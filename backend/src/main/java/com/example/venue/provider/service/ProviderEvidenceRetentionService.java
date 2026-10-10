package com.example.venue.provider.service;

import com.example.venue.provider.config.ProviderObservabilityProperties;
import com.example.venue.provider.mapper.OpsMetricSnapshotMapper;
import com.example.venue.provider.mapper.ProviderCallLogMapper;
import com.example.venue.provider.mapper.RiskAssessmentSnapshotMapper;
import com.example.venue.provider.mapper.WeatherObservationSnapshotMapper;
import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class ProviderEvidenceRetentionService {
    private static final Logger log = LoggerFactory.getLogger(ProviderEvidenceRetentionService.class);

    private final ProviderObservabilityProperties properties;
    private final Clock clock;
    private final InProcessProviderRunGuard runGuard;
    private final ProviderCallLogMapper callLogMapper;
    private final WeatherObservationSnapshotMapper weatherSnapshotMapper;
    private final RiskAssessmentSnapshotMapper riskSnapshotMapper;
    private final OpsMetricSnapshotMapper metricSnapshotMapper;
    private final TransactionTemplate batchTransactionTemplate;

    public ProviderEvidenceRetentionService(ProviderObservabilityProperties properties,
                                            Clock clock,
                                            InProcessProviderRunGuard runGuard,
                                            ProviderCallLogMapper callLogMapper,
                                            WeatherObservationSnapshotMapper weatherSnapshotMapper,
                                            RiskAssessmentSnapshotMapper riskSnapshotMapper,
                                            OpsMetricSnapshotMapper metricSnapshotMapper,
                                            PlatformTransactionManager transactionManager) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runGuard = Objects.requireNonNull(runGuard, "runGuard");
        this.callLogMapper = Objects.requireNonNull(callLogMapper, "callLogMapper");
        this.weatherSnapshotMapper = Objects.requireNonNull(weatherSnapshotMapper, "weatherSnapshotMapper");
        this.riskSnapshotMapper = Objects.requireNonNull(riskSnapshotMapper, "riskSnapshotMapper");
        this.metricSnapshotMapper = Objects.requireNonNull(metricSnapshotMapper, "metricSnapshotMapper");
        this.batchTransactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.batchTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void runOnce() {
        if (!runGuard.tryAcquire(InProcessProviderRunGuard.Lane.RETENTION)) {
            log.info("Provider evidence retention overlap lane={}", InProcessProviderRunGuard.Lane.RETENTION);
            return;
        }

        try {
            ProviderObservabilityProperties.RetentionProperties retention = properties.getRetention();
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime callLogCutoff = now.minusDays(retention.getCallLogDays());
            LocalDateTime snapshotCutoff = now.minusDays(retention.getSnapshotDays());

            if (!process("provider_call_log", callLogCutoff, retention, callLogMapper::selectRetentionCandidateIds,
                callLogMapper::physicalDeleteIds)) {
                return;
            }
            if (!process("weather_observation_snapshot", snapshotCutoff, retention,
                weatherSnapshotMapper::selectRetentionCandidateIds, weatherSnapshotMapper::physicalDeleteIds)) {
                return;
            }
            if (!process("risk_assessment_snapshot", snapshotCutoff, retention,
                riskSnapshotMapper::selectRetentionCandidateIds, riskSnapshotMapper::physicalDeleteIds)) {
                return;
            }
            process("ops_metric_snapshot", snapshotCutoff, retention, metricSnapshotMapper::selectRetentionCandidateIds,
                metricSnapshotMapper::physicalDeleteIds);
        } finally {
            runGuard.release(InProcessProviderRunGuard.Lane.RETENTION);
        }
    }

    private boolean process(String evidenceType,
                            LocalDateTime cutoff,
                            ProviderObservabilityProperties.RetentionProperties retention,
                            CandidateSelector candidateSelector,
                            PhysicalDeleter physicalDeleter) {
        for (int batchNumber = 1; batchNumber <= retention.getMaxBatchesPerRun(); batchNumber++) {
            try {
                List<Long> candidateIds = candidateSelector.select(cutoff, retention.getBatchSize());
                if (candidateIds == null || candidateIds.isEmpty()) {
                    return true;
                }
                List<Long> batchIds = List.copyOf(candidateIds);
                batchTransactionTemplate.executeWithoutResult(status -> physicalDeleter.delete(batchIds));
            } catch (RuntimeException ex) {
                log.error("Provider evidence retention failed evidenceType={} cutoff={} batch={} exceptionClass={}",
                    evidenceType, cutoff, batchNumber, ex.getClass().getName());
                return false;
            }
        }
        return true;
    }

    @FunctionalInterface
    private interface CandidateSelector {
        List<Long> select(LocalDateTime cutoff, int limit);
    }

    @FunctionalInterface
    private interface PhysicalDeleter {
        int delete(List<Long> ids);
    }
}
