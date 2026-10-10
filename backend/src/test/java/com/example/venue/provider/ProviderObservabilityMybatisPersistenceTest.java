package com.example.venue.provider;

import org.springframework.test.annotation.DirtiesContext;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.provider.dto.ProviderRegistryRegistration;
import com.example.venue.provider.entity.ProviderRegistryEntry;
import com.example.venue.provider.mapper.ProviderCallLogMapper;
import com.example.venue.provider.mapper.ProviderRegistryEntryMapper;
import com.example.venue.provider.service.ProviderObservabilityService;
import com.example.venue.provider.service.ProviderRegistryStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(
    classes = ProviderObservabilityMybatisPersistenceTest.PersistenceTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.task.scheduling.enabled=false"
    }
)
class ProviderObservabilityMybatisPersistenceTest {
    private static final AtomicReference<LookupControl> LOOKUP_CONTROL = new AtomicReference<>();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("provider_observability")
        .withUsername("provider")
        .withPassword("provider")
        .withCommand("--character-set-server=utf8mb4",
                     "--collation-server=utf8mb4_unicode_ci",
                     "--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private ProviderObservabilityService observabilityService;

    @Autowired
    private ProviderRegistryEntryMapper registryMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String fixtureProviderKey;

    @AfterEach
    void deleteOnlyTheFixtureRegistryRow() {
        LOOKUP_CONTROL.set(null);
        if (fixtureProviderKey != null) {
            jdbcTemplate.update("DELETE FROM provider_registry_entry WHERE provider_key = ?", fixtureProviderKey);
        }
    }

    @Test
    void upsertRegistryRestoresLogicallyDeletedKeyThroughRealSpringMybatisMapper() {
        fixtureProviderKey = "provider.mybatis.fixture." + UUID.randomUUID();
        LocalDateTime lastSuccessAt = LocalDateTime.of(2026, 7, 13, 9, 0);
        LocalDateTime lastFailureAt = LocalDateTime.of(2026, 7, 13, 10, 0);
        ProviderRegistryEntry deleted = deletedRegistry(lastSuccessAt, lastFailureAt);
        registryMapper.insert(deleted);
        registryMapper.deleteById(deleted.id);

        assertThat(registryMapper.selectOne(new QueryWrapper<ProviderRegistryEntry>()
            .eq("provider_key", fixtureProviderKey).last("limit 1"))).isNull();

        observabilityService.upsertRegistry(registration(true));

        ProviderRegistryEntry restored = registryMapper.selectOne(new QueryWrapper<ProviderRegistryEntry>()
            .eq("provider_key", fixtureProviderKey).last("limit 1"));
        assertThat(restored).isNotNull();
        assertThat(restored.providerDomain).isEqualTo("PAYMENT");
        assertThat(restored.providerEnv).isEqualTo("SANDBOX");
        assertThat(restored.providerName).isEqualTo("ALIPAY");
        assertThat(restored.displayName).isEqualTo("支付宝沙箱修复");
        assertThat(restored.configVersion).isEqualTo("ALIPAY_SANDBOX_V2");
        assertThat(restored.enabled).isOne();
        assertThat(restored.status).isEqualTo("ACTIVE");
        assertThat(restored.deleted).isZero();
        assertThat(restored.lastSuccessAt).isEqualTo(lastSuccessAt);
        assertThat(restored.lastFailureAt).isEqualTo(lastFailureAt);
        assertThat(restored.consecutiveFailures).isEqualTo(4);
        assertThat(restored.lastFailureCode).isEqualTo("OLD_FAILURE");
        assertThat(restored.lastFailureMessage).isEqualTo("old message");
        assertThat(restored.metadataJson).contains("repaired");
    }

    @Test
    void upsertRegistryUpdatesActiveDescriptorWithoutOverwritingCallDerivedFields() {
        fixtureProviderKey = "provider.mybatis.fixture." + UUID.randomUUID();
        LocalDateTime lastSuccessAt = LocalDateTime.of(2026, 7, 13, 9, 0);
        LocalDateTime lastFailureAt = LocalDateTime.of(2026, 7, 13, 10, 0);
        ProviderRegistryEntry active = deletedRegistry(lastSuccessAt, lastFailureAt);
        active.enabled = 1;
        active.deleted = 0;
        registryMapper.insert(active);

        observabilityService.upsertRegistry(registration(true));

        ProviderRegistryEntry updated = registryMapper.selectOne(new QueryWrapper<ProviderRegistryEntry>()
            .eq("provider_key", fixtureProviderKey).last("limit 1"));
        assertThat(updated).isNotNull();
        assertThat(updated.displayName).isEqualTo("支付宝沙箱修复");
        assertThat(updated.configVersion).isEqualTo("ALIPAY_SANDBOX_V2");
        assertThat(updated.enabled).isOne();
        assertThat(updated.status).isEqualTo("FAILED");
        assertThat(updated.lastSuccessAt).isEqualTo(lastSuccessAt);
        assertThat(updated.lastFailureAt).isEqualTo(lastFailureAt);
        assertThat(updated.consecutiveFailures).isEqualTo(4);
        assertThat(updated.lastFailureCode).isEqualTo("OLD_FAILURE");
        assertThat(updated.lastFailureMessage).isEqualTo("old message");
    }

    @Test
    void upsertRegistryRestoresDeletedDisabledDescriptorAsDisabled() {
        fixtureProviderKey = "provider.mybatis.fixture." + UUID.randomUUID();
        LocalDateTime lastSuccessAt = LocalDateTime.of(2026, 7, 13, 9, 0);
        LocalDateTime lastFailureAt = LocalDateTime.of(2026, 7, 13, 10, 0);
        ProviderRegistryEntry deleted = deletedRegistry(lastSuccessAt, lastFailureAt);
        registryMapper.insert(deleted);
        registryMapper.deleteById(deleted.id);

        observabilityService.upsertRegistry(registration(false));

        ProviderRegistryEntry restored = registryMapper.selectOne(new QueryWrapper<ProviderRegistryEntry>()
            .eq("provider_key", fixtureProviderKey).last("limit 1"));
        assertThat(restored).isNotNull();
        assertThat(restored.enabled).isZero();
        assertThat(restored.status).isEqualTo("DISABLED");
        assertThat(restored.deleted).isZero();
        assertThat(restored.lastSuccessAt).isEqualTo(lastSuccessAt);
        assertThat(restored.lastFailureAt).isEqualTo(lastFailureAt);
        assertThat(restored.consecutiveFailures).isEqualTo(4);
        assertThat(restored.lastFailureCode).isEqualTo("OLD_FAILURE");
        assertThat(restored.lastFailureMessage).isEqualTo("old message");
    }

    @Test
    void concurrentUpsertsOfAbsentDescriptorBothSucceedAfterBothRealPresenceChecksReturn() throws Exception {
        fixtureProviderKey = "provider.mybatis.fixture." + UUID.randomUUID();
        LookupControl control = LookupControl.forAbsentKey();
        LOOKUP_CONTROL.set(control);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> upsertAfterStart(start));
            Future<?> second = executor.submit(() -> upsertAfterStart(start));
            start.countDown();

            assertThat(control.absentSelectsReturned.await(5, TimeUnit.SECONDS)).isTrue();
            control.releaseAbsentCallers.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(5, TimeUnit.SECONDS)).isNull();
        } finally {
            control.releaseAll();
            LOOKUP_CONTROL.set(null);
            executor.shutdownNow();
        }

        ProviderRegistryEntry inserted = registryMapper.selectOne(new QueryWrapper<ProviderRegistryEntry>()
            .eq("provider_key", fixtureProviderKey).last("limit 1"));
        Long rowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM provider_registry_entry WHERE provider_key = ?", Long.class, fixtureProviderKey);
        assertThat(rowCount).isEqualTo(1L);
        assertThat(inserted).isNotNull();
        assertThat(inserted.providerDomain).isEqualTo("PAYMENT");
        assertThat(inserted.providerEnv).isEqualTo("SANDBOX");
        assertThat(inserted.providerName).isEqualTo("ALIPAY");
        assertThat(inserted.displayName).isEqualTo("支付宝沙箱修复");
        assertThat(inserted.configVersion).isEqualTo("ALIPAY_SANDBOX_V2");
        assertThat(inserted.enabled).isOne();
        assertThat(inserted.status).isEqualTo("ACTIVE");
        assertThat(inserted.deleted).isZero();
        assertThat(inserted.consecutiveFailures).isZero();
        assertThat(inserted.lastSuccessAt).isNull();
        assertThat(inserted.lastFailureAt).isNull();
        assertThat(inserted.lastFailureCode).isNull();
        assertThat(inserted.lastFailureMessage).isNull();
    }

    @Test
    void concurrentUpsertsOfDeletedDescriptorHoldTheFirstRealLockedReadUntilTheSecondBlocks() throws Exception {
        fixtureProviderKey = "provider.mybatis.fixture." + UUID.randomUUID();
        LocalDateTime lastSuccessAt = LocalDateTime.of(2026, 7, 13, 9, 0);
        LocalDateTime lastFailureAt = LocalDateTime.of(2026, 7, 13, 10, 0);
        ProviderRegistryEntry deleted = deletedRegistry(lastSuccessAt, lastFailureAt);
        registryMapper.insert(deleted);
        registryMapper.deleteById(deleted.id);

        LookupControl control = LookupControl.forDeletedKey();
        LOOKUP_CONTROL.set(control);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(this::upsert);
            assertThat(control.firstDeletedSelectReturned.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(this::upsert);
            assertThat(control.secondDeletedSelectInvocationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitFixtureScopedLockedSelect(control.secondDeletedConnectionId())).isTrue();

            control.releaseFirstDeletedCaller.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(5, TimeUnit.SECONDS)).isNull();
        } finally {
            control.releaseAll();
            LOOKUP_CONTROL.set(null);
            executor.shutdownNow();
        }

        ProviderRegistryEntry restored = registryMapper.selectOne(new QueryWrapper<ProviderRegistryEntry>()
            .eq("provider_key", fixtureProviderKey).last("limit 1"));
        assertThat(restored).isNotNull();
        assertThat(restored.enabled).isOne();
        assertThat(restored.status).isEqualTo("ACTIVE");
        assertThat(restored.deleted).isZero();
        assertThat(restored.lastSuccessAt).isEqualTo(lastSuccessAt);
        assertThat(restored.lastFailureAt).isEqualTo(lastFailureAt);
        assertThat(restored.consecutiveFailures).isEqualTo(4);
        assertThat(restored.lastFailureCode).isEqualTo("OLD_FAILURE");
        assertThat(restored.lastFailureMessage).isEqualTo("old message");
    }

    private void upsertAfterStart(CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent provider upsert did not start");
            }
            observabilityService.upsertRegistry(registration(true));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent provider upsert was interrupted", ex);
        }
    }

    private void upsert() {
        observabilityService.upsertRegistry(registration(true));
    }

    private boolean awaitFixtureScopedLockedSelect(long connectionId) throws InterruptedException {
        // Release only after MySQL observes the second transaction's exact locked query.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long matchingStatements = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.processlist
                WHERE USER = SUBSTRING_INDEX(USER(), '@', 1)
                  AND ID = ?
                  AND COMMAND = 'Query'
                  AND INFO LIKE '%provider_registry_entry%'
                  AND INFO LIKE '%FOR UPDATE%'
                """, Long.class, connectionId);
            if (matchingStatements != null && matchingStatements > 0) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private ProviderRegistryRegistration registration(boolean enabled) {
        return new ProviderRegistryRegistration(
            fixtureProviderKey, "PAYMENT", "SANDBOX", "ALIPAY", "支付宝沙箱修复", "ALIPAY_SANDBOX_V2", enabled,
            "{\"state\":\"repaired\"}");
    }

    private ProviderRegistryEntry deletedRegistry(LocalDateTime lastSuccessAt, LocalDateTime lastFailureAt) {
        ProviderRegistryEntry entry = new ProviderRegistryEntry();
        entry.providerKey = fixtureProviderKey;
        entry.providerDomain = "PAYMENT";
        entry.providerEnv = "SANDBOX";
        entry.providerName = "ALIPAY";
        entry.displayName = "旧支付宝沙箱";
        entry.configVersion = "ALIPAY_SANDBOX_V1";
        entry.enabled = 0;
        entry.status = "FAILED";
        entry.freshnessSeconds = 300;
        entry.failureThreshold = 3;
        entry.timeoutMs = 2000;
        entry.lastSuccessAt = lastSuccessAt;
        entry.lastFailureAt = lastFailureAt;
        entry.consecutiveFailures = 4;
        entry.lastFailureCode = "OLD_FAILURE";
        entry.lastFailureMessage = "old message";
        entry.metadataJson = "{\"state\":\"old\"}";
        entry.createTime = lastSuccessAt;
        entry.updateTime = lastFailureAt;
        entry.deleted = 0;
        return entry;
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = ProviderRegistryEntryMapper.class)
    static class PersistenceTestApplication {
        @Bean
        @Primary
        ProviderRegistryEntryMapper synchronizedRegistryMapper(
            @Qualifier("providerRegistryEntryMapper") ProviderRegistryEntryMapper delegate,
            DataSource dataSource) {
            return (ProviderRegistryEntryMapper) Proxy.newProxyInstance(
                ProviderRegistryEntryMapper.class.getClassLoader(),
                new Class<?>[]{ProviderRegistryEntryMapper.class},
                (proxy, method, args) -> {
                    try {
                        LookupControl control = LOOKUP_CONTROL.get();
                        int selectNumber = 0;
                        if (control != null && control.controls(method.getName())) {
                            selectNumber = control.beforeActualSelect(currentConnectionId(dataSource));
                        }
                        Object result = method.invoke(delegate, args);
                        if (control != null && control.controls(method.getName())) {
                            control.afterActualSelect(selectNumber, (ProviderRegistryEntry) result);
                        }
                        return result;
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
        }

        private static long currentConnectionId(DataSource dataSource) {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                if (!result.next()) {
                    throw new IllegalStateException("Could not read the transaction connection id");
                }
                return result.getLong(1);
            } catch (SQLException ex) {
                throw new IllegalStateException("Could not read the transaction connection id", ex);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }

        @Bean
        ProviderRegistryStateService providerRegistryStateService(ProviderRegistryEntryMapper registryMapper) {
            return new ProviderRegistryStateService(registryMapper);
        }

        @Bean
        ProviderObservabilityService providerObservabilityService(ProviderRegistryEntryMapper registryMapper,
                                                                  ProviderCallLogMapper callLogMapper,
                                                                  ProviderRegistryStateService registryStateService) {
            return new ProviderObservabilityService(registryMapper, callLogMapper, registryStateService);
        }
    }

    private static final class LookupControl {
        private enum Mode {
            ABSENT,
            DELETED
        }

        private final Mode mode;
        private final AtomicInteger selectCount = new AtomicInteger();
        private final CountDownLatch absentSelectsReturned = new CountDownLatch(2);
        private final CountDownLatch releaseAbsentCallers = new CountDownLatch(1);
        private final CountDownLatch firstDeletedSelectReturned = new CountDownLatch(1);
        private final CountDownLatch releaseFirstDeletedCaller = new CountDownLatch(1);
        private final CountDownLatch secondDeletedSelectInvocationStarted = new CountDownLatch(1);
        private final AtomicLong secondDeletedConnectionId = new AtomicLong(-1);

        private LookupControl(Mode mode) {
            this.mode = mode;
        }

        static LookupControl forAbsentKey() {
            return new LookupControl(Mode.ABSENT);
        }

        static LookupControl forDeletedKey() {
            return new LookupControl(Mode.DELETED);
        }

        int beforeActualSelect(long connectionId) {
            int selectNumber = selectCount.incrementAndGet();
            if (mode == Mode.DELETED && selectNumber == 2) {
                secondDeletedConnectionId.set(connectionId);
                secondDeletedSelectInvocationStarted.countDown();
            }
            return selectNumber;
        }

        long secondDeletedConnectionId() {
            long connectionId = secondDeletedConnectionId.get();
            if (connectionId < 0) {
                throw new IllegalStateException("Second deleted-key connection id was not captured");
            }
            return connectionId;
        }

        boolean controls(String methodName) {
            return mode == Mode.ABSENT
                ? "selectByProviderKeyIncludingDeleted".equals(methodName)
                : "selectByProviderKeyIncludingDeletedForUpdate".equals(methodName);
        }

        void afterActualSelect(int selectNumber, ProviderRegistryEntry result) {
            if (mode == Mode.ABSENT) {
                if (result != null) {
                    throw new IllegalStateException("Absent-key fixture unexpectedly existed");
                }
                absentSelectsReturned.countDown();
                await(releaseAbsentCallers, "Concurrent absent-key callers were not released");
                return;
            }
            if (selectNumber == 1) {
                if (result == null || !Integer.valueOf(1).equals(result.deleted)) {
                    throw new IllegalStateException("Deleted-key fixture was not returned by the locked mapper query");
                }
                firstDeletedSelectReturned.countDown();
                await(releaseFirstDeletedCaller, "First deleted-key caller was not released");
            }
        }

        void releaseAll() {
            releaseAbsentCallers.countDown();
            releaseFirstDeletedCaller.countDown();
        }

        private void await(CountDownLatch latch, String message) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(message);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(message, ex);
            }
        }
    }
}
