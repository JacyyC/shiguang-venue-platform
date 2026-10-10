package com.example.venue.provider;

import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRefreshResponse;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.entity.OpsMetricSnapshot;
import com.example.venue.provider.mapper.OpsMetricSnapshotMapper;
import com.example.venue.provider.mapper.ProviderRegistryEntryMapper;
import com.example.venue.provider.metrics.LocalRuntimeMetricsProvider;
import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import com.example.venue.provider.service.ProviderEvidenceWriter;
import com.example.venue.provider.service.RuntimeMetricsCollectionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuntimeMetricsCollectionServiceTest {
    private static final String RUNNING_GAP = "采集器正在运行，本轮未执行";
    private static final String METRICS_FAILURE_GAP = "本地运行指标采集失败，请检查Provider状态。";

    private final LocalRuntimeMetricsProvider metricsProvider = mock(LocalRuntimeMetricsProvider.class);
    private final ProviderEvidenceWriter evidenceWriter = mock(ProviderEvidenceWriter.class);
    private final InProcessProviderRunGuard guard = new InProcessProviderRunGuard();
    private final RuntimeMetricsCollectionService service = new RuntimeMetricsCollectionService(
        metricsProvider, evidenceWriter, guard);

    @Test
    void scheduledMetricsSuccessWritesAllFourKeysAndPreservesTheFirstSnapshotId() {
        List<OpsMetricSnapshot> snapshots = completeMetrics();
        when(metricsProvider.collect()).thenReturn(snapshots);
        when(metricsProvider.dataGaps()).thenReturn(List.of(LocalRuntimeMetricsProvider.SLOW_QUERY_GAP));
        doAnswer(invocation -> {
            List<OpsMetricSnapshot> written = invocation.getArgument(0);
            written.get(0).id = 701L;
            return null;
        }).when(evidenceWriter).writeMetricsSuccess(anyList(), any(ProviderCallRecord.class));

        ProviderRefreshResponse response = service.collect(ProviderRunContext.scheduled());

        assertThat(response.status).isEqualTo("SUCCESS");
        assertThat(response.providerKey).isEqualTo(LocalRuntimeMetricsProvider.PROVIDER_KEY);
        assertThat(response.snapshotId).isEqualTo(701L);
        assertThat(response.dataGaps).containsExactly(LocalRuntimeMetricsProvider.SLOW_QUERY_GAP);
        ArgumentCaptor<List<OpsMetricSnapshot>> snapshotsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ProviderCallRecord> recordCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(evidenceWriter).writeMetricsSuccess(snapshotsCaptor.capture(), recordCaptor.capture());
        assertThat(snapshotsCaptor.getValue()).extracting(snapshot -> snapshot.metricKey)
            .containsExactlyInAnyOrder("db_ping_ms", "redis_ping_ms", "redis_hit_rate", "jvm_memory_used_mb");
        ProviderCallRecord record = recordCaptor.getValue();
        assertThat(record.status()).isEqualTo("SUCCESS");
        assertThat(record.sourceType()).isEqualTo("SCHEDULED");
        assertThat(record.triggeredBy()).isNull();
        assertThat(record.updateRegistry()).isTrue();
        assertThat(record.responseSummaryJson())
            .contains("\"metricKey\":\"db_ping_ms\"", "\"metricKey\":\"redis_ping_ms\"",
                "\"metricKey\":\"redis_hit_rate\"", "\"metricKey\":\"jvm_memory_used_mb\"",
                "\"status\":\"OK\"", "\"metricValue\":12.34", LocalRuntimeMetricsProvider.SLOW_QUERY_GAP)
            .doesNotContain("sourceSummary", "snapshotId", "raw-secret");
        InOrder inOrder = inOrder(metricsProvider, evidenceWriter);
        inOrder.verify(metricsProvider).collect();
        inOrder.verify(evidenceWriter).writeMetricsSuccess(anyList(), any(ProviderCallRecord.class));
    }

    @Test
    void partialMetricsAvailabilityWritesDegradedRegistryOutcomeWithTheSharedFailureCode() {
        List<OpsMetricSnapshot> snapshots = completeMetrics();
        snapshots.get(2).status = "UNAVAILABLE";
        snapshots.get(2).metricValue = null;
        when(metricsProvider.collect()).thenReturn(snapshots);
        when(metricsProvider.dataGaps()).thenReturn(List.of(LocalRuntimeMetricsProvider.SLOW_QUERY_GAP));

        ProviderRefreshResponse response = service.collect(ProviderRunContext.manual(7L));

        assertThat(response.status).isEqualTo("DEGRADED");
        assertThat(response.dataGaps).containsExactly(LocalRuntimeMetricsProvider.SLOW_QUERY_GAP);
        ArgumentCaptor<ProviderCallRecord> recordCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(evidenceWriter).writeMetricsSuccess(anyList(), recordCaptor.capture());
        ProviderCallRecord record = recordCaptor.getValue();
        assertThat(record.status()).isEqualTo("DEGRADED");
        assertThat(record.failureCode()).isEqualTo("METRICS_PARTIAL_UNAVAILABLE");
        assertThat(record.sourceType()).isEqualTo("MANUAL");
        assertThat(record.triggeredBy()).isEqualTo(7L);
        assertThat(record.updateRegistry()).isTrue();
        verify(evidenceWriter, never()).writeRegistryOutcome(any());
    }

    @Test
    void metricSnapshotsDoNotRetainRawSourcePayloadBeforeEvidenceWrite() {
        List<OpsMetricSnapshot> snapshots = completeMetrics();
        snapshots.forEach(snapshot -> snapshot.sourceSummaryJson =
            "{\"rawRuntimeSource\":\"NOT_FOR_PERSISTENCE\"}");
        when(metricsProvider.collect()).thenReturn(snapshots);
        when(metricsProvider.dataGaps()).thenReturn(List.of(LocalRuntimeMetricsProvider.SLOW_QUERY_GAP));

        service.collect(ProviderRunContext.scheduled());

        ArgumentCaptor<List<OpsMetricSnapshot>> snapshotsCaptor = ArgumentCaptor.forClass(List.class);
        verify(evidenceWriter).writeMetricsSuccess(snapshotsCaptor.capture(), any(ProviderCallRecord.class));
        assertThat(snapshotsCaptor.getValue()).allSatisfy(snapshot ->
            assertThat(snapshot.sourceSummaryJson).isNull());
    }

    @Test
    void busyMetricsLaneWritesOnlyOneEvidenceOnlySkippedRecordAndDoesNotCallTheProvider() {
        assertThat(guard.tryAcquire(InProcessProviderRunGuard.Lane.METRICS)).isTrue();
        try {
            ProviderRefreshResponse response = service.collect(ProviderRunContext.manual(7L));

            assertThat(response.status).isEqualTo("SKIPPED");
            assertThat(response.providerKey).isEqualTo(LocalRuntimeMetricsProvider.PROVIDER_KEY);
            assertThat(response.snapshotId).isNull();
            assertThat(response.dataGaps).containsExactly(RUNNING_GAP);
            verifyNoInteractions(metricsProvider);
            ArgumentCaptor<ProviderCallRecord> recordCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
            verify(evidenceWriter, times(1)).writeCallOnly(recordCaptor.capture());
            ProviderCallRecord record = recordCaptor.getValue();
            assertThat(record.status()).isEqualTo("SKIPPED");
            assertThat(record.failureCode()).isEqualTo("COLLECTION_ALREADY_RUNNING");
            assertThat(record.sourceType()).isEqualTo("MANUAL");
            assertThat(record.triggeredBy()).isEqualTo(7L);
            assertThat(record.updateRegistry()).isFalse();
            verify(evidenceWriter, never()).writeMetricsSuccess(anyList(), any());
            verify(evidenceWriter, never()).writeRegistryOutcome(any());
        } finally {
            guard.release(InProcessProviderRunGuard.Lane.METRICS);
        }
    }

    @Test
    void collectionProcessFailureWritesOneSafeFailedRegistryOutcomeAndKeepsTheSlowQueryGap() {
        // A26-P3-36：failureMessage 改为携带真实异常 message（与天气侧对齐），
        // 由 ProviderObservabilityService 经 SensitiveDataMasker 脱敏后落库（password=xxx 被替换为 password=***）。
        // 原实现完全吞掉真实异常，failureMessage 恒为固定 GAP，连续失败时运维无根因线索。
        // response.dataGaps 仍返回固定 GAP 列表保持用户友好。
        when(metricsProvider.collect()).thenThrow(new IllegalStateException("redis password=raw-secret"));

        ProviderRefreshResponse response = service.collect(ProviderRunContext.scheduled());

        assertThat(response.status).isEqualTo("FAILED");
        assertThat(response.providerKey).isEqualTo(LocalRuntimeMetricsProvider.PROVIDER_KEY);
        assertThat(response.snapshotId).isNull();
        assertThat(response.dataGaps)
            .containsExactly(LocalRuntimeMetricsProvider.SLOW_QUERY_GAP, METRICS_FAILURE_GAP)
            .doesNotContain("raw-secret");
        ArgumentCaptor<ProviderCallRecord> recordCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(evidenceWriter, times(1)).writeRegistryOutcome(recordCaptor.capture());
        ProviderCallRecord record = recordCaptor.getValue();
        assertThat(record.status()).isEqualTo("FAILED");
        assertThat(record.failureCode()).isEqualTo("METRICS_PROVIDER_FAILED");
        // service 层传入原始异常 message，脱敏由 ProviderObservabilityService 落库时完成。
        assertThat(record.failureMessage()).isEqualTo("redis password=raw-secret");
        assertThat(record.sourceType()).isEqualTo("SCHEDULED");
        assertThat(record.triggeredBy()).isNull();
        assertThat(record.updateRegistry()).isTrue();
        verify(evidenceWriter, never()).writeMetricsSuccess(anyList(), any());
        verify(evidenceWriter, never()).writeCallOnly(any());
    }

    @Test
    void missingMetricsCollectionResultIsNotRecordedAsAnEmptySuccessfulObservation() {
        when(metricsProvider.collect()).thenReturn(null);

        ProviderRefreshResponse response = service.collect(ProviderRunContext.scheduled());

        assertThat(response.status).isEqualTo("FAILED");
        assertThat(response.providerKey).isEqualTo(LocalRuntimeMetricsProvider.PROVIDER_KEY);
        assertThat(response.snapshotId).isNull();
        assertThat(response.dataGaps)
            .containsExactly(LocalRuntimeMetricsProvider.SLOW_QUERY_GAP, METRICS_FAILURE_GAP);
        ArgumentCaptor<ProviderCallRecord> recordCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(evidenceWriter).writeRegistryOutcome(recordCaptor.capture());
        assertThat(recordCaptor.getValue().status()).isEqualTo("FAILED");
        assertThat(recordCaptor.getValue().failureCode()).isEqualTo("METRICS_PROVIDER_FAILED");
        // A26-P3-36：Objects.requireNonNull 抛 NullPointerException("metrics snapshots")，failureMessage 携带该 message。
        assertThat(recordCaptor.getValue().failureMessage()).isEqualTo("metrics snapshots");
        verify(evidenceWriter, never()).writeMetricsSuccess(anyList(), any());
        verify(evidenceWriter, never()).writeCallOnly(any());
    }

    @Test
    void metricsWriterFailurePropagatesWithoutBeingRelabeledAsCollectionFailure() {
        List<OpsMetricSnapshot> snapshots = completeMetrics();
        IllegalStateException writerFailure = new IllegalStateException("metrics evidence write failed");
        when(metricsProvider.collect()).thenReturn(snapshots);
        doThrow(writerFailure).when(evidenceWriter).writeMetricsSuccess(anyList(), any());

        assertThatThrownBy(() -> service.collect(ProviderRunContext.scheduled()))
            .isSameAs(writerFailure);

        verify(evidenceWriter).writeMetricsSuccess(anyList(), any());
        verify(evidenceWriter, never()).writeRegistryOutcome(any());
        verify(evidenceWriter, never()).writeCallOnly(any());
        assertThat(guard.tryAcquire(InProcessProviderRunGuard.Lane.METRICS)).isTrue();
        guard.release(InProcessProviderRunGuard.Lane.METRICS);
    }

    @Test
    void collectionBoundaryUsesOnlyTheLocalMetricsProviderGuardAndEvidenceWriter() throws NoSuchMethodException {
        Constructor<?>[] constructors = RuntimeMetricsCollectionService.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(
            LocalRuntimeMetricsProvider.class, ProviderEvidenceWriter.class, InProcessProviderRunGuard.class);
        assertThat(RuntimeMetricsCollectionService.class.getDeclaredFields()).extracting(Field::getType)
            .doesNotContain(OpsMetricSnapshotMapper.class, ProviderRegistryEntryMapper.class);
        assertThat(RuntimeMetricsCollectionService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(RuntimeMetricsCollectionService.class
            .getMethod("collect", ProviderRunContext.class).getAnnotation(Transactional.class)).isNull();
    }

    private List<OpsMetricSnapshot> completeMetrics() {
        return List.of(
            metric("db_ping_ms", new BigDecimal("12.34"), "OK"),
            metric("redis_ping_ms", new BigDecimal("3.21"), "OK"),
            metric("redis_hit_rate", new BigDecimal("0.8750"), "OK"),
            metric("jvm_memory_used_mb", new BigDecimal("256.00"), "OK"));
    }

    private OpsMetricSnapshot metric(String key, BigDecimal value, String status) {
        OpsMetricSnapshot snapshot = new OpsMetricSnapshot();
        snapshot.snapshotNo = "MT-" + key;
        snapshot.metricKey = key;
        snapshot.metricValue = value;
        snapshot.metricUnit = "ms";
        snapshot.status = status;
        snapshot.confidence = new BigDecimal("0.9000");
        snapshot.sourceSummaryJson = "{\"raw-secret\":\"not-for-call-summary\"}";
        return snapshot;
    }
}
