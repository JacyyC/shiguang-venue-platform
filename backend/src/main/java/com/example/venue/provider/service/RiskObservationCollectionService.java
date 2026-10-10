package com.example.venue.provider.service;

import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRefreshResponse;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.entity.RiskAssessmentSnapshot;
import com.example.venue.provider.risk.InternalRiskRulesProvider;
import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

@Service
public class RiskObservationCollectionService {
    private static final Logger logger = LoggerFactory.getLogger(RiskObservationCollectionService.class);
    private static final String RUNNING_GAP = "采集器正在运行，本轮未执行";
    private static final String RISK_FAILURE_GAP = "内部风险规则采集失败，请检查Provider状态。";

    private final InternalRiskRulesProvider riskProvider;
    private final ProviderEvidenceWriter evidenceWriter;
    private final InProcessProviderRunGuard guard;

    public RiskObservationCollectionService(InternalRiskRulesProvider riskProvider,
                                            ProviderEvidenceWriter evidenceWriter,
                                            InProcessProviderRunGuard guard) {
        this.riskProvider = riskProvider;
        this.evidenceWriter = evidenceWriter;
        this.guard = guard;
    }

    public ProviderRefreshResponse collect(@Nullable Long venueId, ProviderRunContext context) {
        ProviderRunContext runContext = Objects.requireNonNull(context, "context");
        long started = System.nanoTime();
        if (!guard.tryAcquire(InProcessProviderRunGuard.Lane.RISK)) {
            evidenceWriter.writeCallOnly(overlapRecord(runContext, venueId, elapsedMs(started)));
            return response("SKIPPED", null, RUNNING_GAP);
        }
        try {
            RiskAssessmentSnapshot snapshot;
            try {
                snapshot = riskProvider.assess(venueId);
                snapshot.ruleSummaryJson = null;
            } catch (RuntimeException ex) {
                // A26-P3-36：与天气侧对齐——落库脱敏后的真实异常 message（由 ProviderObservabilityService
                // 经 SensitiveDataMasker 脱敏后写入 ProviderCallLog.failure_message 列），并打 warn 日志
                // 记录根因，避免连续失败时运维无任何线索。原实现完全吞掉真实异常，failureMessage 恒为固定 GAP。
                logger.warn("risk collection failed for venueId={}: {}", venueId, ex.getMessage(), ex);
                evidenceWriter.writeRegistryOutcome(
                    failureRecord(runContext, venueId, ex, elapsedMs(started)));
                return response("FAILED", null, RISK_FAILURE_GAP);
            }

            // A26-P3-35：successRecord 在 evidenceWriter.writeRiskSuccess 内部 insert 后用回填的 snapshot.id 构建，
            // response_summary_json 中的 snapshotId 与 snapshot_id 列保持一致。
            evidenceWriter.writeRiskSuccess(snapshot,
                id -> successRecord(runContext, venueId, snapshot, id, elapsedMs(started)));
            return response("SUCCESS", snapshot.id, null);
        } finally {
            guard.release(InProcessProviderRunGuard.Lane.RISK);
        }
    }

    private ProviderCallRecord successRecord(ProviderRunContext context,
                                             Long venueId,
                                             RiskAssessmentSnapshot snapshot,
                                             Long snapshotId,
                                             long durationMs) {
        return ProviderCallRecord.of(
            context,
            InternalRiskRulesProvider.PROVIDER_KEY,
            InternalRiskRulesProvider.PROVIDER_DOMAIN,
            InternalRiskRulesProvider.PROVIDER_ENV,
            InternalRiskRulesProvider.PROVIDER_NAME,
            null,
            InternalRiskRulesProvider.OPERATION,
            "SUCCESS",
            InternalRiskRulesProvider.CONFIG_VERSION,
            riskRequestSummary(venueId),
            riskResponseSummary(Objects.requireNonNull(snapshot, "risk snapshot"), snapshotId),
            null,
            null,
            durationMs,
            snapshot.snapshotNo,
            InternalRiskRulesProvider.SNAPSHOT_TYPE,
            snapshotId,
            snapshot.confidence,
            true);
    }

    private ProviderCallRecord failureRecord(ProviderRunContext context, Long venueId,
                                             RuntimeException failure, long durationMs) {
        return ProviderCallRecord.of(
            context,
            InternalRiskRulesProvider.PROVIDER_KEY,
            InternalRiskRulesProvider.PROVIDER_DOMAIN,
            InternalRiskRulesProvider.PROVIDER_ENV,
            InternalRiskRulesProvider.PROVIDER_NAME,
            null,
            InternalRiskRulesProvider.OPERATION,
            "FAILED",
            InternalRiskRulesProvider.CONFIG_VERSION,
            riskRequestSummary(venueId),
            null,
            "RISK_PROVIDER_FAILED",
            failure.getMessage(),
            durationMs,
            null,
            InternalRiskRulesProvider.SNAPSHOT_TYPE,
            null,
            null,
            true);
    }

    private ProviderCallRecord overlapRecord(ProviderRunContext context, Long venueId, long durationMs) {
        return ProviderCallRecord.of(
            context,
            InternalRiskRulesProvider.PROVIDER_KEY,
            InternalRiskRulesProvider.PROVIDER_DOMAIN,
            InternalRiskRulesProvider.PROVIDER_ENV,
            InternalRiskRulesProvider.PROVIDER_NAME,
            null,
            InternalRiskRulesProvider.OPERATION,
            "SKIPPED",
            InternalRiskRulesProvider.CONFIG_VERSION,
            riskRequestSummary(venueId),
            null,
            "COLLECTION_ALREADY_RUNNING",
            RUNNING_GAP,
            durationMs,
            null,
            InternalRiskRulesProvider.SNAPSHOT_TYPE,
            null,
            null,
            false);
    }

    private ProviderRefreshResponse response(String status, Long snapshotId, String dataGap) {
        ProviderRefreshResponse response = new ProviderRefreshResponse();
        response.status = status;
        response.providerKey = InternalRiskRulesProvider.PROVIDER_KEY;
        response.snapshotId = snapshotId;
        if (dataGap != null) {
            response.dataGaps.add(dataGap);
        }
        return response;
    }

    private String riskRequestSummary(Long venueId) {
        return venueId == null
            ? "{\"scopeType\":\"SYSTEM\"}"
            : "{\"scopeType\":\"VENUE\",\"venueId\":" + venueId + "}";
    }

    private String riskResponseSummary(RiskAssessmentSnapshot snapshot, Long snapshotId) {
        // A26-P3-35：snapshotId 由调用方在 insert 后传入回填值，不再读 snapshot.id（此时已回填但语义更清晰）。
        return "{\"snapshotId\":" + jsonValue(snapshotId)
            + ",\"riskScore\":" + jsonValue(snapshot.riskScore)
            + ",\"riskLevel\":" + quote(snapshot.riskLevel)
            + ",\"failedLoginCount\":" + jsonValue(snapshot.failedLoginCount)
            + ",\"paymentFailureCount\":" + jsonValue(snapshot.paymentFailureCount)
            + ",\"noticeFailureCount\":" + jsonValue(snapshot.noticeFailureCount)
            + ",\"openComplaintCount\":" + jsonValue(snapshot.openComplaintCount)
            + ",\"severeComplaintCount\":" + jsonValue(snapshot.severeComplaintCount)
            + "}";
    }

    private String jsonValue(BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }

    private String jsonValue(Number value) {
        return value == null ? "null" : value.toString();
    }

    private String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
