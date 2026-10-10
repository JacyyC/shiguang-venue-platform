package com.example.venue.provider.service;

import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRefreshResponse;
import com.example.venue.provider.entity.OpsMetricSnapshot;
import com.example.venue.provider.entity.RiskAssessmentSnapshot;
import com.example.venue.provider.entity.WeatherObservationSnapshot;
import com.example.venue.provider.mapper.OpsMetricSnapshotMapper;
import com.example.venue.provider.mapper.RiskAssessmentSnapshotMapper;
import com.example.venue.provider.mapper.WeatherObservationSnapshotMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

@Service
public class ProviderEvidenceWriter {
    private final WeatherObservationSnapshotMapper weatherSnapshotMapper;
    private final RiskAssessmentSnapshotMapper riskSnapshotMapper;
    private final OpsMetricSnapshotMapper metricSnapshotMapper;
    private final ProviderObservabilityService observabilityService;

    public ProviderEvidenceWriter(WeatherObservationSnapshotMapper weatherSnapshotMapper,
                                  RiskAssessmentSnapshotMapper riskSnapshotMapper,
                                  OpsMetricSnapshotMapper metricSnapshotMapper,
                                  ProviderObservabilityService observabilityService) {
        this.weatherSnapshotMapper = weatherSnapshotMapper;
        this.riskSnapshotMapper = riskSnapshotMapper;
        this.metricSnapshotMapper = metricSnapshotMapper;
        this.observabilityService = observabilityService;
    }

    @Transactional
    public ProviderRefreshResponse writeWeatherSuccess(WeatherObservationSnapshot snapshot,
                                                        ProviderCallRecord outcome) {
        requireRegistryOutcome(outcome);
        weatherSnapshotMapper.insert(Objects.requireNonNull(snapshot, "snapshot"));
        observabilityService.recordCall(withSnapshotId(outcome, snapshot.id));
        return response(outcome.status(), outcome.providerKey(), snapshot.id);
    }

    @Transactional
    public void writeWeatherDetail(WeatherObservationSnapshot snapshot, ProviderCallRecord detail) {
        requireEvidenceOnly(detail);
        weatherSnapshotMapper.insert(Objects.requireNonNull(snapshot, "snapshot"));
        observabilityService.recordCall(withSnapshotId(detail, snapshot.id));
    }

    @Transactional
    public ProviderRefreshResponse writeRiskSuccess(RiskAssessmentSnapshot snapshot,
                                                    Function<Long, ProviderCallRecord> outcomeBuilder) {
        // A26-P3-35：response_summary_json 中的 snapshotId 必须在 insert 后用回填的 snapshot.id 构建，
        // 否则与同一行被 withSnapshotId 正确回填的 snapshot_id 列自相矛盾。
        // 调用方传入 outcomeBuilder 回调，由本方法在 insert 后用 snapshot.id 调用回调构建 outcome，
        // 确保 response_summary_json 和 snapshot_id 列都携带真实的 snapshotId。
        riskSnapshotMapper.insert(Objects.requireNonNull(snapshot, "snapshot"));
        ProviderCallRecord outcome = outcomeBuilder.apply(snapshot.id);
        requireRegistryOutcome(outcome);
        observabilityService.recordCall(outcome);
        return response(outcome.status(), outcome.providerKey(), snapshot.id);
    }

    @Transactional
    public void writeMetricsSuccess(List<OpsMetricSnapshot> snapshots, ProviderCallRecord outcome) {
        requireRegistryOutcome(outcome);
        Long firstSnapshotId = null;
        for (OpsMetricSnapshot snapshot : Objects.requireNonNull(snapshots, "snapshots")) {
            metricSnapshotMapper.insert(Objects.requireNonNull(snapshot, "snapshot"));
            if (firstSnapshotId == null) {
                firstSnapshotId = snapshot.id;
            }
        }
        observabilityService.recordCall(withSnapshotId(outcome, firstSnapshotId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeCallOnly(ProviderCallRecord evidence) {
        requireEvidenceOnly(evidence);
        observabilityService.recordCall(evidence);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeRegistryOutcome(ProviderCallRecord outcome) {
        requireRegistryOutcome(outcome);
        observabilityService.recordCall(outcome);
    }

    private void requireEvidenceOnly(ProviderCallRecord evidence) {
        if (Objects.requireNonNull(evidence, "evidence").updateRegistry()) {
            throw new IllegalArgumentException("Provider evidence-only record cannot updateRegistry");
        }
    }

    private void requireRegistryOutcome(ProviderCallRecord outcome) {
        if (!Objects.requireNonNull(outcome, "outcome").updateRegistry()) {
            throw new IllegalArgumentException("Provider registry-impact outcome must updateRegistry");
        }
    }

    private ProviderCallRecord withSnapshotId(ProviderCallRecord record, Long snapshotId) {
        return new ProviderCallRecord(
            record.sourceType(), record.triggeredBy(), record.providerKey(), record.providerDomain(),
            record.providerEnv(), record.providerName(), record.idempotencyKey(), record.operationType(),
            record.status(), record.configVersion(), record.requestSummaryJson(), record.responseSummaryJson(),
            record.failureCode(), record.failureMessage(), record.durationMs(), record.externalRef(),
            record.snapshotType(), snapshotId, record.confidence(), record.updateRegistry());
    }

    private ProviderRefreshResponse response(String status, String providerKey, Long snapshotId) {
        ProviderRefreshResponse response = new ProviderRefreshResponse();
        response.status = status;
        response.providerKey = providerKey;
        response.snapshotId = snapshotId;
        return response;
    }
}
