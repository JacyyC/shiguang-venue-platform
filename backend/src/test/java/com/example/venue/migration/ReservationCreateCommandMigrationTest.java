package com.example.venue.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V36 预约请求身份迁移的升级验证（S03）。
 *
 * <p>Flyway 是 append-only：本用例在一次性隔离库里先升到 V35，确认新表尚不存在并留下数据，
 * 再升到最新版本，验证前一版本升级路径、既有数据不被改写，以及新表的约束真的能拦住脏状态。</p>
 */
@Testcontainers
class ReservationCreateCommandMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("reservation_migration")
        .withUsername("migration")
        .withPassword("migration")
        .withEnv("TZ", "Asia/Shanghai")
        .withCommand("--character-set-server=utf8mb4",
                     "--collation-server=utf8mb4_unicode_ci",
                     "--log-bin-trust-function-creators=1");

    @Test
    void upgradesFromThePreviousVersionWithoutRewritingExistingData() throws Exception {
        Flyway toPrevious = flyway(MigrationVersion.fromVersion("35"));
        assertThat(toPrevious.migrate().targetSchemaVersion).isEqualTo("35");
        assertThat(tableExists("reservation_create_command")).isFalse();

        long configId = seedExistingConfigRow();

        Flyway toLatest = flyway(MigrationVersion.LATEST);
        assertThat(toLatest.migrate().targetSchemaVersion).isEqualTo("36");
        assertThat(tableExists("reservation_create_command")).isTrue();
        assertThat(columnExists("reservation_create_command", "idempotency_key")).isTrue();
        assertThat(uniqueIndexExists("reservation_create_command", "uk_reservation_create_command_user_key")).isTrue();

        // 既有数据在升级中未被改写
        assertThat(configValue(configId)).isEqualTo("upgrade-fixture");

        // 迁移可重复执行（第二次没有待执行脚本，也不报错）
        assertThat(toLatest.migrate().migrationsExecuted).isZero();

        assertPendingAndCompletedConstraints();
    }

    private Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .locations("classpath:db/migration")
            .target(target)
            .load();
    }

    private long seedExistingConfigRow() throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO sys_config(config_key, config_value, config_name, value_type, editable)
                 VALUES ('migration.upgrade.fixture', 'upgrade-fixture', '升级夹具', 'STRING', 'N')
                 """, Statement.RETURN_GENERATED_KEYS)) {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private String configValue(long id) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT config_value FROM sys_config WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    /** 新表的 CHECK 约束必须真的拦住「处理中却已有结果」这类脏状态。 */
    private void assertPendingAndCompletedConstraints() throws SQLException {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        try (Connection connection = connect()) {
            try (PreparedStatement pending = connection.prepareStatement("""
                INSERT INTO reservation_create_command(user_id, idempotency_key, request_hash, status, create_time)
                VALUES (?, ?, ?, 'PROCESSING', ?)
                """)) {
                pending.setLong(1, 7001L);
                pending.setString(2, "migration-pending-key");
                pending.setString(3, "0".repeat(64));
                pending.setTimestamp(4, now);
                assertThat(pending.executeUpdate()).isEqualTo(1);
            }

            try (PreparedStatement completed = connection.prepareStatement("""
                INSERT INTO reservation_create_command(
                    user_id, idempotency_key, request_hash, status, reservation_id, result_status,
                    create_time, completed_at
                ) VALUES (?, ?, ?, 'COMPLETED', ?, 'PENDING_REVIEW', ?, ?)
                """)) {
                completed.setLong(1, 7001L);
                completed.setString(2, "migration-completed-key");
                completed.setString(3, "1".repeat(64));
                completed.setLong(4, 99L);
                completed.setTimestamp(5, now);
                completed.setTimestamp(6, now);
                assertThat(completed.executeUpdate()).isEqualTo(1);
            }

            // 处理中却已有结果：违反 CHECK，必须被拒绝
            assertThatThrownBy(() -> {
                try (PreparedStatement invalid = connection.prepareStatement("""
                    INSERT INTO reservation_create_command(
                        user_id, idempotency_key, request_hash, status, reservation_id, create_time
                    ) VALUES (?, ?, ?, 'PROCESSING', ?, ?)
                    """)) {
                    invalid.setLong(1, 7001L);
                    invalid.setString(2, "migration-invalid-key");
                    invalid.setString(3, "2".repeat(64));
                    invalid.setLong(4, 100L);
                    invalid.setTimestamp(5, now);
                    invalid.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);

            // 同用户同键只能有一条
            assertThatThrownBy(() -> {
                try (PreparedStatement duplicate = connection.prepareStatement("""
                    INSERT INTO reservation_create_command(user_id, idempotency_key, request_hash, status, create_time)
                    VALUES (?, 'migration-pending-key', ?, 'PROCESSING', ?)
                    """)) {
                    duplicate.setLong(1, 7001L);
                    duplicate.setString(2, "3".repeat(64));
                    duplicate.setTimestamp(3, now);
                    duplicate.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);

            // 不同用户可以用同名键（键的作用域包含用户）
            try (PreparedStatement other = connection.prepareStatement("""
                INSERT INTO reservation_create_command(user_id, idempotency_key, request_hash, status, create_time)
                VALUES (?, 'migration-pending-key', ?, 'PROCESSING', ?)
                """)) {
                other.setLong(1, 7002L);
                other.setString(2, "4".repeat(64));
                other.setTimestamp(3, now);
                assertThat(other.executeUpdate()).isEqualTo(1);
            }
        }
    }

    private boolean tableExists(String tableName) throws SQLException {
        return scalar("""
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = ?
            """, tableName) > 0;
    }

    private boolean columnExists(String tableName, String columnName) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                 """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1) > 0;
            }
        }
    }

    private boolean uniqueIndexExists(String tableName, String indexName) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? AND non_unique = 0
                 """)) {
            statement.setString(1, tableName);
            statement.setString(2, indexName);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1) > 0;
            }
        }
    }

    private int scalar(String sql, String parameter) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter == null) {
                statement.setNull(1, Types.VARCHAR);
            } else {
                statement.setString(1, parameter);
            }
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
            MYSQL.getJdbcUrl() + "?connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true",
            MYSQL.getUsername(), MYSQL.getPassword());
    }
}
