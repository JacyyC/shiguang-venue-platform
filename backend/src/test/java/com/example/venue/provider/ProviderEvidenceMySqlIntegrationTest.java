package com.example.venue.provider;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.provider.config.ProviderObservabilityProperties;
import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.entity.ProviderRegistryEntry;
import com.example.venue.provider.entity.WeatherObservationSnapshot;
import com.example.venue.provider.mapper.OpsMetricSnapshotMapper;
import com.example.venue.provider.mapper.ProviderCallLogMapper;
import com.example.venue.provider.mapper.ProviderRegistryEntryMapper;
import com.example.venue.provider.mapper.RiskAssessmentSnapshotMapper;
import com.example.venue.provider.mapper.WeatherObservationSnapshotMapper;
import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import com.example.venue.provider.service.ProviderEvidenceRetentionService;
import com.example.venue.provider.service.ProviderEvidenceWriter;
import com.example.venue.provider.service.ProviderObservabilityService;
import com.example.venue.provider.service.ProviderRegistryStateService;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(
    classes = ProviderEvidenceMySqlIntegrationTest.IntegrationApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.task.scheduling.enabled=false"
)
class ProviderEvidenceMySqlIntegrationTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 31, 12, 0);
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    private static final int RETENTION_BATCH_SIZE = 2;
    private static final int RETENTION_MAX_BATCHES = 1;
    private static final String REGISTRY_ROW_LOCK_STATEMENT =
        ProviderRegistryEntryMapper.class.getName() + ".selectByProviderKeyForUpdate";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("provider_evidence")
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
        registry.add("app.provider.observability.scheduled-enabled", () -> "false");
        registry.add("app.provider.observability.weather.enabled", () -> "false");
        registry.add("app.provider.observability.risk.enabled", () -> "false");
        registry.add("app.provider.observability.metrics.enabled", () -> "false");
        registry.add("app.provider.observability.retention.enabled", () -> "false");
    }

    @Autowired
    private ProviderEvidenceWriter evidenceWriter;

    @Autowired
    private ProviderEvidenceRetentionService retentionService;

    @Autowired
    private ProviderObservabilityProperties properties;

    @Autowired
    private FailingAfterSnapshotObservabilityService observabilityService;

    @Autowired
    private ProviderRegistryEntryMapper registryMapper;

    @Autowired
    private ProviderCallLogMapper callLogMapper;

    @Autowired
    private WeatherObservationSnapshotMapper weatherSnapshotMapper;

    @Autowired
    private RiskAssessmentSnapshotMapper riskSnapshotMapper;

    @Autowired
    private OpsMetricSnapshotMapper metricSnapshotMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RegistryRowLockAttemptProbe registryRowLockAttemptProbe;

    private String fixturePrefix;

    @BeforeEach
    void prepareFixture() {
        fixturePrefix = "p5-retention-fixture." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        properties.getRetention().setCallLogDays(30);
        properties.getRetention().setSnapshotDays(30);
        properties.getRetention().setBatchSize(RETENTION_BATCH_SIZE);
        properties.getRetention().setMaxBatchesPerRun(RETENTION_MAX_BATCHES);
        observabilityService.clearInjectedFailure();
        registryRowLockAttemptProbe.clear();
    }

    @AfterEach
    void removeOnlyThisTestsFixtureRows() {
        if (fixturePrefix == null) {
            return;
        }
        String keyPattern = fixturePrefix + "%";
        jdbcTemplate.update("DELETE FROM provider_call_log WHERE provider_key LIKE ?", keyPattern);
        jdbcTemplate.update("DELETE FROM weather_observation_snapshot WHERE provider_key LIKE ?", keyPattern);
        jdbcTemplate.update("DELETE FROM risk_assessment_snapshot WHERE provider_key LIKE ?", keyPattern);
        jdbcTemplate.update("DELETE FROM ops_metric_snapshot WHERE provider_key LIKE ?", keyPattern);
        jdbcTemplate.update("DELETE FROM provider_registry_entry WHERE provider_key LIKE ?", keyPattern);
    }

    @Test
    void concurrentFailedOutcomesSerializeOnTheRegistryRow() throws Exception {
        String providerKey = fixtureKey("registry-lock");
        insertRegistry(providerKey);
        CountDownLatch firstRowLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
        CountDownLatch secondRowLockQueryEntered = new CountDownLatch(1);
        CountDownLatch secondRowLockQueryReturned = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Void> first = executor.submit(lockedFailedOutcomeTask(
                providerKey, "one", firstRowLockAcquired, releaseFirstTransaction));
            assertThat(firstRowLockAcquired.await(10, TimeUnit.SECONDS)).isTrue();

            registryRowLockAttemptProbe.arm(secondRowLockQueryEntered, secondRowLockQueryReturned);
            Future<Void> second = executor.submit(failedOutcomeTask(providerKey, "two"));

            assertThat(secondRowLockQueryEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondRowLockQueryReturned.getCount())
                .as("second registry FOR UPDATE must remain blocked while the first transaction holds the row")
                .isEqualTo(1);
            assertThat(second.isDone()).isFalse();

            releaseFirstTransaction.countDown();
            first.get(30, TimeUnit.SECONDS);
            assertThat(secondRowLockQueryReturned.await(10, TimeUnit.SECONDS)).isTrue();
            second.get(30, TimeUnit.SECONDS);
        } finally {
            releaseFirstTransaction.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        ProviderRegistryEntry row = selectRegistry(providerKey);
        assertThat(row.consecutiveFailures).isEqualTo(2);
        assertThat(row.status).isEqualTo("DEGRADED");
        assertThat(countRows("provider_call_log", providerKey)).isEqualTo(2);
    }

    @Test
    void manualSuccessIsAtomicAndInjectedPostSnapshotFailureLeavesNoHalfSuccessEvidence() {
        String successfulKey = fixtureKey("manual-success");
        insertRegistry(successfulKey);

        evidenceWriter.writeWeatherSuccess(
            weatherSnapshot(successfulKey, "manual-success"), successRecord(
                successfulKey, ProviderRunContext.manual(1L), "FETCH_WEATHER", true));

        ProviderRegistryEntry successfulRegistry = selectRegistry(successfulKey);
        assertThat(countRows("weather_observation_snapshot", successfulKey)).isEqualTo(1);
        assertThat(countRows("provider_call_log", successfulKey)).isEqualTo(1);
        assertThat(successfulRegistry.status).isEqualTo("ACTIVE");
        assertThat(successfulRegistry.lastSuccessAt).isNotNull();
        assertThat(successfulRegistry.consecutiveFailures).isZero();

        String failingKey = fixtureKey("manual-rollback");
        insertRegistry(failingKey);
        observabilityService.failNextRecordCallAfterSnapshotInsert();

        assertThatThrownBy(() -> evidenceWriter.writeWeatherSuccess(
            weatherSnapshot(failingKey, "manual-rollback"), successRecord(
                failingKey, ProviderRunContext.manual(1L), "FETCH_WEATHER", true)))
            .isInstanceOf(InjectedPostSnapshotFailure.class)
            .hasMessage("injected after weather snapshot insert");

        ProviderRegistryEntry failedRegistry = selectRegistry(failingKey);
        assertThat(observabilityService.wasInjectedAfterSnapshot()).isTrue();
        assertThat(countRows("weather_observation_snapshot", failingKey)).isZero();
        assertThat(countRows("provider_call_log", failingKey)).isZero();
        assertThat(failedRegistry.lastSuccessAt).isNull();
        assertThat(failedRegistry.lastFailureAt).isNull();
        assertThat(failedRegistry.consecutiveFailures).isZero();
    }

    @Test
    void scheduledWeatherDetailsCommitIndependentlyBeforeTheirAggregateRegistryOutcome() {
        String providerKey = fixtureKey("scheduled-weather");
        insertRegistry(providerKey);

        evidenceWriter.writeWeatherDetail(
            weatherSnapshot(providerKey, "detail-one"), successRecord(
                providerKey, ProviderRunContext.scheduled(), "FETCH_WEATHER", false));
        assertThat(countRows("weather_observation_snapshot", providerKey)).isEqualTo(1);
        assertThat(countRows("provider_call_log", providerKey)).isEqualTo(1);
        assertRegistryHasNoOutcome(providerKey);

        evidenceWriter.writeWeatherDetail(
            weatherSnapshot(providerKey, "detail-two"), successRecord(
                providerKey, ProviderRunContext.scheduled(), "FETCH_WEATHER", false));
        assertThat(countRows("weather_observation_snapshot", providerKey)).isEqualTo(2);
        assertThat(countRows("provider_call_log", providerKey)).isEqualTo(2);
        assertRegistryHasNoOutcome(providerKey);

        evidenceWriter.writeRegistryOutcome(successRecord(
            providerKey, ProviderRunContext.scheduled(), "FETCH_WEATHER_BATCH", true));

        ProviderRegistryEntry registry = selectRegistry(providerKey);
        assertThat(countRows("provider_call_log", providerKey)).isEqualTo(3);
        assertThat(registry.lastSuccessAt).isNotNull();
        assertThat(registry.consecutiveFailures).isZero();
    }

    @Test
    void retentionPhysicallyDeletesOnlyTheConfiguredBatchAllowanceAndRetainsEligibleExpiredFixtureRows() {
        LocalDateTime expiredAt = NOW.minusDays(31);
        LocalDateTime snapshotCutoff = NOW.minusDays(properties.getRetention().getSnapshotDays());
        LocalDateTime callCutoff = NOW.minusDays(properties.getRetention().getCallLogDays());

        String weatherKey = fixtureKey("retention-weather");
        List<Long> expiredWeather = List.of(
            insertWeather("weather-expired-one", weatherKey, 701L, expiredAt),
            insertWeather("weather-expired-two", weatherKey, 701L, expiredAt),
            insertWeather("weather-expired-three", weatherKey, 701L, expiredAt));
        long protectedWeather = insertWeather("weather-protected", weatherKey, 701L, expiredAt);

        String riskKey = fixtureKey("retention-risk");
        List<Long> expiredRisk = List.of(
            insertRisk("risk-expired-one", riskKey, "SYSTEM", expiredAt),
            insertRisk("risk-expired-two", riskKey, "SYSTEM", expiredAt),
            insertRisk("risk-expired-three", riskKey, "SYSTEM", expiredAt));
        long protectedRisk = insertRisk("risk-protected", riskKey, "SYSTEM", expiredAt);

        String metricKey = fixtureKey("retention-metric");
        List<Long> expiredMetric = List.of(
            insertMetric("metric-expired-one", metricKey, "db_ping_ms", "UNAVAILABLE", expiredAt),
            insertMetric("metric-expired-two", metricKey, "db_ping_ms", "UNAVAILABLE", expiredAt),
            insertMetric("metric-expired-three", metricKey, "db_ping_ms", "UNAVAILABLE", expiredAt));
        long protectedMetric = insertMetric("metric-protected", metricKey, "db_ping_ms", "OK", expiredAt);

        String callKey = fixtureKey("retention-call");
        List<Long> expiredCalls = List.of(
            insertCall("call-expired-one", callKey, "FAILED", null, null, expiredAt),
            insertCall("call-expired-two", callKey, "FAILED", null, null, expiredAt),
            insertCall("call-expired-three", callKey, "FAILED", null, null, expiredAt));
        long protectedSuccessfulCall = insertCall("call-success-protected", callKey, "SUCCESS", null, null, expiredAt);
        long callForProtectedWeather = insertCall(
            "call-weather-protected", callKey, "FAILED", "WEATHER", protectedWeather, expiredAt);
        long callForProtectedRisk = insertCall(
            "call-risk-protected", callKey, "FAILED", "RISK", protectedRisk, expiredAt);
        long callForProtectedMetric = insertCall(
            "call-metric-protected", callKey, "FAILED", "METRIC", protectedMetric, expiredAt);

        assertCandidatesAreBoundedAndProtected(
            weatherSnapshotMapper.selectRetentionCandidateIds(snapshotCutoff, RETENTION_BATCH_SIZE),
            expiredWeather, List.of(protectedWeather));
        assertCandidatesAreBoundedAndProtected(
            riskSnapshotMapper.selectRetentionCandidateIds(snapshotCutoff, RETENTION_BATCH_SIZE),
            expiredRisk, List.of(protectedRisk));
        assertCandidatesAreBoundedAndProtected(
            metricSnapshotMapper.selectRetentionCandidateIds(snapshotCutoff, RETENTION_BATCH_SIZE),
            expiredMetric, List.of(protectedMetric));
        assertCandidatesAreBoundedAndProtected(
            callLogMapper.selectRetentionCandidateIds(callCutoff, RETENTION_BATCH_SIZE),
            expiredCalls, List.of(
                protectedSuccessfulCall, callForProtectedWeather, callForProtectedRisk, callForProtectedMetric));

        retentionService.runOnce();

        assertBoundedPhysicalDeletion("weather_observation_snapshot", expiredWeather);
        assertBoundedPhysicalDeletion("risk_assessment_snapshot", expiredRisk);
        assertBoundedPhysicalDeletion("ops_metric_snapshot", expiredMetric);
        assertBoundedPhysicalDeletion("provider_call_log", expiredCalls);
        assertRowsExist("weather_observation_snapshot", List.of(protectedWeather));
        assertRowsExist("risk_assessment_snapshot", List.of(protectedRisk));
        assertRowsExist("ops_metric_snapshot", List.of(protectedMetric));
        assertRowsExist("provider_call_log", List.of(
            protectedSuccessfulCall, callForProtectedWeather, callForProtectedRisk, callForProtectedMetric));
    }

    @Test
    void retentionPhysicallyDeletesOnlyExpiredRowsOutsideEachProtectedLatestEvidenceStream() {
        LocalDateTime expiredAt = NOW.minusDays(31);
        LocalDateTime snapshotCutoff = NOW.minusDays(properties.getRetention().getSnapshotDays());
        LocalDateTime callLogCutoff = NOW.minusDays(properties.getRetention().getCallLogDays());

        String weatherProvider = fixtureKey("retention-weather");
        long expiredWeatherId = insertWeather("weather-expired", weatherProvider, 701L, expiredAt);
        long latestWeatherId = insertWeather("weather-latest", weatherProvider, 701L, expiredAt);

        String riskProvider = fixtureKey("retention-risk");
        long expiredRiskId = insertRisk("risk-expired", riskProvider, "SYSTEM", expiredAt);
        long latestRiskId = insertRisk("risk-latest", riskProvider, "SYSTEM", expiredAt);

        String metricProvider = fixtureKey("retention-metric");
        long expiredMetricId = insertMetric(
            "metric-expired", metricProvider, "db_ping_ms", "UNAVAILABLE", expiredAt);
        long latestValidMetricId = insertMetric(
            "metric-latest-valid", metricProvider, "db_ping_ms", "OK", expiredAt);
        long latestUnavailableMetricId = insertMetric(
            "metric-latest-unavailable", metricProvider, "db_ping_ms", "UNAVAILABLE", expiredAt);
        long expiredFallbackMetricId = insertMetric(
            "metric-fallback-expired", metricProvider, "redis_ping_ms", "UNAVAILABLE", expiredAt);
        long latestFallbackMetricId = insertMetric(
            "metric-fallback-latest", metricProvider, "redis_ping_ms", "UNAVAILABLE", expiredAt);

        String callProvider = fixtureKey("retention-call");
        long expiredCallId = insertCall("call-expired", callProvider, "FAILED", null, null, expiredAt);
        long latestSuccessfulCallId = insertCall(
            "call-latest-success", callProvider, "SUCCESS", null, null, expiredAt);
        long callReferencingProtectedWeatherId = insertCall(
            "call-protected-weather", callProvider, "FAILED", "WEATHER", latestWeatherId, expiredAt);
        long callReferencingProtectedRiskId = insertCall(
            "call-protected-risk", callProvider, "FAILED", "RISK", latestRiskId, expiredAt);
        long callReferencingProtectedMetricId = insertCall(
            "call-protected-metric", callProvider, "FAILED", "METRIC", latestValidMetricId, expiredAt);
        String noSuccessCallProvider = fixtureKey("retention-call-no-success");
        long expiredNoSuccessCallId = insertCall(
            "call-no-success-expired", noSuccessCallProvider, "FAILED", null, null, expiredAt);
        long latestNoSuccessCallId = insertCall(
            "call-no-success-latest", noSuccessCallProvider, "TIMEOUT", null, null, expiredAt);

        List<Long> weatherCandidates = weatherSnapshotMapper.selectRetentionCandidateIds(snapshotCutoff, 100);
        List<Long> riskCandidates = riskSnapshotMapper.selectRetentionCandidateIds(snapshotCutoff, 100);
        List<Long> metricCandidates = metricSnapshotMapper.selectRetentionCandidateIds(snapshotCutoff, 100);
        List<Long> callCandidates = callLogMapper.selectRetentionCandidateIds(callLogCutoff, 100);

        assertThat(weatherCandidates).contains(expiredWeatherId).doesNotContain(latestWeatherId);
        assertThat(riskCandidates).contains(expiredRiskId).doesNotContain(latestRiskId);
        assertThat(metricCandidates)
            .contains(expiredMetricId, latestUnavailableMetricId, expiredFallbackMetricId)
            .doesNotContain(latestValidMetricId, latestFallbackMetricId);
        assertThat(callCandidates).contains(expiredCallId, expiredNoSuccessCallId)
            .doesNotContain(latestSuccessfulCallId)
            .doesNotContain(callReferencingProtectedWeatherId, callReferencingProtectedRiskId,
                callReferencingProtectedMetricId, latestNoSuccessCallId);

        callLogMapper.physicalDeleteIds(List.of(expiredCallId, expiredNoSuccessCallId));
        weatherSnapshotMapper.physicalDeleteIds(List.of(expiredWeatherId));
        riskSnapshotMapper.physicalDeleteIds(List.of(expiredRiskId));
        metricSnapshotMapper.physicalDeleteIds(List.of(
            expiredMetricId, latestUnavailableMetricId, expiredFallbackMetricId));

        assertThat(rowExists("weather_observation_snapshot", expiredWeatherId)).isFalse();
        assertThat(rowExists("weather_observation_snapshot", latestWeatherId)).isTrue();
        assertThat(rowExists("risk_assessment_snapshot", expiredRiskId)).isFalse();
        assertThat(rowExists("risk_assessment_snapshot", latestRiskId)).isTrue();
        assertThat(rowExists("ops_metric_snapshot", expiredMetricId)).isFalse();
        assertThat(rowExists("ops_metric_snapshot", latestValidMetricId)).isTrue();
        assertThat(rowExists("ops_metric_snapshot", latestUnavailableMetricId)).isFalse();
        assertThat(rowExists("ops_metric_snapshot", expiredFallbackMetricId)).isFalse();
        assertThat(rowExists("ops_metric_snapshot", latestFallbackMetricId)).isTrue();
        assertThat(rowExists("provider_call_log", expiredCallId)).isFalse();
        assertThat(rowExists("provider_call_log", latestSuccessfulCallId)).isTrue();
        assertThat(rowExists("provider_call_log", callReferencingProtectedWeatherId)).isTrue();
        assertThat(rowExists("provider_call_log", callReferencingProtectedRiskId)).isTrue();
        assertThat(rowExists("provider_call_log", callReferencingProtectedMetricId)).isTrue();
        assertThat(rowExists("provider_call_log", expiredNoSuccessCallId)).isFalse();
        assertThat(rowExists("provider_call_log", latestNoSuccessCallId)).isTrue();
    }

    @Test
    void retentionProtectsLatestVisibleSnapshotsWhilePhysicallyReclaimingNewerSoftDeletedRows() {
        LocalDateTime expiredAt = NOW.minusDays(31);
        LocalDateTime snapshotCutoff = NOW.minusDays(properties.getRetention().getSnapshotDays());
        LocalDateTime callLogCutoff = NOW.minusDays(properties.getRetention().getCallLogDays());

        String weatherProvider = fixtureKey("soft-weather");
        long latestVisibleWeatherId = insertWeather(
            "soft-weather-visible", weatherProvider, 801L, expiredAt, 0);
        long deletedWeatherId = insertWeather("soft-weather-deleted", weatherProvider, 801L, expiredAt, 1);

        String riskProvider = fixtureKey("soft-risk");
        long latestVisibleRiskId = insertRisk("soft-risk-visible", riskProvider, "SYSTEM", expiredAt, 0);
        long deletedRiskId = insertRisk("soft-risk-deleted", riskProvider, "SYSTEM", expiredAt, 1);

        String metricProvider = fixtureKey("soft-metric");
        long latestVisibleMetricId = insertMetric(
            "soft-metric-visible", metricProvider, "jvm_memory_used_mb", "OK", expiredAt, 0);
        long deletedMetricId = insertMetric(
            "soft-metric-deleted", metricProvider, "jvm_memory_used_mb", "OK", expiredAt, 1);

        String callProvider = fixtureKey("soft-call");
        long protectedSuccessCallId = insertCall(
            "soft-call-success", callProvider, "SUCCESS", null, null, expiredAt);
        long callForDeletedWeatherId = insertCall(
            "soft-call-weather", callProvider, "FAILED", "WEATHER", deletedWeatherId, expiredAt);
        long callForDeletedRiskId = insertCall(
            "soft-call-risk", callProvider, "FAILED", "RISK", deletedRiskId, expiredAt);
        long callForDeletedMetricId = insertCall(
            "soft-call-metric", callProvider, "FAILED", "METRIC", deletedMetricId, expiredAt);

        assertThat(weatherSnapshotMapper.selectRetentionCandidateIds(snapshotCutoff, 100))
            .contains(deletedWeatherId)
            .doesNotContain(latestVisibleWeatherId);
        assertThat(riskSnapshotMapper.selectRetentionCandidateIds(snapshotCutoff, 100))
            .contains(deletedRiskId)
            .doesNotContain(latestVisibleRiskId);
        assertThat(metricSnapshotMapper.selectRetentionCandidateIds(snapshotCutoff, 100))
            .contains(deletedMetricId)
            .doesNotContain(latestVisibleMetricId);
        assertThat(callLogMapper.selectRetentionCandidateIds(callLogCutoff, 100))
            .contains(callForDeletedWeatherId, callForDeletedRiskId, callForDeletedMetricId)
            .doesNotContain(protectedSuccessCallId);

        callLogMapper.physicalDeleteIds(List.of(callForDeletedWeatherId, callForDeletedRiskId, callForDeletedMetricId));
        weatherSnapshotMapper.physicalDeleteIds(List.of(deletedWeatherId));
        riskSnapshotMapper.physicalDeleteIds(List.of(deletedRiskId));
        metricSnapshotMapper.physicalDeleteIds(List.of(deletedMetricId));

        assertThat(rowExists("weather_observation_snapshot", latestVisibleWeatherId)).isTrue();
        assertThat(rowExists("weather_observation_snapshot", deletedWeatherId)).isFalse();
        assertThat(rowExists("risk_assessment_snapshot", latestVisibleRiskId)).isTrue();
        assertThat(rowExists("risk_assessment_snapshot", deletedRiskId)).isFalse();
        assertThat(rowExists("ops_metric_snapshot", latestVisibleMetricId)).isTrue();
        assertThat(rowExists("ops_metric_snapshot", deletedMetricId)).isFalse();
        assertThat(rowExists("provider_call_log", protectedSuccessCallId)).isTrue();
        assertThat(rowExists("provider_call_log", callForDeletedWeatherId)).isFalse();
        assertThat(rowExists("provider_call_log", callForDeletedRiskId)).isFalse();
        assertThat(rowExists("provider_call_log", callForDeletedMetricId)).isFalse();
    }

    @Test
    void successfulFlywayHistoryMapsToCurrentSourceChecksums() {
        flyway.validate();
        Map<String, Integer> sourceChecksums = Arrays.stream(flyway.info().all())
            .filter(info -> info.getVersion() != null)
            .collect(Collectors.toMap(MigrationInfo::getScript, MigrationInfo::getChecksum, (left, right) -> left));
        List<MigrationHistoryRow> historyRows = jdbcTemplate.query("""
            SELECT version, script, checksum
            FROM flyway_schema_history
            WHERE success = 1
            ORDER BY installed_rank
            """, (resultSet, rowNum) -> new MigrationHistoryRow(
            resultSet.getString("version"), resultSet.getString("script"), resultSet.getObject("checksum", Integer.class)));

        assertThat(historyRows).isNotEmpty();
        assertThat(historyRows).allSatisfy(history -> {
            assertThat(history.version()).isNotBlank();
            assertThat(sourceChecksums).containsKey(history.script());
            assertThat(history.checksum()).isEqualTo(sourceChecksums.get(history.script()));
        });
    }

    private Callable<Void> lockedFailedOutcomeTask(String providerKey,
                                                   String suffix,
                                                   CountDownLatch rowLockAcquired,
                                                   CountDownLatch releaseTransaction) {
        return () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                ProviderRegistryEntry locked = registryMapper.selectByProviderKeyForUpdate(providerKey);
                if (locked == null) {
                    throw new IllegalStateException("registry lock fixture row was not found");
                }
                rowLockAcquired.countDown();
                awaitLatch(releaseTransaction, "first transaction was not released");
                observabilityService.recordCall(failedRecord(providerKey, suffix));
            });
            return null;
        };
    }

    private Callable<Void> failedOutcomeTask(String providerKey, String suffix) {
        return () -> {
            evidenceWriter.writeRegistryOutcome(failedRecord(providerKey, suffix));
            return null;
        };
    }

    private void insertRegistry(String providerKey) {
        jdbcTemplate.update("""
            INSERT INTO provider_registry_entry
                (provider_key, provider_domain, provider_env, provider_name, display_name, config_version,
                 enabled, status, freshness_seconds, failure_threshold, timeout_ms, consecutive_failures,
                 metadata_json, deleted)
            VALUES (?, 'WEATHER', 'DEV', 'P5_MYSQL_TEST', 'P5 MySQL integration fixture', 'P5_TEST_V1',
                    1, 'ACTIVE', 60, 3, 1000, 0, JSON_OBJECT('fixture', true), 0)
            """, providerKey);
    }

    private ProviderRegistryEntry selectRegistry(String providerKey) {
        ProviderRegistryEntry row = registryMapper.selectByProviderKeyIncludingDeleted(providerKey);
        assertThat(row).isNotNull();
        return row;
    }

    private void assertRegistryHasNoOutcome(String providerKey) {
        ProviderRegistryEntry row = selectRegistry(providerKey);
        assertThat(row.lastSuccessAt).isNull();
        assertThat(row.lastFailureAt).isNull();
        assertThat(row.consecutiveFailures).isZero();
    }

    private ProviderCallRecord failedRecord(String providerKey, String suffix) {
        return ProviderCallRecord.of(
            ProviderRunContext.scheduled(), providerKey, "WEATHER", "DEV", "P5_MYSQL_TEST",
            fixtureIdentifier("failure-" + suffix), "FETCH_WEATHER", "FAILED", "P5_TEST_V1",
            "{\"fixture\":true}", null, "INTEGRATION_FAILURE", "fixture failure", 1L, null,
            "WEATHER", null, null, true);
    }

    private ProviderCallRecord successRecord(String providerKey,
                                             ProviderRunContext source,
                                             String operation,
                                             boolean updateRegistry) {
        return ProviderCallRecord.of(
            source, providerKey, "WEATHER", "DEV", "P5_MYSQL_TEST", fixtureIdentifier("call-" + operation),
            operation, "SUCCESS", "P5_TEST_V1", "{\"fixture\":true}", "{\"ok\":true}", null, null,
            1L, null, "WEATHER", null, BigDecimal.valueOf(0.9), updateRegistry);
    }

    private WeatherObservationSnapshot weatherSnapshot(String providerKey, String suffix) {
        WeatherObservationSnapshot snapshot = new WeatherObservationSnapshot();
        snapshot.snapshotNo = fixtureIdentifier("weather-" + suffix);
        snapshot.venueId = 900001L;
        snapshot.locationId = 900001L;
        snapshot.providerKey = providerKey;
        snapshot.providerEnv = "DEV";
        snapshot.providerName = "P5_MYSQL_TEST";
        snapshot.configVersion = "P5_TEST_V1";
        snapshot.observedAt = NOW;
        snapshot.expiresAt = NOW.plusMinutes(30);
        snapshot.alertLevel = "NONE";
        snapshot.summaryText = "fixture";
        snapshot.confidence = BigDecimal.valueOf(0.9);
        snapshot.rawSummaryJson = "{\"fixture\":true}";
        snapshot.createTime = NOW;
        snapshot.deleted = 0;
        return snapshot;
    }

    private long insertWeather(String suffix, String providerKey, long venueId, LocalDateTime createdAt) {
        return insertWeather(suffix, providerKey, venueId, createdAt, 0);
    }

    private long insertWeather(String suffix,
                               String providerKey,
                               long venueId,
                               LocalDateTime createdAt,
                               int deleted) {
        String snapshotNo = fixtureIdentifier(suffix);
        jdbcTemplate.update("""
            INSERT INTO weather_observation_snapshot
                (snapshot_no, venue_id, location_id, provider_key, provider_env, provider_name, config_version,
                 observed_at, expires_at, alert_level, create_time, deleted)
            VALUES (?, ?, ?, ?, 'DEV', 'P5_MYSQL_TEST', 'P5_TEST_V1', ?, ?, 'NONE', ?, ?)
            """, snapshotNo, venueId, venueId, providerKey, createdAt, createdAt.plusHours(1), createdAt, deleted);
        return idFor("weather_observation_snapshot", "snapshot_no", snapshotNo);
    }

    private long insertRisk(String suffix, String providerKey, String scopeType, LocalDateTime createdAt) {
        return insertRisk(suffix, providerKey, scopeType, createdAt, 0);
    }

    private long insertRisk(String suffix,
                            String providerKey,
                            String scopeType,
                            LocalDateTime createdAt,
                            int deleted) {
        String snapshotNo = fixtureIdentifier(suffix);
        jdbcTemplate.update("""
            INSERT INTO risk_assessment_snapshot
                (snapshot_no, scope_type, scope_id, provider_key, provider_env, provider_name, config_version,
                 risk_score, risk_level, window_start, window_end, failed_login_count, payment_failure_count,
                 notice_failure_count, open_complaint_count, severe_complaint_count, create_time, deleted)
            VALUES (?, ?, NULL, ?, 'DEV', 'P5_MYSQL_TEST', 'P5_TEST_V1', 0, 'LOW', ?, ?, 0, 0, 0, 0, 0, ?, ?)
            """, snapshotNo, scopeType, providerKey, createdAt.minusHours(1), createdAt, createdAt, deleted);
        return idFor("risk_assessment_snapshot", "snapshot_no", snapshotNo);
    }

    private long insertMetric(String suffix,
                              String providerKey,
                              String metricKey,
                              String status,
                              LocalDateTime createdAt) {
        return insertMetric(suffix, providerKey, metricKey, status, createdAt, 0);
    }

    private long insertMetric(String suffix,
                              String providerKey,
                              String metricKey,
                              String status,
                              LocalDateTime createdAt,
                              int deleted) {
        String snapshotNo = fixtureIdentifier(suffix);
        jdbcTemplate.update("""
            INSERT INTO ops_metric_snapshot
                (snapshot_no, provider_key, provider_env, provider_name, config_version, metric_key, metric_value,
                 metric_unit, status, observed_at, expires_at, create_time, deleted)
            VALUES (?, ?, 'DEV', 'P5_MYSQL_TEST', 'P5_TEST_V1', ?, 1, 'ms', ?, ?, ?, ?, ?)
            """, snapshotNo, providerKey, metricKey, status, createdAt, createdAt.plusHours(1), createdAt, deleted);
        return idFor("ops_metric_snapshot", "snapshot_no", snapshotNo);
    }

    private long insertCall(String suffix,
                            String providerKey,
                            String status,
                            String snapshotType,
                            Long snapshotId,
                            LocalDateTime occurredAt) {
        String callNo = fixtureIdentifier(suffix);
        jdbcTemplate.update("""
            INSERT INTO provider_call_log
                (call_no, provider_key, provider_domain, provider_env, provider_name, operation_type, status,
                 config_version, snapshot_type, snapshot_id, source_type, occurred_at, create_time)
            VALUES (?, ?, 'WEATHER', 'DEV', 'P5_MYSQL_TEST', 'RETENTION_TEST', ?, 'P5_TEST_V1', ?, ?,
                    'SCHEDULED', ?, ?)
            """, callNo, providerKey, status, snapshotType, snapshotId, occurredAt, occurredAt);
        return idFor("provider_call_log", "call_no", callNo);
    }

    private void assertCandidatesAreBoundedAndProtected(List<Long> candidates,
                                                         List<Long> expiredRows,
                                                         List<Long> protectedRows) {
        assertThat(candidates).hasSizeLessThanOrEqualTo(RETENTION_BATCH_SIZE);
        assertThat(candidates).containsAnyElementsOf(expiredRows);
        assertThat(candidates).doesNotContainAnyElementsOf(protectedRows);
    }

    private void assertBoundedPhysicalDeletion(String table, List<Long> expiredRows) {
        long remainingExpiredRows = expiredRows.stream().filter(id -> rowExists(table, id)).count();
        long physicallyDeletedRows = expiredRows.size() - remainingExpiredRows;
        long allowedRows = (long) RETENTION_BATCH_SIZE * RETENTION_MAX_BATCHES;

        assertThat(physicallyDeletedRows).isGreaterThan(0).isLessThanOrEqualTo(allowedRows);
        assertThat(remainingExpiredRows).isGreaterThan(0);
    }

    private void assertRowsExist(String table, List<Long> ids) {
        ids.forEach(id -> assertThat(rowExists(table, id)).isTrue());
    }

    private int countRows(String table, String providerKey) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE provider_key = ?", Integer.class, providerKey);
        return count == null ? 0 : count;
    }

    private long idFor(String table, String column, String value) {
        Long id = jdbcTemplate.queryForObject(
            "SELECT id FROM " + table + " WHERE " + column + " = ?", Long.class, value);
        if (id == null) {
            throw new IllegalStateException("fixture insert did not create an id");
        }
        return id;
    }

    private boolean rowExists(String table, long id) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Long.class, id);
        return count != null && count == 1L;
    }

    private String fixtureKey(String suffix) {
        return fixturePrefix + "." + suffix;
    }

    private String fixtureIdentifier(String suffix) {
        return fixturePrefix + "." + suffix;
    }

    private void awaitLatch(CountDownLatch latch, String failureMessage) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(failureMessage);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, ex);
        }
    }

    record MigrationHistoryRow(String version, String script, Integer checksum) {
    }

    static class InjectedPostSnapshotFailure extends RuntimeException {
        InjectedPostSnapshotFailure() {
            super("injected after weather snapshot insert");
        }
    }

    static class FailingAfterSnapshotObservabilityService extends ProviderObservabilityService {
        private final AtomicBoolean failNextRecordCall = new AtomicBoolean();
        private final AtomicBoolean injectedAfterSnapshot = new AtomicBoolean();

        FailingAfterSnapshotObservabilityService(ProviderRegistryEntryMapper registryMapper,
                                                  ProviderCallLogMapper callLogMapper,
                                                  ProviderRegistryStateService registryStateService) {
            super(registryMapper, callLogMapper, registryStateService);
        }

        @Override
        public void recordCall(ProviderCallRecord record) {
            if (failNextRecordCall.compareAndSet(true, false)) {
                injectedAfterSnapshot.set(true);
                throw new InjectedPostSnapshotFailure();
            }
            super.recordCall(record);
        }

        void failNextRecordCallAfterSnapshotInsert() {
            failNextRecordCall.set(true);
        }

        boolean wasInjectedAfterSnapshot() {
            return injectedAfterSnapshot.get();
        }

        void clearInjectedFailure() {
            failNextRecordCall.set(false);
            injectedAfterSnapshot.set(false);
        }
    }

    static class RegistryRowLockAttemptProbe {
        private final AtomicReference<RegistryRowLockAttempt> armedAttempt = new AtomicReference<>();

        void arm(CountDownLatch queryEntered, CountDownLatch queryReturned) {
            if (!armedAttempt.compareAndSet(null, new RegistryRowLockAttempt(queryEntered, queryReturned))) {
                throw new IllegalStateException("registry row-lock probe was already armed");
            }
        }

        RegistryRowLockAttempt takeFor(String statementId) {
            if (!REGISTRY_ROW_LOCK_STATEMENT.equals(statementId)) {
                return null;
            }
            return armedAttempt.getAndSet(null);
        }

        void clear() {
            armedAttempt.set(null);
        }
    }

    record RegistryRowLockAttempt(CountDownLatch queryEntered, CountDownLatch queryReturned) {
    }

    @Intercepts({
        @Signature(type = Executor.class, method = "query", args = {
            MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class
        })
    })
    static class RegistryRowLockAttemptInterceptor implements Interceptor {
        private final RegistryRowLockAttemptProbe probe;

        RegistryRowLockAttemptInterceptor(RegistryRowLockAttemptProbe probe) {
            this.probe = probe;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            RegistryRowLockAttempt attempt = probe.takeFor(statement.getId());
            if (attempt == null) {
                return invocation.proceed();
            }
            attempt.queryEntered().countDown();
            try {
                return invocation.proceed();
            } finally {
                attempt.queryReturned().countDown();
            }
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = ProviderCallLogMapper.class)
    static class IntegrationApplication {
        @Bean
        Clock providerEvidenceIntegrationClock() {
            return FIXED_CLOCK;
        }

        @Bean
        ProviderObservabilityProperties providerObservabilityProperties() {
            return new ProviderObservabilityProperties();
        }

        @Bean
        InProcessProviderRunGuard inProcessProviderRunGuard() {
            return new InProcessProviderRunGuard();
        }

        @Bean
        RegistryRowLockAttemptProbe registryRowLockAttemptProbe() {
            return new RegistryRowLockAttemptProbe();
        }

        @Bean
        Interceptor registryRowLockAttemptInterceptor(RegistryRowLockAttemptProbe probe) {
            return new RegistryRowLockAttemptInterceptor(probe);
        }

        @Bean
        ProviderRegistryStateService providerRegistryStateService(ProviderRegistryEntryMapper registryMapper) {
            return new ProviderRegistryStateService(registryMapper);
        }

        @Bean
        FailingAfterSnapshotObservabilityService providerObservabilityService(
            ProviderRegistryEntryMapper registryMapper,
            ProviderCallLogMapper callLogMapper,
            ProviderRegistryStateService registryStateService) {
            return new FailingAfterSnapshotObservabilityService(registryMapper, callLogMapper, registryStateService);
        }

        @Bean
        ProviderEvidenceWriter providerEvidenceWriter(
            WeatherObservationSnapshotMapper weatherSnapshotMapper,
            RiskAssessmentSnapshotMapper riskSnapshotMapper,
            OpsMetricSnapshotMapper metricSnapshotMapper,
            FailingAfterSnapshotObservabilityService observabilityService) {
            return new ProviderEvidenceWriter(
                weatherSnapshotMapper, riskSnapshotMapper, metricSnapshotMapper, observabilityService);
        }

        @Bean
        ProviderEvidenceRetentionService providerEvidenceRetentionService(
            ProviderObservabilityProperties properties,
            Clock clock,
            InProcessProviderRunGuard runGuard,
            ProviderCallLogMapper callLogMapper,
            WeatherObservationSnapshotMapper weatherSnapshotMapper,
            RiskAssessmentSnapshotMapper riskSnapshotMapper,
            OpsMetricSnapshotMapper metricSnapshotMapper,
            PlatformTransactionManager transactionManager) {
            return new ProviderEvidenceRetentionService(
                properties, clock, runGuard, callLogMapper, weatherSnapshotMapper, riskSnapshotMapper,
                metricSnapshotMapper, transactionManager);
        }
    }
}
