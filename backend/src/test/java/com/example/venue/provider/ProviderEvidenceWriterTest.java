package com.example.venue.provider;

import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRefreshResponse;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.entity.OpsMetricSnapshot;
import com.example.venue.provider.entity.ProviderCallLog;
import com.example.venue.provider.entity.ProviderRegistryEntry;
import com.example.venue.provider.entity.RiskAssessmentSnapshot;
import com.example.venue.provider.entity.WeatherObservationSnapshot;
import com.example.venue.provider.mapper.OpsMetricSnapshotMapper;
import com.example.venue.provider.mapper.ProviderCallLogMapper;
import com.example.venue.provider.mapper.ProviderRegistryEntryMapper;
import com.example.venue.provider.mapper.RiskAssessmentSnapshotMapper;
import com.example.venue.provider.mapper.WeatherObservationSnapshotMapper;
import com.example.venue.provider.service.ProviderEvidenceWriter;
import com.example.venue.provider.service.ProviderObservabilityService;
import com.example.venue.provider.service.ProviderRegistryStateService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProviderEvidenceWriterTest {
    private static final String PROVIDER_KEY = "weather.open_meteo.dev";

    @Test
    void riskSuccessWritesSnapshotCallAndRegistryAsOneShortTransaction() {
        try (WriterFixture fixture = new WriterFixture(false)) {
            RiskAssessmentSnapshot snapshot = riskSnapshot();

            fixture.writer.writeRiskSuccess(snapshot,
                id -> riskSuccessRecordWithId(ProviderRunContext.manual(7L), id, true));

            assertThat(fixture.events).containsExactly("risk.insert", "call.insert", "registry.lock", "registry.update");
            assertThat(fixture.transactionManager.beginCount).isEqualTo(1);
            assertThat(fixture.transactionManager.commitCount).isEqualTo(1);
            assertThat(fixture.transactionManager.rollbackCount).isZero();
            assertThat(fixture.committedRiskSnapshots).containsExactly(snapshot);
            assertThat(fixture.callLogSnapshotId()).isEqualTo(snapshot.id);
        }
    }

    @Test
    void scheduledWeatherDetailWritesSnapshotAndEvidenceWithoutRegistryImpact() {
        try (WriterFixture fixture = new WriterFixture(false)) {
            WeatherObservationSnapshot snapshot = weatherSnapshot();

            fixture.writer.writeWeatherDetail(
                snapshot, successRecord(ProviderRunContext.scheduled(), "WEATHER", false));

            assertThat(fixture.events).containsExactly("weather.insert", "call.insert");
            assertThat(fixture.transactionManager.beginCount).isEqualTo(1);
            assertThat(fixture.transactionManager.commitCount).isEqualTo(1);
            assertThat(fixture.committedWeatherSnapshots).containsExactly(snapshot);
            assertThat(fixture.callLogSnapshotId()).isEqualTo(snapshot.id);
            verify(fixture.registryMapper, never()).selectByProviderKeyForUpdate(any());
        }
    }

    @Test
    void manualWeatherSuccessReturnsAssignedSnapshotAndAppliesRegistryImpact() {
        try (WriterFixture fixture = new WriterFixture(false)) {
            WeatherObservationSnapshot snapshot = weatherSnapshot();

            ProviderRefreshResponse response = fixture.writer.writeWeatherSuccess(
                snapshot, successRecord(ProviderRunContext.manual(7L), "WEATHER", true));

            assertThat(response.status).isEqualTo("SUCCESS");
            assertThat(response.providerKey).isEqualTo(PROVIDER_KEY);
            assertThat(response.snapshotId).isEqualTo(snapshot.id);
            assertThat(fixture.events).containsExactly("weather.insert", "call.insert", "registry.lock", "registry.update");
        }
    }

    @Test
    void metricsSuccessWritesEverySnapshotBeforeOneRegistryImpactOutcome() {
        try (WriterFixture fixture = new WriterFixture(false)) {
            OpsMetricSnapshot first = metricSnapshot("redis_hit_rate");
            OpsMetricSnapshot second = metricSnapshot("db_available");

            fixture.writer.writeMetricsSuccess(
                List.of(first, second), successRecord(ProviderRunContext.manual(7L), "METRIC", true));

            assertThat(fixture.events).containsExactly(
                "metric.insert", "metric.insert", "call.insert", "registry.lock", "registry.update");
            assertThat(fixture.committedMetricSnapshots).containsExactly(first, second);
            assertThat(fixture.callLogSnapshotId()).isEqualTo(first.id);
        }
    }

    @Test
    void evidenceOnlyCallCannotMutateRegistryAndRegistryOutcomeIsExplicit() {
        try (WriterFixture fixture = new WriterFixture(false)) {
            fixture.writer.writeCallOnly(skippedRecord());

            assertThat(fixture.events).containsExactly("call.insert");
            verify(fixture.registryMapper, never()).selectByProviderKeyForUpdate(any());
        }

        try (WriterFixture fixture = new WriterFixture(false)) {
            fixture.writer.writeRegistryOutcome(successRecord(ProviderRunContext.scheduled(), "WEATHER", true));

            assertThat(fixture.events).containsExactly("call.insert", "registry.lock", "registry.update");
        }
    }

    @Test
    void callOnlySuspendsAndResumesAnOuterTransaction() {
        try (WriterFixture fixture = new WriterFixture(false)) {
            fixture.inOuterTransaction(() -> fixture.writer.writeCallOnly(skippedRecord()));

            assertThat(fixture.events).containsExactly("call.insert");
            assertThat(fixture.transactionManager.beginCount).isEqualTo(2);
            assertThat(fixture.transactionManager.commitCount).isEqualTo(2);
            assertThat(fixture.transactionManager.suspendCount).isEqualTo(1);
            assertThat(fixture.transactionManager.resumeCount).isEqualTo(1);
        }
    }

    @Test
    void registryOutcomeSuspendsAndResumesAnOuterTransaction() {
        try (WriterFixture fixture = new WriterFixture(false)) {
            fixture.inOuterTransaction(() ->
                fixture.writer.writeRegistryOutcome(successRecord(ProviderRunContext.scheduled(), "WEATHER", true)));

            assertThat(fixture.events).containsExactly("call.insert", "registry.lock", "registry.update");
            assertThat(fixture.transactionManager.beginCount).isEqualTo(2);
            assertThat(fixture.transactionManager.commitCount).isEqualTo(2);
            assertThat(fixture.transactionManager.suspendCount).isEqualTo(1);
            assertThat(fixture.transactionManager.resumeCount).isEqualTo(1);
        }
    }

    @Test
    void rejectsRegistryFlagsAtEvidenceOnlyAndSnapshotOutcomeBoundaries() {
        try (WriterFixture fixture = new WriterFixture(false)) {
            assertThatThrownBy(() -> fixture.writer.writeWeatherDetail(
                weatherSnapshot(), successRecord(ProviderRunContext.scheduled(), "WEATHER", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence-only");
            assertThatThrownBy(() -> fixture.writer.writeCallOnly(
                successRecord(ProviderRunContext.scheduled(), "WEATHER", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence-only");
            assertThatThrownBy(() -> fixture.writer.writeRiskSuccess(
                riskSnapshot(), id -> riskSuccessRecordWithId(ProviderRunContext.manual(7L), id, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registry-impact");
        }
    }

    @Test
    void injectedCallFailureRollsBackTheEntireRiskSuccessUnit() {
        try (WriterFixture fixture = new WriterFixture(true)) {
            assertThatThrownBy(() -> fixture.writer.writeRiskSuccess(
                riskSnapshot(), id -> riskSuccessRecordWithId(ProviderRunContext.manual(7L), id, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected call failure");

            assertThat(fixture.events).containsExactly("risk.insert", "call.insert");
            assertThat(fixture.committedRiskSnapshots).isEmpty();
            assertThat(fixture.transactionManager.beginCount).isEqualTo(1);
            assertThat(fixture.transactionManager.commitCount).isZero();
            assertThat(fixture.transactionManager.rollbackCount).isEqualTo(1);
            verifyNoInteractions(fixture.registryMapper);
        }
    }

    @Test
    void riskSuccessBuildsResponseSummaryAndSnapshotIdAfterInsertWithBackfilledId() {
        // A26-P3-35：writeRiskSuccess 在 insert 后用回填的 snapshot.id 调用 outcomeBuilder，
        // 确保 response_summary_json 中的 snapshotId 与 snapshot_id 列携带同一回填值，
        // 不再出现 response_summary_json.snapshotId=null 而 snapshot_id 列非 null 的矛盾。
        try (WriterFixture fixture = new WriterFixture(false)) {
            RiskAssessmentSnapshot snapshot = riskSnapshot();

            fixture.writer.writeRiskSuccess(snapshot, id -> ProviderCallRecord.of(
                ProviderRunContext.manual(7L), PROVIDER_KEY, "RISK", "DEV", "INTERNAL_RISK", "idem-risk",
                "ASSESS_RISK", "SUCCESS", "RISK_V1", "{\"scope\":\"test\"}",
                "{\"snapshotId\":" + id + ",\"riskScore\":62.50}", null, null, 20L, null, "RISK", id,
                BigDecimal.valueOf(0.9), true));

            assertThat(snapshot.id).isNotNull();
            assertThat(fixture.callLog).isNotNull();
            assertThat(fixture.callLog.snapshotId).isEqualTo(snapshot.id);
            assertThat(fixture.callLog.responseSummaryJson)
                .isEqualTo("{\"snapshotId\":" + snapshot.id + ",\"riskScore\":62.50}");
        }
    }

    private static ProviderCallRecord successRecord(ProviderRunContext context,
                                                     String snapshotType,
                                                     boolean updateRegistry) {
        return ProviderCallRecord.of(
            context, PROVIDER_KEY, "WEATHER", "DEV", "OPEN_METEO", "idem-" + snapshotType,
            "FETCH_" + snapshotType, "SUCCESS", "OPEN_METEO_V1", "{\"scope\":\"test\"}",
            "{\"ok\":true}", null, null, 20L, null, snapshotType, null,
            BigDecimal.valueOf(0.9), updateRegistry);
    }

    // A26-P3-35：risk 路径的 outcomeBuilder 在 insert 后被调用，需要用回填的 snapshotId 构造 record，
    // 让 response_summary_json 和 snapshot_id 字段都携带同一回填值（与 RiskObservationCollectionService.successRecord 对齐）。
    private static ProviderCallRecord riskSuccessRecordWithId(ProviderRunContext context,
                                                              Long snapshotId,
                                                              boolean updateRegistry) {
        return ProviderCallRecord.of(
            context, PROVIDER_KEY, "RISK", "DEV", "INTERNAL_RISK", "idem-risk",
            "ASSESS_RISK", "SUCCESS", "RISK_V1", "{\"scope\":\"test\"}",
            "{\"snapshotId\":" + snapshotId + ",\"ok\":true}", null, null, 20L, null, "RISK", snapshotId,
            BigDecimal.valueOf(0.9), updateRegistry);
    }

    private static ProviderCallRecord skippedRecord() {
        return ProviderCallRecord.of(
            ProviderRunContext.scheduled(), PROVIDER_KEY, "WEATHER", "DEV", "OPEN_METEO", "idem-skip",
            "FETCH_WEATHER", "SKIPPED", "OPEN_METEO_V1", "{\"scope\":\"test\"}", null,
            "NO_ACTIVE_LOCATION", "no active location", 20L, null, "WEATHER", null, null, false);
    }

    private static WeatherObservationSnapshot weatherSnapshot() {
        WeatherObservationSnapshot snapshot = new WeatherObservationSnapshot();
        snapshot.providerKey = PROVIDER_KEY;
        snapshot.deleted = 0;
        return snapshot;
    }

    private static RiskAssessmentSnapshot riskSnapshot() {
        RiskAssessmentSnapshot snapshot = new RiskAssessmentSnapshot();
        snapshot.providerKey = PROVIDER_KEY;
        snapshot.deleted = 0;
        return snapshot;
    }

    private static OpsMetricSnapshot metricSnapshot(String metricKey) {
        OpsMetricSnapshot snapshot = new OpsMetricSnapshot();
        snapshot.providerKey = PROVIDER_KEY;
        snapshot.metricKey = metricKey;
        snapshot.deleted = 0;
        return snapshot;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionalWriterConfiguration {
    }

    private static final class WriterFixture implements AutoCloseable {
        private final List<String> events = new ArrayList<>();
        private final List<WeatherObservationSnapshot> committedWeatherSnapshots = new ArrayList<>();
        private final List<RiskAssessmentSnapshot> committedRiskSnapshots = new ArrayList<>();
        private final List<OpsMetricSnapshot> committedMetricSnapshots = new ArrayList<>();
        private final AtomicLong nextId = new AtomicLong(100);
        private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        private final WeatherObservationSnapshotMapper weatherMapper = mock(WeatherObservationSnapshotMapper.class);
        private final RiskAssessmentSnapshotMapper riskMapper = mock(RiskAssessmentSnapshotMapper.class);
        private final OpsMetricSnapshotMapper metricMapper = mock(OpsMetricSnapshotMapper.class);
        private final ProviderCallLogMapper callLogMapper = mock(ProviderCallLogMapper.class);
        private final ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        private final ProviderEvidenceWriter writer;
        private ProviderCallLog callLog;

        private WriterFixture(boolean failCallLog) {
            configureSnapshotMappers();
            configureCallLogMapper(failCallLog);
            configureRegistryMapper();
            context.register(TransactionalWriterConfiguration.class);
            context.registerBean("transactionManager", PlatformTransactionManager.class, () -> transactionManager);
            context.registerBean(WeatherObservationSnapshotMapper.class, () -> weatherMapper);
            context.registerBean(RiskAssessmentSnapshotMapper.class, () -> riskMapper);
            context.registerBean(OpsMetricSnapshotMapper.class, () -> metricMapper);
            context.registerBean(ProviderCallLogMapper.class, () -> callLogMapper);
            context.registerBean(ProviderRegistryEntryMapper.class, () -> registryMapper);
            context.registerBean(ProviderRegistryStateService.class,
                () -> new ProviderRegistryStateService(registryMapper));
            context.registerBean(ProviderObservabilityService.class,
                () -> new ProviderObservabilityService(registryMapper, callLogMapper,
                    context.getBean(ProviderRegistryStateService.class)));
            context.registerBean(ProviderEvidenceWriter.class,
                () -> new ProviderEvidenceWriter(weatherMapper, riskMapper, metricMapper,
                    context.getBean(ProviderObservabilityService.class)));
            context.refresh();
            writer = context.getBean(ProviderEvidenceWriter.class);
        }

        private void configureSnapshotMappers() {
            doAnswer(invocation -> {
                WeatherObservationSnapshot snapshot = invocation.getArgument(0);
                snapshot.id = nextId.incrementAndGet();
                events.add("weather.insert");
                stageOnCommit(committedWeatherSnapshots, snapshot);
                return 1;
            }).when(weatherMapper).insert(any(WeatherObservationSnapshot.class));
            doAnswer(invocation -> {
                RiskAssessmentSnapshot snapshot = invocation.getArgument(0);
                snapshot.id = nextId.incrementAndGet();
                events.add("risk.insert");
                stageOnCommit(committedRiskSnapshots, snapshot);
                return 1;
            }).when(riskMapper).insert(any(RiskAssessmentSnapshot.class));
            doAnswer(invocation -> {
                OpsMetricSnapshot snapshot = invocation.getArgument(0);
                snapshot.id = nextId.incrementAndGet();
                events.add("metric.insert");
                stageOnCommit(committedMetricSnapshots, snapshot);
                return 1;
            }).when(metricMapper).insert(any(OpsMetricSnapshot.class));
        }

        private void configureCallLogMapper(boolean failCallLog) {
            doAnswer(invocation -> {
                events.add("call.insert");
                if (failCallLog) {
                    throw new IllegalStateException("injected call failure");
                }
                callLog = invocation.getArgument(0);
                return 1;
            }).when(callLogMapper).insert(any(ProviderCallLog.class));
        }

        private void configureRegistryMapper() {
            when(registryMapper.selectByProviderKeyForUpdate(PROVIDER_KEY)).thenAnswer(invocation -> {
                events.add("registry.lock");
                return activeRegistry();
            });
            doAnswer(invocation -> {
                events.add("registry.update");
                return 1;
            }).when(registryMapper).update(any(ProviderRegistryEntry.class), any());
        }

        private Long callLogSnapshotId() {
            return callLog.snapshotId;
        }

        private void inOuterTransaction(Runnable action) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
        }

        private <T> void stageOnCommit(List<T> committed, T value) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException("snapshot write must execute inside a transaction");
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        committed.add(value);
                    }
                }
            });
        }

        @Override
        public void close() {
            context.close();
        }
    }

    private static ProviderRegistryEntry activeRegistry() {
        ProviderRegistryEntry entry = new ProviderRegistryEntry();
        entry.id = 1L;
        entry.providerKey = PROVIDER_KEY;
        entry.enabled = 1;
        entry.status = "ACTIVE";
        entry.consecutiveFailures = 0;
        entry.failureThreshold = 3;
        entry.deleted = 0;
        return entry;
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int beginCount;
        private int commitCount;
        private int rollbackCount;
        private int suspendCount;
        private int resumeCount;
        private final ThreadLocal<RecordingTransaction> currentTransaction = new ThreadLocal<>();

        @Override
        protected Object doGetTransaction() {
            RecordingTransaction existing = currentTransaction.get();
            return existing == null ? new RecordingTransaction() : existing;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return transaction == currentTransaction.get();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            beginCount++;
            currentTransaction.set((RecordingTransaction) transaction);
        }

        @Override
        protected Object doSuspend(Object transaction) {
            suspendCount++;
            RecordingTransaction suspended = currentTransaction.get();
            currentTransaction.remove();
            return suspended;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            resumeCount++;
            currentTransaction.set((RecordingTransaction) suspendedResources);
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((RecordingTransaction) status.getTransaction()).rollbackOnly = true;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commitCount++;
            currentTransaction.remove();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbackCount++;
            currentTransaction.remove();
        }

        private static final class RecordingTransaction implements SmartTransactionObject {
            private boolean rollbackOnly;

            @Override
            public boolean isRollbackOnly() {
                return rollbackOnly;
            }
        }
    }
}
