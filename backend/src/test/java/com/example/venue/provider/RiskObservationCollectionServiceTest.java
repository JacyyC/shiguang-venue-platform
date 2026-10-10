package com.example.venue.provider;

import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRefreshResponse;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.entity.RiskAssessmentSnapshot;
import com.example.venue.provider.mapper.ProviderRegistryEntryMapper;
import com.example.venue.provider.mapper.RiskAssessmentSnapshotMapper;
import com.example.venue.provider.risk.InternalRiskRulesProvider;
import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import com.example.venue.provider.service.ProviderEvidenceWriter;
import com.example.venue.provider.service.RiskObservationCollectionService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RiskObservationCollectionServiceTest {
    private static final String RUNNING_GAP = "采集器正在运行，本轮未执行";
    private static final String RISK_FAILURE_GAP = "内部风险规则采集失败，请检查Provider状态。";

    private final InternalRiskRulesProvider riskProvider = mock(InternalRiskRulesProvider.class);
    private final ProviderEvidenceWriter evidenceWriter = mock(ProviderEvidenceWriter.class);
    private final InProcessProviderRunGuard guard = new InProcessProviderRunGuard();
    private final RiskObservationCollectionService service = new RiskObservationCollectionService(
        riskProvider, evidenceWriter, guard);

    @Test
    void scheduledSystemRiskPassesNullWritesRegistryImpactEvidenceAndPreservesScheduledSource() {
        RiskAssessmentSnapshot snapshot = riskSnapshot(501L, "SYSTEM", null);
        when(riskProvider.assess(isNull())).thenReturn(snapshot);

        ProviderRefreshResponse response = service.collect(null, ProviderRunContext.scheduled());

        assertThat(response.status).isEqualTo("SUCCESS");
        assertThat(response.providerKey).isEqualTo(InternalRiskRulesProvider.PROVIDER_KEY);
        assertThat(response.snapshotId).isEqualTo(501L);
        assertThat(response.dataGaps).isEmpty();
        verify(riskProvider).assess(isNull());
        ArgumentCaptor<RiskAssessmentSnapshot> snapshotCaptor =
            ArgumentCaptor.forClass(RiskAssessmentSnapshot.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Function<Long, ProviderCallRecord>> builderCaptor =
            ArgumentCaptor.forClass(java.util.function.Function.class);
        verify(evidenceWriter).writeRiskSuccess(snapshotCaptor.capture(), builderCaptor.capture());
        assertThat(snapshotCaptor.getValue().scopeType).isEqualTo("SYSTEM");
        assertThat(snapshotCaptor.getValue().scopeId).isNull();
        // A26-P3-35：service 传入 outcomeBuilder 回调，由 evidenceWriter 在 insert 后用回填的 snapshot.id 调用。
        // 测试模拟 insert 回填 id=501L，主动调用 builder 验证返回的 record 携带正确的 snapshotId。
        ProviderCallRecord record = builderCaptor.getValue().apply(501L);
        assertThat(record.status()).isEqualTo("SUCCESS");
        assertThat(record.sourceType()).isEqualTo("SCHEDULED");
        assertThat(record.triggeredBy()).isNull();
        assertThat(record.updateRegistry()).isTrue();
        assertThat(record.requestSummaryJson()).isEqualTo("{\"scopeType\":\"SYSTEM\"}");
        assertThat(record.responseSummaryJson())
            .isEqualTo("{\"snapshotId\":501,\"riskScore\":62.50,\"riskLevel\":\"MEDIUM\","
                + "\"failedLoginCount\":2,\"paymentFailureCount\":3,\"noticeFailureCount\":4,"
                + "\"openComplaintCount\":5,\"severeComplaintCount\":6}")
            .doesNotContain("rawRule");
        // A26-P3-35：snapshotId 字段与 response_summary_json 中的 snapshotId 保持一致。
        assertThat(record.snapshotId()).isEqualTo(501L);
        InOrder inOrder = inOrder(riskProvider, evidenceWriter);
        inOrder.verify(riskProvider).assess(isNull());
        inOrder.verify(evidenceWriter).writeRiskSuccess(any(RiskAssessmentSnapshot.class), any());
    }

    @Test
    void manualRiskPreservesSystemAndVenueScopesThroughItsSuppliedContext() {
        RiskAssessmentSnapshot systemSnapshot = riskSnapshot(502L, "SYSTEM", null);
        RiskAssessmentSnapshot venueSnapshot = riskSnapshot(503L, "VENUE", 9L);
        when(riskProvider.assess(isNull())).thenReturn(systemSnapshot);
        when(riskProvider.assess(9L)).thenReturn(venueSnapshot);

        ProviderRefreshResponse systemResponse = service.collect(null, ProviderRunContext.manual(7L));
        ProviderRefreshResponse venueResponse = service.collect(9L, ProviderRunContext.manual(7L));

        assertThat(systemResponse.snapshotId).isEqualTo(502L);
        assertThat(venueResponse.snapshotId).isEqualTo(503L);
        verify(riskProvider).assess(isNull());
        verify(riskProvider).assess(9L);
        ArgumentCaptor<RiskAssessmentSnapshot> snapshotCaptor =
            ArgumentCaptor.forClass(RiskAssessmentSnapshot.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Function<Long, ProviderCallRecord>> builderCaptor =
            ArgumentCaptor.forClass(java.util.function.Function.class);
        verify(evidenceWriter, times(2)).writeRiskSuccess(snapshotCaptor.capture(), builderCaptor.capture());
        assertThat(snapshotCaptor.getAllValues()).extracting(snapshot -> snapshot.scopeType)
            .containsExactly("SYSTEM", "VENUE");
        assertThat(snapshotCaptor.getAllValues()).extracting(snapshot -> snapshot.scopeId)
            .containsExactly(null, 9L);
        // A26-P3-35：用回填的 snapshot.id 调用 builder，验证 record 字段。
        ProviderCallRecord systemRecord = builderCaptor.getAllValues().get(0).apply(502L);
        ProviderCallRecord venueRecord = builderCaptor.getAllValues().get(1).apply(503L);
        assertThat(systemRecord.responseSummaryJson()).contains("\"snapshotId\":502");
        assertThat(venueRecord.responseSummaryJson()).contains("\"snapshotId\":503");
        assertThat(java.util.List.of(systemRecord, venueRecord)).allSatisfy(record -> {
            assertThat(record.sourceType()).isEqualTo("MANUAL");
            assertThat(record.triggeredBy()).isEqualTo(7L);
            assertThat(record.updateRegistry()).isTrue();
        });
        assertThat(java.util.List.of(systemRecord, venueRecord))
            .extracting(ProviderCallRecord::requestSummaryJson)
            .containsExactly("{\"scopeType\":\"SYSTEM\"}", "{\"scopeType\":\"VENUE\",\"venueId\":9}");
    }

    @Test
    void riskSnapshotDoesNotRetainRawRulePayloadBeforeEvidenceWrite() {
        RiskAssessmentSnapshot snapshot = riskSnapshot(505L, "SYSTEM", null);
        snapshot.ruleSummaryJson = "{\"rawRiskRule\":\"NOT_FOR_PERSISTENCE\"}";
        when(riskProvider.assess(isNull())).thenReturn(snapshot);

        service.collect(null, ProviderRunContext.scheduled());

        ArgumentCaptor<RiskAssessmentSnapshot> snapshotCaptor =
            ArgumentCaptor.forClass(RiskAssessmentSnapshot.class);
        verify(evidenceWriter).writeRiskSuccess(snapshotCaptor.capture(), any());
        assertThat(snapshotCaptor.getValue().ruleSummaryJson).isNull();
    }

    @Test
    void busyRiskLaneWritesOnlyOneEvidenceOnlySkippedRecordAndDoesNotCallTheProvider() {
        assertThat(guard.tryAcquire(InProcessProviderRunGuard.Lane.RISK)).isTrue();
        try {
            ProviderRefreshResponse response = service.collect(null, ProviderRunContext.scheduled());

            assertThat(response.status).isEqualTo("SKIPPED");
            assertThat(response.providerKey).isEqualTo(InternalRiskRulesProvider.PROVIDER_KEY);
            assertThat(response.snapshotId).isNull();
            assertThat(response.dataGaps).containsExactly(RUNNING_GAP);
            verifyNoInteractions(riskProvider);
            ArgumentCaptor<ProviderCallRecord> recordCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
            verify(evidenceWriter, times(1)).writeCallOnly(recordCaptor.capture());
            ProviderCallRecord record = recordCaptor.getValue();
            assertThat(record.status()).isEqualTo("SKIPPED");
            assertThat(record.failureCode()).isEqualTo("COLLECTION_ALREADY_RUNNING");
            assertThat(record.sourceType()).isEqualTo("SCHEDULED");
            assertThat(record.triggeredBy()).isNull();
            assertThat(record.updateRegistry()).isFalse();
            verify(evidenceWriter, never()).writeRiskSuccess(any(), any());
            verify(evidenceWriter, never()).writeRegistryOutcome(any());
        } finally {
            guard.release(InProcessProviderRunGuard.Lane.RISK);
        }
    }

    @Test
    void riskProviderFailureWritesRegistryOutcomeCarryingMaskableExceptionMessage() {
        // A26-P3-36：风险采集失败时 failureMessage 改为携带真实异常 message（与天气侧对齐），
        // 由 ProviderObservabilityService 经 SensitiveDataMasker 脱敏后落库到 ProviderCallLog.failure_message 列。
        // 原实现完全吞掉真实异常，failureMessage 恒为固定 GAP，连续失败时运维无根因线索。
        // response.dataGaps 仍返回固定 GAP 保持用户友好。
        when(riskProvider.assess(9L)).thenThrow(new IllegalStateException("raw token=not-for-evidence"));

        ProviderRefreshResponse response = service.collect(9L, ProviderRunContext.manual(7L));

        assertThat(response.status).isEqualTo("FAILED");
        assertThat(response.providerKey).isEqualTo(InternalRiskRulesProvider.PROVIDER_KEY);
        assertThat(response.snapshotId).isNull();
        assertThat(response.dataGaps).containsExactly(RISK_FAILURE_GAP);
        ArgumentCaptor<ProviderCallRecord> recordCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(evidenceWriter, times(1)).writeRegistryOutcome(recordCaptor.capture());
        ProviderCallRecord record = recordCaptor.getValue();
        assertThat(record.status()).isEqualTo("FAILED");
        assertThat(record.failureCode()).isEqualTo("RISK_PROVIDER_FAILED");
        // service 层传入原始异常 message，脱敏由 ProviderObservabilityService 落库时完成。
        assertThat(record.failureMessage()).isEqualTo("raw token=not-for-evidence");
        assertThat(record.requestSummaryJson()).isEqualTo("{\"scopeType\":\"VENUE\",\"venueId\":9}");
        assertThat(record.updateRegistry()).isTrue();
        verify(evidenceWriter, never()).writeRiskSuccess(any(), any());
        verify(evidenceWriter, never()).writeCallOnly(any());
    }

    @Test
    void riskWriterFailurePropagatesWithoutBeingRelabeledAsProviderFailure() {
        RiskAssessmentSnapshot snapshot = riskSnapshot(504L, "SYSTEM", null);
        IllegalStateException writerFailure = new IllegalStateException("risk evidence write failed");
        when(riskProvider.assess(isNull())).thenReturn(snapshot);
        doThrow(writerFailure).when(evidenceWriter).writeRiskSuccess(any(RiskAssessmentSnapshot.class), any());

        assertThatThrownBy(() -> service.collect(null, ProviderRunContext.scheduled()))
            .isSameAs(writerFailure);

        verify(evidenceWriter).writeRiskSuccess(any(RiskAssessmentSnapshot.class), any());
        verify(evidenceWriter, never()).writeRegistryOutcome(any());
        verify(evidenceWriter, never()).writeCallOnly(any());
        assertThat(guard.tryAcquire(InProcessProviderRunGuard.Lane.RISK)).isTrue();
        guard.release(InProcessProviderRunGuard.Lane.RISK);
    }

    @Test
    void collectionBoundaryUsesOnlyTheReadProviderGuardAndEvidenceWriter() throws NoSuchMethodException {
        Constructor<?>[] constructors = RiskObservationCollectionService.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(
            InternalRiskRulesProvider.class, ProviderEvidenceWriter.class, InProcessProviderRunGuard.class);
        assertThat(RiskObservationCollectionService.class.getDeclaredFields()).extracting(Field::getType)
            .doesNotContain(RiskAssessmentSnapshotMapper.class, ProviderRegistryEntryMapper.class);
        assertThat(RiskObservationCollectionService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(RiskObservationCollectionService.class
            .getMethod("collect", Long.class, ProviderRunContext.class).getAnnotation(Transactional.class)).isNull();
    }

    private RiskAssessmentSnapshot riskSnapshot(Long id, String scopeType, Long scopeId) {
        RiskAssessmentSnapshot snapshot = new RiskAssessmentSnapshot();
        snapshot.id = id;
        snapshot.snapshotNo = "RK-" + id;
        snapshot.scopeType = scopeType;
        snapshot.scopeId = scopeId;
        snapshot.riskScore = new BigDecimal("62.50");
        snapshot.riskLevel = "MEDIUM";
        snapshot.failedLoginCount = 2;
        snapshot.paymentFailureCount = 3;
        snapshot.noticeFailureCount = 4;
        snapshot.openComplaintCount = 5;
        snapshot.severeComplaintCount = 6;
        snapshot.ruleSummaryJson = "{\"rawRule\":\"not-safe-for-call-summary\"}";
        snapshot.confidence = new BigDecimal("0.9000");
        return snapshot;
    }
}
