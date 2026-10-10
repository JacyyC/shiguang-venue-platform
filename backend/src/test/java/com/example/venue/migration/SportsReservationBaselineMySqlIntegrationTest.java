package com.example.venue.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SportsReservationBaselineMySqlIntegrationTest {
    private static final String DUPLICATE_CREDENTIAL_HASH = "v12-duplicate-credential-hash";
    private static List<String> versionsBeforeUpgrade;
    private static int invalidatedDuplicateCredentials;
    private static int duplicateCredentialAuditEvents;
    private static int retainedUserGuardRows;
    private static int roleCodeColumnsAfterUpgrade;
    private static List<String> versionsBeforeV14;
    private static List<String> legacyReservationSnapshots;
    private static List<String> legacyAllocationSnapshots;
    private static List<String> versionsBeforeV15;
    private static List<String> versionsBeforeV16;
    private static List<String> versionsBeforeV17;
    private static int bridgeRowsAfterUpgrade;
    private static int bridgePointsAfterUpgrade;
    private static int bridgeRowsAfterReplay;
    private static List<String> bridgeProjectionRebuilds;
    private static List<String> bridgeFactSnapshots;

    private static final List<String> CORE_BUSINESS_TABLES = List.of(
        "venue",
        "venue_space",
        "reservation",
        "biz_order",
        "payment_record",
        "notice",
        "customer_complaint",
        "provider_call_log",
        "weather_observation_snapshot",
        "community_group",
        "community_group_member",
        "community_post",
        "community_comment",
        "community_user_block",
        "community_report",
        "community_governance_event",
        "community_activity",
        "community_companion_request",
        "community_activity_application",
        "community_activity_participation",
        "community_match_decision",
        "community_activity_state_event",
        "community_activity_command",
        "community_activity_notification_task",
        "member_reward_catalog_item",
        "member_reward_catalog_version",
        "member_reward_redemption",
        "member_reward_event"
    );

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("sports_reservation_baseline")
        .withUsername("baseline")
        .withPassword("baseline")
        .withCommand("--character-set-server=utf8mb4",
                     "--collation-server=utf8mb4_unicode_ci",
                     "--log-bin-trust-function-creators=1");

    @BeforeAll
    static void migrateEmptyDatabaseAndExistingData() throws SQLException {
        Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .target("11")
            .load()
            .migrate();

        versionsBeforeUpgrade = queryStrings(successfulMigrationVersionsSql());
        seedV12AndV13UpgradeData();

        Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .target("13")
            .load()
            .migrate();

        invalidatedDuplicateCredentials = queryInt("""
            SELECT COUNT(*)
            FROM reservation_lifecycle
            WHERE reservation_id IN (910001, 910002)
              AND checkin_code_hash IS NULL
              AND checkin_code_last4 IS NULL
              AND credential_status = 'INVALIDATED'
            """);
        duplicateCredentialAuditEvents = queryInt("""
            SELECT COUNT(*)
            FROM reservation_lifecycle_event
            WHERE reservation_id IN (910001, 910002)
              AND event_type = 'CHECKIN_CREDENTIAL_INVALIDATED'
              AND reason_code = 'DUPLICATE_CREDENTIAL_HASH'
            """);
        retainedUserGuardRows = queryInt("""
            SELECT COUNT(*)
            FROM site_shift_assignment_guard
            WHERE assigned_user_id = 920001
            """);
        roleCodeColumnsAfterUpgrade = queryInt("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'site_shift_assignment_guard'
              AND column_name = 'role_code'
            """);

        versionsBeforeV14 = queryStrings(successfulMigrationVersionsSql());
        seedV14UpgradeData();
        Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .target("14")
            .load()
            .migrate();
        legacyReservationSnapshots = queryStrings("""
            SELECT CONCAT(resource_category_snapshot, ':', booking_mode_snapshot, ':',
                          rule_version_snapshot, ':', participant_count, ':', rule_id_snapshot IS NOT NULL)
            FROM reservation
            WHERE id = 940001
            """);
        legacyAllocationSnapshots = queryStrings("""
            SELECT CONCAT(resource_category_snapshot, ':', booking_mode, ':',
                          rule_version_snapshot, ':', quantity, ':', rule_id IS NOT NULL, ':', active_flag)
            FROM reservation_occupancy
            WHERE reservation_id = 940001
            """);

        versionsBeforeV15 = queryStrings(successfulMigrationVersionsSql());
        remapSystemAdminAndAdvancePermissionSequences();
        Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .target("15")
            .load()
            .migrate();
        versionsBeforeV16 = queryStrings(successfulMigrationVersionsSql());
        Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .target("16")
            .load()
            .migrate();
        versionsBeforeV17 = queryStrings(successfulMigrationVersionsSql());
        seedV17BridgeData();
        Flyway flyway = Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .load();
        flyway.migrate();
        bridgeRowsAfterUpgrade = queryInt("""
            SELECT COUNT(*)
            FROM member_account_log
            WHERE event_type = 'MEMBER_POINTS_OPENING_BRIDGE'
              AND account_id IN (980001, 980002, 980003)
            """);
        bridgePointsAfterUpgrade = queryInt("""
            SELECT COALESCE(SUM(points_delta), 0)
            FROM member_account_log
            WHERE event_type = 'MEMBER_POINTS_OPENING_BRIDGE'
              AND account_id IN (980001, 980002, 980003)
            """);
        flyway.migrate();
        bridgeRowsAfterReplay = queryInt("""
            SELECT COUNT(*)
            FROM member_account_log
            WHERE event_type = 'MEMBER_POINTS_OPENING_BRIDGE'
              AND account_id IN (980001, 980002, 980003)
            """);
        bridgeProjectionRebuilds = queryStrings("""
            SELECT CONCAT(a.id, ':', a.points_balance, ':', COALESCE(SUM(l.points_delta), 0))
            FROM member_account a
            LEFT JOIN member_account_log l ON l.account_id = a.id
            WHERE a.id IN (980001, 980002, 980003)
            GROUP BY a.id, a.points_balance
            ORDER BY a.id
            """);
        bridgeFactSnapshots = queryStrings("""
            SELECT CONCAT(account_id, ':', business_key, ':', rule_version, ':',
                          points_balance_before, ':', points_balance_after)
            FROM member_account_log
            WHERE event_type = 'MEMBER_POINTS_OPENING_BRIDGE'
              AND account_id IN (980001, 980002, 980003)
            ORDER BY account_id
            """);
        cleanupUpgradeFixtures();
    }

    @Test
    void flywayKeepsTheV1V2BaselineAndAppliesV3ToV36AppendOnlyRemediations() throws SQLException {
        assertThat(queryStrings(successfulMigrationVersionsSql()))
            .containsExactly(
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34", "35", "36"
            );
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_type = 'BASE TABLE'
              AND table_name <> 'flyway_schema_history'
            """))
            // V36 新增 reservation_create_command（预约创建的请求身份与结果核对）
            .isEqualTo(148);
        assertThat(queryInt("""
            SELECT COUNT(DISTINCT perms)
            FROM sys_menu
            WHERE deleted = 0
            AND perms IN ('stored-value:recharge:execute', 'stored-value:refund:review',
                            'stored-value:account:unfreeze:review', 'stored-value:account:close')
            """))
            .isEqualTo(4);
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM sys_role_menu relation
            JOIN sys_menu menu ON menu.id = relation.menu_id
            WHERE menu.deleted = 0
              AND menu.perms IN ('stored-value:recharge:execute', 'stored-value:refund:review',
                                 'stored-value:account:unfreeze:review', 'stored-value:account:close')
            """))
            .isZero();
    }

    @Test
    void existingV11DatabaseRepairsDuplicateCredentialsAndUserLevelShiftGuards() {
        assertThat(versionsBeforeUpgrade)
            .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        assertThat(invalidatedDuplicateCredentials).isEqualTo(2);
        assertThat(duplicateCredentialAuditEvents).isEqualTo(2);
        assertThat(retainedUserGuardRows).isEqualTo(1);
        assertThat(roleCodeColumnsAfterUpgrade).isZero();
    }

    @Test
    void existingV13DatabaseBackfillsLegacyExclusiveRulesAndAllocationSnapshots() {
        assertThat(versionsBeforeV14)
            .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13");
        assertThat(legacyReservationSnapshots)
            .containsExactly("SPORTS_FIELD:EXCLUSIVE:1:1:1");
        assertThat(legacyAllocationSnapshots)
            .containsExactly("SPORTS_FIELD:EXCLUSIVE:1:1:1:1");
    }

    @Test
    void existingV14DatabaseUpgradesToV15WithoutDependingOnBootstrapIds() throws SQLException {
        assertThat(versionsBeforeV15)
            .containsExactly(
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14"
            );
        assertThat(queryInt("SELECT id FROM sys_role WHERE role_key = 'SYSTEM_ADMIN'"))
            .isEqualTo(950003);
        assertThat(queryInt("""
            SELECT MIN(id)
            FROM sys_menu
            WHERE perms IN (
              'content-admin:view', 'community:report:review', 'community:content:moderate',
              'community:content:restore', 'community:audit:view'
            )
            """))
            .isGreaterThanOrEqualTo(951000);
        assertThat(queryInt("""
            SELECT MIN(relation.id)
            FROM sys_role_menu relation
            JOIN sys_role role ON role.id = relation.role_id
            JOIN sys_menu menu ON menu.id = relation.menu_id
            WHERE role.role_key = 'SYSTEM_ADMIN'
              AND menu.perms IN ('content-admin:view', 'community:audit:view')
            """))
            .isGreaterThanOrEqualTo(952000);
    }

    @Test
    void existingV15DatabaseUpgradesToV16WithoutRewritingEarlierMigrations() {
        assertThat(versionsBeforeV16)
            .containsExactly(
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15"
            );
    }

    @Test
    void existingV16DatabaseBridgesOnlyNonZeroProjectionDifferencesOnce() throws SQLException {
        assertThat(versionsBeforeV17)
            .containsExactly(
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16"
            );
        assertThat(bridgeRowsAfterUpgrade).isEqualTo(2);
        assertThat(bridgePointsAfterUpgrade).isEqualTo(45);
        assertThat(bridgeRowsAfterReplay).isEqualTo(2);
        assertThat(bridgeProjectionRebuilds)
            .containsExactly("980001:30:30", "980002:75:75", "980003:-10:-10");
        assertThat(bridgeFactSnapshots)
            .containsExactly(
                "980002:OPENING_BRIDGE:R4_V17:ACCOUNT:980002:R4_OPENING_V1:20:75",
                "980003:OPENING_BRIDGE:R4_V17:ACCOUNT:980003:R4_OPENING_V1:0:-10"
            );
    }

    @Test
    void v17CreatesRewardContractsAndKeepsPermissionsSeparated() throws SQLException {
        assertThat(queryStrings("""
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name IN (
                'member_reward_catalog_item', 'member_reward_catalog_version',
                'member_reward_redemption', 'member_reward_event'
              )
            ORDER BY table_name
            """))
            .containsExactly(
                "member_reward_catalog_item", "member_reward_catalog_version",
                "member_reward_event", "member_reward_redemption"
            );
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'member_account_log'
              AND column_name IN (
                'business_key', 'rule_version', 'points_balance_before', 'points_balance_after'
              )
            """)).isEqualTo(4);
        assertThat(queryInt("""
            SELECT COUNT(DISTINCT perms)
            FROM sys_menu
            WHERE perms IN (
              'member-reward:me', 'member-reward:catalog:manage',
              'member-reward:exception:manage', 'member-reward:audit:view'
            ) AND deleted = 0
            """)).isEqualTo(4);
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM sys_role role
            JOIN sys_role_menu relation ON relation.role_id = role.id
            JOIN sys_menu menu ON menu.id = relation.menu_id
            WHERE menu.perms IN (
              'member-reward:catalog:manage', 'member-reward:exception:manage', 'member-reward:audit:view'
            )
            """)).isZero();
    }

    @Test
    void v18SeedsHomePrimarySlotAndGrantsOnlyAuditToSystemAdmin() throws SQLException {
        assertThat(queryStrings("""
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name IN (
                'placement_slot', 'placement_candidate_version',
                'placement_decision', 'placement_event'
              )
            ORDER BY table_name
            """))
            .containsExactly(
                "placement_candidate_version",
                "placement_decision",
                "placement_event",
                "placement_slot"
            );
        assertThat(queryStrings("""
            SELECT CONCAT(slot_code, ':', slot_name, ':', status)
            FROM placement_slot
            """))
            .containsExactly("HOME_PRIMARY:首页自营宣传位:ENABLED");
        assertThat(queryInt("""
            SELECT COUNT(DISTINCT perms)
            FROM sys_menu
            WHERE perms IN ('placement:manage','placement:publish','placement:audit:view')
              AND deleted = 0
            """))
            .isEqualTo(3);
        assertThat(queryStrings("""
            SELECT menu.perms
            FROM sys_role role
            JOIN sys_role_menu relation ON relation.role_id = role.id
            JOIN sys_menu menu ON menu.id = relation.menu_id
            WHERE role.role_key = 'SYSTEM_ADMIN'
              AND menu.perms IN ('placement:manage','placement:publish','placement:audit:view')
            ORDER BY menu.perms
            """))
            .containsExactly("placement:audit:view");
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM sys_role role
            JOIN sys_role_menu relation ON relation.role_id = role.id
            JOIN sys_menu menu ON menu.id = relation.menu_id
            WHERE role.role_key = 'VENUE_ADMIN'
              AND menu.perms IN ('placement:manage','placement:publish','placement:audit:view')
            """))
            .isZero();
    }

    @Test
    void v24CreatesAdvertisingFactsAndGrantsOnlyAuditToSystemAdmin() throws SQLException {
        assertThat(queryStrings("""
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name IN (
                'advertising_advertiser', 'advertising_campaign',
                'advertising_creative_version', 'advertising_audit_event'
              )
            ORDER BY table_name
            """))
            .containsExactly(
                "advertising_advertiser",
                "advertising_audit_event",
                "advertising_campaign",
                "advertising_creative_version"
            );
        assertThat(queryInt("""
            SELECT COUNT(DISTINCT perms)
            FROM sys_menu
            WHERE perms IN (
              'advertising:manage', 'advertising:review', 'advertising:publish',
              'advertising:analysis:view', 'advertising:audit:view'
            ) AND deleted = 0
            """))
            .isEqualTo(5);
        assertThat(queryStrings("""
            SELECT menu.perms
            FROM sys_role role
            JOIN sys_role_menu relation ON relation.role_id = role.id
            JOIN sys_menu menu ON menu.id = relation.menu_id
            WHERE role.role_key = 'SYSTEM_ADMIN'
              AND menu.perms IN (
                'advertising:manage', 'advertising:review', 'advertising:publish',
                'advertising:analysis:view', 'advertising:audit:view'
              )
            ORDER BY menu.perms
            """))
            .containsExactly("advertising:audit:view");
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM sys_role role
            JOIN sys_role_menu relation ON relation.role_id = role.id
            JOIN sys_menu menu ON menu.id = relation.menu_id
            WHERE role.role_key = 'VENUE_ADMIN'
              AND menu.perms IN (
                'advertising:manage', 'advertising:review', 'advertising:publish',
                'advertising:analysis:view', 'advertising:audit:view'
              )
            """))
            .isZero();
    }

    @Test
    void v24RejectsInvalidAdvertisingStatusAndExternalCreativeTarget() throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO advertising_campaign (
                      campaign_no, advertiser_id, campaign_name,
                      effective_from, effective_until, status, created_by
                    ) VALUES (
                      'ADV-BAD-CAMP-1', 1, '非法计划',
                      '2030-01-01 00:00:00', '2030-01-02 00:00:00', 'HACKED', 1
                    )
                    """))
                    .isInstanceOf(SQLException.class);

                executeUpdate(connection, """
                    INSERT INTO advertising_advertiser (
                      advertiser_no, advertiser_name, status, created_by
                    ) VALUES ('ADV-GOOD-ADVER-1', '测试广告主', 'ACTIVE', 1)
                    """);
                long advertiserId = queryLong(connection,
                    "SELECT id FROM advertising_advertiser WHERE advertiser_no = 'ADV-GOOD-ADVER-1'");
                executeUpdate(connection, String.format("""
                    INSERT INTO advertising_campaign (
                      campaign_no, advertiser_id, campaign_name,
                      effective_from, effective_until, status, created_by
                    ) VALUES (
                      'ADV-GOOD-CAMP-1', %d, '测试计划',
                      '2030-01-01 00:00:00', '2030-01-02 00:00:00', 'DRAFT', 1
                    )
                    """, advertiserId));
                long campaignId = queryLong(connection,
                    "SELECT id FROM advertising_campaign WHERE campaign_no = 'ADV-GOOD-CAMP-1'");

                assertThatThrownBy(() -> executeUpdate(connection, String.format("""
                    INSERT INTO advertising_creative_version (
                      creative_no, campaign_id, version_no, headline, body_text,
                      call_to_action, target_type, target_id, status, created_by
                    ) VALUES (
                      'ADV-BAD-CREAT-1', %d, 1, '标题', '正文',
                      'VIEW', 'EXTERNAL_URL', 9, 'DRAFT', 1
                    )
                    """, campaignId)))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection, String.format("""
                    INSERT INTO advertising_creative_version (
                      creative_no, campaign_id, version_no, headline, body_text,
                      call_to_action, target_type, target_id, status, created_by
                    ) VALUES (
                      'ADV-BAD-CREAT-2', %d, 2, '标题', '正文',
                      'VIEW', 'VENUE_LIST', 9, 'DRAFT', 1
                    )
                    """, campaignId)))
                    .isInstanceOf(SQLException.class);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void v25CreatesIndependentPrivacyDecisionGuardAndMeasurementFacts() throws SQLException {
        assertThat(queryStrings("""
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name IN (
                'advertising_privacy_preference', 'advertising_decision_guard',
                'advertising_decision', 'advertising_event'
              )
            ORDER BY table_name
            """))
            .containsExactly(
                "advertising_decision",
                "advertising_decision_guard",
                "advertising_event",
                "advertising_privacy_preference"
            );
        assertThat(queryInt("""
            SELECT COUNT(DISTINCT index_name)
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'advertising_event'
              AND index_name IN (
                'uk_advertising_event_key', 'uk_advertising_event_decision_type',
                'idx_advertising_event_user_slot_time', 'idx_advertising_event_user_creative_time'
              )
            """))
            .isEqualTo(4);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO advertising_privacy_preference (
                      user_id, commercial_enabled, measurement_enabled, policy_version
                    ) VALUES (990001, 2, 1, 'G-R7-20260803-v1')
                    """))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO advertising_decision_guard (user_id, slot_code, scope_key)
                    VALUES (990001, 'HOME_PRIMARY', 'SLOT')
                    """))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO advertising_event (
                      event_no, event_key, event_type, decision_id, user_id, slot_code,
                      advertiser_id, campaign_id, creative_version_id, request_hash,
                      result_status, policy_version, occurred_at
                    ) VALUES (
                      'ADE-BAD-1', 'ADE-BAD-KEY-1', 'FORM_LOAD', 1, 990001, 'HOME_COMMERCIAL',
                      1, 1, 1, REPEAT('a', 64), 'ACCEPTED', 'G-R7-20260803-v1', CURRENT_TIMESTAMP
                    )
                    """))
                    .isInstanceOf(SQLException.class);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void v26CreatesConversionAttributionFactsAndRejectsInvalidSnapshots() throws SQLException {
        assertThat(queryStrings("""
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name IN ('advertising_conversion_callback', 'advertising_attribution')
            ORDER BY table_name
            """))
            .containsExactly("advertising_attribution", "advertising_conversion_callback");
        assertThat(queryStrings("""
            SELECT DISTINCT CONCAT(table_name, ':', index_name)
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name IN ('advertising_conversion_callback', 'advertising_attribution')
              AND index_name <> 'PRIMARY'
            ORDER BY 1
            """))
            .containsExactly(
                "advertising_attribution:idx_advertising_attribution_campaign_time",
                "advertising_attribution:idx_advertising_attribution_click_event",
                "advertising_attribution:idx_advertising_attribution_status_time",
                "advertising_attribution:idx_advertising_attribution_user_status_time",
                "advertising_attribution:uk_advertising_attribution_no",
                "advertising_attribution:uk_advertising_attribution_reservation",
                "advertising_conversion_callback:idx_advertising_callback_reservation_event",
                "advertising_conversion_callback:idx_advertising_callback_result_time",
                "advertising_conversion_callback:idx_advertising_callback_retry",
                "advertising_conversion_callback:idx_advertising_callback_status_time",
                "advertising_conversion_callback:uk_advertising_callback_event_key",
                "advertising_conversion_callback:uk_advertising_callback_no"
            );
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO advertising_conversion_callback (
                      callback_no, event_key, event_type, reservation_id, user_id,
                      source_status, policy_version, occurred_at
                    ) VALUES (
                      'ACB-BAD-1', 'ACB-BAD-KEY-1', 'UNSUPPORTED', 991001, 991001,
                      'PENDING_REVIEW', 'G-R7-20260803-v1', CURRENT_TIMESTAMP
                    )
                    """))
                    .isInstanceOf(SQLException.class);

                executeUpdate(connection, """
                    INSERT INTO advertising_attribution (
                      attribution_no, reservation_id, user_id, conversion_occurred_at,
                      policy_version, rule_version_snapshot, status, reason_code,
                      source_status, source_event_key
                    ) VALUES (
                      'AAT-GAP-1', 991001, 991001, CURRENT_TIMESTAMP,
                      'G-R7-20260803-v1', 'ADVERTISING_ATTRIBUTION_V1', 'GAP',
                      'NO_VALID_CLICK', 'PENDING_REVIEW', 'RESERVATION_SUBMITTED:991001'
                    )
                    """);
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO advertising_attribution (
                      attribution_no, reservation_id, user_id, click_event_id,
                      decision_id, advertiser_id, campaign_id, creative_version_id,
                      click_occurred_at, conversion_occurred_at, policy_version,
                      rule_version_snapshot, status, reason_code, source_status,
                      source_event_key
                    ) VALUES (
                      'AAT-BAD-1', 991001, 991001, 1, 1, 1, 1, 1,
                      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'G-R7-20260803-v1',
                      'ADVERTISING_ATTRIBUTION_V1', 'ATTRIBUTED', 'LAST_VALID_CLICK_7D',
                      'PENDING_REVIEW', 'RESERVATION_SUBMITTED:991001'
                    )
                    """))
                    .isInstanceOf(SQLException.class);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void v18RejectsInvalidCandidateAudienceWindowFrequencyAndEventVisibility() throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                executeUpdate(connection, """
                    INSERT INTO placement_slot (slot_code, slot_name, status)
                    VALUES ('BAD_SLOT', '反例版位', 'ENABLED')
                    """);
                long slotId = queryLong(connection, "SELECT id FROM placement_slot WHERE slot_code = 'BAD_SLOT'");
                assertThatThrownBy(() -> executeUpdate(connection, String.format("""
                    INSERT INTO placement_candidate_version (
                      slot_id, candidate_no, source_type, source_id, source_version,
                      effective_from, effective_until, audience_type, audience_level_code,
                      priority, frequency_cap_per_user, frequency_cap_window_hours, rule_version, status
                    ) VALUES (
                      %d, 'BAD-CAND-1', 'GLOBAL_NOTICE', 1, 'v1',
                      '2030-01-01 00:00:00', '2029-12-31 23:59:59',
                      'ALL_AUTHENTICATED', NULL, 0, 1, 24, 'PLACEMENT_RULE_V1', 'DRAFT'
                    )
                    """, slotId)))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection, String.format("""
                    INSERT INTO placement_candidate_version (
                      slot_id, candidate_no, source_type, source_id, source_version,
                      effective_from, effective_until, audience_type, audience_level_code,
                      priority, frequency_cap_per_user, frequency_cap_window_hours, rule_version, status
                    ) VALUES (
                      %d, 'BAD-CAND-2', 'GLOBAL_NOTICE', 1, 'v1',
                      '2030-01-01 00:00:00', '2030-01-02 00:00:00',
                      'MEMBER_LEVEL', NULL, 0, 1, 24, 'PLACEMENT_RULE_V1', 'DRAFT'
                    )
                    """, slotId)))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection, String.format("""
                    INSERT INTO placement_candidate_version (
                      slot_id, candidate_no, source_type, source_id, source_version,
                      effective_from, effective_until, audience_type, audience_level_code,
                      priority, frequency_cap_per_user, frequency_cap_window_hours, rule_version, status
                    ) VALUES (
                      %d, 'BAD-CAND-3', 'GLOBAL_NOTICE', 1, 'v1',
                      '2030-01-01 00:00:00', '2030-01-02 00:00:00',
                      'ALL_AUTHENTICATED', NULL, 0, 0, 24, 'PLACEMENT_RULE_V1', 'DRAFT'
                    )
                    """, slotId)))
                    .isInstanceOf(SQLException.class);
                executeUpdate(connection, String.format("""
                    INSERT INTO placement_candidate_version (
                      slot_id, candidate_no, source_type, source_id, source_version,
                      effective_from, effective_until, audience_type, audience_level_code,
                      priority, frequency_cap_per_user, frequency_cap_window_hours, rule_version, status
                    ) VALUES (
                      %d, 'GOOD-CAND-1', 'GLOBAL_NOTICE', 1, 'v1',
                      '2030-01-01 00:00:00', '2030-01-02 00:00:00',
                      'ALL_AUTHENTICATED', NULL, 0, 1, 24, 'PLACEMENT_RULE_V1', 'PUBLISHED'
                    )
                    """, slotId));
                long candidateId = queryLong(connection,
                    "SELECT id FROM placement_candidate_version WHERE candidate_no = 'GOOD-CAND-1'");
                executeUpdate(connection, String.format("""
                    INSERT INTO placement_decision (
                      decision_no, user_id, slot_id, candidate_version_id, token_hash,
                      rule_version_snapshot, audience_snapshot_json, frequency_snapshot_json,
                      source_snapshot_json, priority_snapshot, expires_at, status
                    ) VALUES (
                      'PLD-BAD-1', 9900101, %d, %d,
                      '0000000000000000000000000000000000000000000000000000000000000000',
                      'PLACEMENT_RULE_V1', JSON_OBJECT(), JSON_OBJECT(), JSON_OBJECT(), 0,
                      '2030-01-01 00:05:00', 'ISSUED'
                    )
                    """, slotId, candidateId));
                long decisionId = queryLong(connection,
                    "SELECT id FROM placement_decision WHERE decision_no = 'PLD-BAD-1'");
                assertThatThrownBy(() -> executeUpdate(connection, String.format("""
                    INSERT INTO placement_event (
                      event_no, event_key, event_type, decision_id, user_id, slot_id,
                      candidate_version_id, request_hash, visible_ratio, visible_duration_ms,
                      result_status, occurred_at
                    ) VALUES (
                      'PLE-BAD-1', 'PLE-BAD-1-KEY', 'CLIENT_VIEWABLE', %d,
                      9900101, %d, %d,
                      'hash-1', NULL, NULL, 'ACCEPTED', '2030-01-01 00:01:00'
                    )
                    """, decisionId, slotId, candidateId)))
                    .isInstanceOf(SQLException.class);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void v16CreatesNormalizedActivityMatchingAndHandoffContracts() throws SQLException {
        assertThat(queryStrings("""
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name IN (
                'community_activity', 'community_companion_request',
                'community_activity_application', 'community_activity_participation',
                'community_match_decision', 'community_activity_state_event',
                'community_activity_command', 'community_activity_notification_task'
              )
            ORDER BY table_name
            """))
            .containsExactly(
                "community_activity",
                "community_activity_application",
                "community_activity_command",
                "community_activity_notification_task",
                "community_activity_participation",
                "community_activity_state_event",
                "community_companion_request",
                "community_match_decision"
            );
        assertThat(queryStrings("""
            SELECT CONCAT(table_name, ':', constraint_name)
            FROM information_schema.table_constraints
            WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'
              AND table_name IN ('community_activity', 'community_companion_request',
                'community_activity_notification_task')
            ORDER BY table_name, constraint_name
            """))
            .contains(
                "community_activity:chk_community_activity_commitment",
                "community_companion_request:chk_community_companion_commitment",
                "community_activity_notification_task:chk_community_activity_notification_timeline"
            );
    }

    @Test
    void v14CreatesNormalizedRuleSeatAndAllocationContracts() throws SQLException {
        assertThat(queryStrings("""
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name IN ('venue_resource_rule', 'venue_resource_seat', 'reservation_occupancy_seat')
            ORDER BY table_name
            """))
            .containsExactly("reservation_occupancy_seat", "venue_resource_rule", "venue_resource_seat");
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND ((table_name = 'reservation' AND column_name IN (
                    'resource_category_snapshot', 'booking_mode_snapshot', 'rule_id_snapshot',
                    'rule_version_snapshot', 'participant_count', 'allocation_snapshot_json'))
                OR (table_name = 'reservation_occupancy' AND column_name IN (
                    'rule_id', 'resource_category_snapshot', 'booking_mode', 'rule_version_snapshot',
                    'quantity', 'allocation_snapshot_json', 'released_at', 'release_reason')))
            """))
            .isEqualTo(14);
        assertThat(queryStrings("""
            SELECT index_name
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'reservation_occupancy'
              AND index_name IN ('uk_reservation_occupancy_slot', 'uk_reservation_occupancy_active',
                                 'idx_reservation_occupancy_space_time_active')
            GROUP BY index_name
            ORDER BY index_name
            """))
            .containsExactly("idx_reservation_occupancy_space_time_active", "uk_reservation_occupancy_active");
    }

    @Test
    void v15CreatesNormalizedCommunityGovernanceAndRetentionContracts() throws SQLException {
        assertThat(queryStrings("""
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name IN (
                'community_group', 'community_group_member', 'community_post', 'community_comment',
                'community_user_block', 'community_report', 'community_governance_event'
              )
            ORDER BY table_name
            """))
            .containsExactly(
                "community_comment",
                "community_governance_event",
                "community_group",
                "community_group_member",
                "community_post",
                "community_report",
                "community_user_block"
            );
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'sys_deleted_user_snapshot'
              AND column_name IN ('deidentify_after', 'deidentified_at')
            """))
            .isEqualTo(2);
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND is_nullable = 'YES'
              AND ((table_name = 'community_group' AND column_name = 'owner_user_id')
                OR (table_name = 'community_group_member' AND column_name = 'user_id')
                OR (table_name = 'community_post' AND column_name = 'author_user_id')
                OR (table_name = 'community_comment' AND column_name = 'author_user_id')
                OR (table_name = 'community_user_block'
                    AND column_name IN ('blocker_user_id', 'blocked_user_id'))
                OR (table_name = 'community_report' AND column_name = 'reporter_user_id'))
            """))
            .isEqualTo(7);
        assertThat(queryInt("""
            SELECT COUNT(DISTINCT CONCAT(table_name, ':', index_name))
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND ((table_name = 'community_group_member'
                    AND index_name = 'uk_community_group_active_owner' AND non_unique = 0)
                OR (table_name = 'community_post' AND index_name = 'idx_community_post_group_cursor')
                OR (table_name = 'community_comment' AND index_name = 'idx_community_comment_post_cursor')
                OR (table_name = 'community_user_block'
                    AND index_name IN ('idx_community_user_block_forward', 'idx_community_user_block_reverse'))
                OR (table_name = 'community_report'
                    AND index_name IN ('idx_community_report_queue', 'idx_community_report_target'))
                OR (table_name = 'community_governance_event'
                    AND index_name IN (
                      'idx_community_governance_event_subject',
                      'idx_community_governance_event_retention'
                    )))
            """))
            .isEqualTo(9);
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM information_schema.key_column_usage
            WHERE table_schema = DATABASE()
              AND table_name LIKE 'community\\_%'
              AND referenced_table_name = 'sys_user'
            """))
            .isZero();
    }

    @Test
    void v15SeedsCommunityAccessAndKeepsContentAdministrationPermissionsSeparate() throws SQLException {
        assertThat(queryStrings("""
            SELECT CONCAT(role.role_key, ':', menu.perms)
            FROM sys_role role
            JOIN sys_role_menu relation ON relation.role_id = role.id
            JOIN sys_menu menu ON menu.id = relation.menu_id
            WHERE role.role_key IN ('USER', 'VENUE_ADMIN', 'SYSTEM_ADMIN')
              AND menu.perms IN ('user:view', 'community:access')
            ORDER BY role.role_key, menu.perms
            """))
            .containsExactly(
                "SYSTEM_ADMIN:community:access",
                "SYSTEM_ADMIN:user:view",
                "USER:community:access",
                "USER:user:view",
                "VENUE_ADMIN:community:access",
                "VENUE_ADMIN:user:view"
            );
        assertThat(queryStrings("""
            SELECT CONCAT(path, ':', component, ':', visible)
            FROM sys_menu
            WHERE perms = 'community:access' AND deleted = 0
            """))
            .containsExactly("/community:CommunityHome:Y");
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM sys_menu
            WHERE perms IN (
              'content-admin:view', 'community:report:review', 'community:content:moderate',
              'community:content:restore', 'community:audit:view'
            )
              AND deleted = 0
            """))
            .isEqualTo(5);
        assertThat(queryStrings("""
            SELECT CONCAT(path, ':', visible)
            FROM sys_menu
            WHERE perms = 'content-admin:view' AND deleted = 0
            """))
            .containsExactly("/content-admin:N");
        assertThat(queryStrings("""
            SELECT menu.perms
            FROM sys_role role
            JOIN sys_role_menu relation ON relation.role_id = role.id
            JOIN sys_menu menu ON menu.id = relation.menu_id
            WHERE role.role_key = 'SYSTEM_ADMIN'
              AND menu.perms IN (
                'content-admin:view', 'community:report:review', 'community:content:moderate',
                'community:content:restore', 'community:audit:view'
              )
            ORDER BY menu.perms
            """))
            .containsExactly("community:audit:view", "content-admin:view");
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM sys_role role
            JOIN sys_role_menu relation ON relation.role_id = role.id
            JOIN sys_menu menu ON menu.id = relation.menu_id
            WHERE role.role_key = 'VENUE_ADMIN'
              AND menu.perms IN (
                'content-admin:view', 'community:report:review', 'community:content:moderate',
                'community:content:restore', 'community:audit:view'
              )
            """))
            .isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM sys_role WHERE role_key = 'CONTENT_OPERATOR'"))
            .isZero();
    }

    @Test
    void v15RejectsInvalidCommunityOwnershipContentAndRetentionFacts() throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                executeUpdate(connection, """
                    INSERT INTO community_group (
                      id, group_no, name, description, owner_user_id, visibility, join_policy, status,
                      idempotency_key, request_hash, version_no, create_time, update_time
                    ) VALUES (
                      970001, 'GROUP-970001', '迁移约束测试组', '仅在事务内存在', 970100,
                      'PLATFORM_VISIBLE', 'REVIEW_REQUIRED', 'ACTIVE',
                      'group-create-970001', 'request-hash', 0,
                      '2026-01-01 00:00:00', '2026-01-01 00:00:00'
                    )
                    """);
                executeUpdate(connection, """
                    INSERT INTO community_group_member (
                      id, group_id, user_id, member_role, status, joined_at, create_time, update_time
                    ) VALUES (
                      970001, 970001, 970100, 'OWNER', 'ACTIVE',
                      '2026-01-01 00:00:00', '2026-01-01 00:00:00', '2026-01-01 00:00:00'
                    )
                    """);

                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO community_group_member (
                      id, group_id, user_id, member_role, status, joined_at
                    ) VALUES (970002, 970001, 970100, 'MEMBER', 'ACTIVE', '2026-01-01 00:00:00')
                    """))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO community_group_member (
                      id, group_id, user_id, member_role, status, joined_at
                    ) VALUES (970003, 970001, 970101, 'OWNER', 'ACTIVE', '2026-01-01 00:00:00')
                    """))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO community_user_block (
                      id, blocker_user_id, blocked_user_id, status, last_idempotency_key
                    ) VALUES (970001, 970100, 970100, 'ACTIVE', 'block-self-970001')
                    """))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO community_user_block (
                      id, blocker_user_id, blocked_user_id, status, last_idempotency_key
                    ) VALUES (970002, NULL, 970101, 'ACTIVE', 'block-missing-actor-970002')
                    """))
                    .isInstanceOf(SQLException.class);
                executeUpdate(connection, """
                    INSERT INTO community_user_block (
                      id, blocker_user_id, blocked_user_id, status, last_idempotency_key, removed_at
                    ) VALUES (
                      970003, NULL, NULL, 'REMOVED', 'block-deidentified-970003',
                      '2027-01-01 00:00:00'
                    )
                    """);
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO community_group (
                      id, group_no, name, owner_user_id, visibility, join_policy, status,
                      idempotency_key, request_hash
                    ) VALUES (
                      970002, 'GROUP-970002', '非法状态组', 970102,
                      'PLATFORM_VISIBLE', 'DIRECT', 'UNKNOWN',
                      'group-create-970002', 'request-hash'
                    )
                    """))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO community_group (
                      id, group_no, name, owner_user_id, visibility, join_policy, status,
                      idempotency_key, request_hash
                    ) VALUES (
                      970003, 'GROUP-970003', REPEAT('x', 41), 970103,
                      'PLATFORM_VISIBLE', 'DIRECT', 'ACTIVE',
                      'group-create-970003', 'request-hash'
                    )
                    """))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO community_post (
                      id, post_no, group_id, author_user_id, title, content_text, status,
                      idempotency_key, request_hash, published_at
                    ) VALUES (
                      970001, 'POST-970001', 970001, 970100, REPEAT('x', 81), '正文', 'PUBLISHED',
                      'post-create-970001', 'request-hash', '2026-01-01 00:00:00'
                    )
                    """))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO community_post (
                      id, post_no, group_id, author_user_id, title, content_text, status,
                      idempotency_key, request_hash, published_at
                    ) VALUES (
                      970002, 'POST-970002', 970001, 970100, '标题', REPEAT('x', 5001), 'PUBLISHED',
                      'post-create-970002', 'request-hash', '2026-01-01 00:00:00'
                    )
                    """))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO community_report (
                      id, report_no, reporter_user_id, target_type, target_id, reason_text, status,
                      idempotency_key, request_hash, minimal_retain_until, create_time, update_time
                    ) VALUES (
                      970001, 'REPORT-970001', 970100, 'POST', 970001, '举报原因', 'RESOLVED',
                      'report-create-970001', 'request-hash', '2027-01-01 00:00:00',
                      '2026-01-01 00:00:00', '2026-01-01 00:00:00'
                    )
                    """))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO community_governance_event (
                      id, event_no, event_type, subject_type, subject_id, actor_scope, source_type,
                      result_status, idempotency_key, metadata_json, occurred_at, retain_until
                    ) VALUES (
                      970001, 'EVENT-970001', 'GROUP_CREATED', 'GROUP', 970001, 'SYSTEM', 'SYSTEM',
                      'SUCCEEDED', 'event-create-970001', JSON_OBJECT(),
                      '2026-01-01 00:00:00', '2026-12-31 23:59:59'
                    )
                    """))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection, """
                    INSERT INTO community_group (
                      id, group_no, name, owner_user_id, visibility, join_policy, status,
                      idempotency_key, request_hash
                    ) VALUES (
                      970004, 'GROUP-970004', '重复请求组', 970100,
                      'PLATFORM_VISIBLE', 'DIRECT', 'ACTIVE',
                      'group-create-970001', 'another-request-hash'
                    )
                    """))
                    .isInstanceOf(SQLException.class);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void v12UniqueIndexRejectsConcurrentCredentialHashReuse() throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                insertLifecycle(connection, 930001L, "v12-unique-probe");
                assertThatThrownBy(() -> insertLifecycle(connection, 930002L, "v12-unique-probe"))
                    .isInstanceOf(SQLException.class);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void v9ToV11ColumnsExistWithExpectedContracts() throws SQLException {
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND ((table_name = 'venue_review' AND column_name = 'content_raw')
                OR (table_name = 'customer_complaint' AND column_name = 'content_raw')
                OR (table_name = 'customer_service_case_event' AND column_name = 'note_raw'))
            """))
            .isEqualTo(3);
        assertThat(queryStrings("""
            SELECT CONCAT(table_name, ':', character_maximum_length)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND column_name = 'reason_text'
              AND table_name IN (
                'reservation_lifecycle',
                'reservation_lifecycle_event',
                'reservation_reschedule_request'
              )
            ORDER BY table_name
            """))
            .containsExactly(
                "reservation_lifecycle:500",
                "reservation_lifecycle_event:500",
                "reservation_reschedule_request:500"
            );
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'reservation'
              AND column_name = 'budget_released'
              AND column_default = '0'
            """))
            .isEqualTo(1);
    }

    @Test
    void v5AndV6CreateTheirRequiredTables() throws SQLException {
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name IN ('password_reset_token', 'site_shift_assignment_guard')
            """))
            .isEqualTo(2);
    }

    @Test
    void v7CreatesAgentOperationsSummaryIndexes() throws SQLException {
        assertThat(queryStrings("""
            SELECT index_name
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND index_name IN (
                'idx_reservation_venue_create',
                'idx_reservation_create',
                'idx_payment_status_time_order',
                'idx_refund_status_time_order'
              )
            GROUP BY index_name
            ORDER BY index_name
            """))
            .containsExactly(
                "idx_payment_status_time_order",
                "idx_refund_status_time_order",
                "idx_reservation_create",
                "idx_reservation_venue_create"
            );
    }

    @Test
    void v8CreatesRemainingAgentAnalyticsIndexes() throws SQLException {
        assertThat(queryStrings("""
            SELECT index_name
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND index_name IN (
                'idx_funnel_metric_scope_time',
                'idx_funnel_dropoff_scope_time',
                'idx_payment_failure_status_time_order',
                'idx_business_event_type_result_time_entity',
                'idx_coupon_template_scope_valid_time',
                'idx_user_coupon_used_attribution'
              )
            GROUP BY index_name
            ORDER BY index_name
            """))
            .containsExactly(
                "idx_business_event_type_result_time_entity",
                "idx_coupon_template_scope_valid_time",
                "idx_funnel_dropoff_scope_time",
                "idx_funnel_metric_scope_time",
                "idx_payment_failure_status_time_order",
                "idx_user_coupon_used_attribution"
            );
    }

    @Test
    void v3InitializesUserCredentialAndCompareAndSetVersions() throws SQLException {
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'sys_user'
              AND column_name IN ('credential_version', 'user_version')
            """))
            .isEqualTo(2);
        assertThat(queryStrings("""
            SELECT CONCAT(credential_version, ':', user_version)
            FROM sys_user
            WHERE username = 'admin'
            """))
            .containsExactly("0:0");
    }

    @Test
    void bootstrapAdminHasNoLoginRuntimeFact() throws SQLException {
        assertThat(queryInt("SELECT COUNT(*) FROM sys_user WHERE username = 'admin' AND deleted = 0"))
            .isEqualTo(1);
        assertThat(queryInt("""
            SELECT COUNT(*)
            FROM sys_user
            WHERE username = 'admin'
              AND deleted = 0
              AND last_login_time IS NULL
            """))
            .isEqualTo(1);
    }

    @Test
    void bootstrapCreatesExactlyThreeMemberLevels() throws SQLException {
        assertThat(queryStrings("SELECT level_code FROM member_level ORDER BY sort_order"))
            .containsExactly("BASIC", "SILVER", "GOLD");
    }

    @Test
    void bootstrapRegistersFiveProvidersWithExternalSandboxesDisabled() throws SQLException {
        assertThat(queryInt("SELECT COUNT(*) FROM provider_registry_entry WHERE deleted = 0"))
            .isEqualTo(5);
        assertThat(queryStrings("""
            SELECT provider_key
            FROM provider_registry_entry
            WHERE provider_env = 'SANDBOX'
              AND enabled = 0
              AND status = 'DISABLED'
              AND deleted = 0
            ORDER BY provider_key
            """))
            .containsExactly("notice.smtp.sandbox", "payment.alipay.sandbox");
    }

    @Test
    void coreBusinessTablesStartEmpty() throws SQLException {
        for (String table : CORE_BUSINESS_TABLES) {
            assertThat(queryInt("SELECT COUNT(*) FROM " + table))
                .as("%s must have no bootstrap runtime rows", table)
                .isZero();
        }
    }

    private static int queryInt(String sql) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static long queryLong(String sql) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private static String successfulMigrationVersionsSql() {
        return """
            SELECT version
            FROM flyway_schema_history
            WHERE success = 1 AND version IS NOT NULL
            ORDER BY installed_rank
            """;
    }

    private static void seedV12AndV13UpgradeData() throws SQLException {
        try (Connection connection = connection()) {
            insertLifecycle(connection, 910001L, DUPLICATE_CREDENTIAL_HASH);
            insertLifecycle(connection, 910002L, DUPLICATE_CREDENTIAL_HASH);
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO site_shift_assignment_guard (assigned_user_id, role_code)
                VALUES (920001, 'MANAGER'), (920001, 'STAFF'), (920002, 'STAFF')
                """)) {
                statement.executeUpdate();
            }
        }
    }

    private static void seedV14UpgradeData() throws SQLException {
        try (Connection connection = connection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO venue (id, name, status, deleted)
                VALUES (940001, 'V14 legacy venue', 'ACTIVE', 0)
                """)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO venue_space (id, venue_id, name, type, capacity, price_per_hour, status, deleted)
                VALUES (940001, 940001, 'V14 legacy space', 'BADMINTON', 8, 20.00, 'ACTIVE', 0)
                """)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reservation (
                  id, user_id, venue_id, space_id, start_time, end_time, status, amount, deleted
                ) VALUES (
                  940001, 1, 940001, 940001, '2026-08-10 10:00:00', '2026-08-10 11:00:00',
                  'PENDING_REVIEW', 20.00, 0
                )
                """)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reservation_occupancy (
                  reservation_id, space_id, slot_date, start_time, end_time, active_flag
                ) VALUES (940001, 940001, '2026-08-10', '10:00:00', '11:00:00', 1)
                """)) {
                statement.executeUpdate();
            }
        }
    }

    private static void remapSystemAdminAndAdvancePermissionSequences() throws SQLException {
        try (Connection connection = connection()) {
            executeUpdate(connection, "UPDATE sys_user SET role_id = 950003 WHERE role_id = 3");
            executeUpdate(connection, "UPDATE sys_user_role SET role_id = 950003 WHERE role_id = 3");
            executeUpdate(connection, "UPDATE sys_role_menu SET role_id = 950003 WHERE role_id = 3");
            executeUpdate(connection, "UPDATE sys_role SET id = 950003 WHERE role_key = 'SYSTEM_ADMIN'");
            executeUpdate(connection, "ALTER TABLE sys_menu AUTO_INCREMENT = 951000");
            executeUpdate(connection, "ALTER TABLE sys_role_menu AUTO_INCREMENT = 952000");
        }
    }

    private static void executeUpdate(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static void insertLifecycle(Connection connection, long reservationId, String credentialHash)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO reservation_lifecycle (
              reservation_id, user_id, venue_id, space_id,
              checkin_code_hash, checkin_code_last4, credential_status,
              attendance_status, checkin_status, no_show_status, deleted
            ) VALUES (?, 1, 1, 1, ?, '1234', 'ISSUED', 'PENDING', 'NOT_CHECKED_IN', 'NOT_EVALUATED', 0)
            """)) {
            statement.setLong(1, reservationId);
            statement.setString(2, credentialHash);
            statement.executeUpdate();
        }
    }

    private static void cleanupUpgradeFixtures() throws SQLException {
        try (Connection connection = connection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM member_account_log WHERE account_id IN (980001, 980002, 980003)")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM member_account WHERE id IN (980001, 980002, 980003)")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM reservation_occupancy_seat WHERE reservation_id = 940001")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM reservation_occupancy WHERE reservation_id = 940001")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM reservation WHERE id = 940001")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM venue_resource_seat WHERE space_id = 940001")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM venue_resource_rule WHERE space_id = 940001")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM venue_space WHERE id = 940001")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM venue WHERE id = 940001")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM reservation_lifecycle_event WHERE reservation_id IN (910001, 910002)")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM reservation_lifecycle WHERE reservation_id IN (910001, 910002)")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM site_shift_assignment_guard WHERE assigned_user_id IN (920001, 920002)")) {
                statement.executeUpdate();
            }
        }
    }

    private static void seedV17BridgeData() throws SQLException {
        try (Connection connection = connection()) {
            executeUpdate(connection, """
                INSERT INTO member_account (
                  id, user_id, level_id, level_code, level_name, growth_value, points_balance,
                  total_consumption_amount, total_reservations, total_member_discount_amount,
                  status, deleted
                ) VALUES
                  (980001, 980101, 1, 'BASIC', '普通会员', 30, 30, 30.00, 1, 0.00, 'ACTIVE', 0),
                  (980002, 980102, 1, 'BASIC', '普通会员', 75, 75, 75.00, 1, 0.00, 'ACTIVE', 0),
                  (980003, 980103, 1, 'BASIC', '普通会员', 0, -10, 0.00, 0, 0.00, 'ACTIVE', 0)
                """);
            executeUpdate(connection, """
                INSERT INTO member_account_log (
                  account_id, user_id, event_type, before_level_code, after_level_code,
                  growth_delta, points_delta, consumption_delta, source_type, reason_code, reason_text
                ) VALUES
                  (980001, 980101, 'MEMBER_POINTS_CHANGED', 'BASIC', 'BASIC',
                   30, 30, 30.00, 'PAYMENT', 'PAID_ORDER', 'V17 一致账户测试'),
                  (980002, 980102, 'MEMBER_POINTS_CHANGED', 'BASIC', 'BASIC',
                   20, 20, 20.00, 'PAYMENT', 'PAID_ORDER', 'V17 正差额账户测试')
                """);
        }
    }

    private static List<String> queryStrings(String sql) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            var values = new java.util.ArrayList<String>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
