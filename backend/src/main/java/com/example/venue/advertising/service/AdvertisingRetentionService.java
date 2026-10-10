package com.example.venue.advertising.service;

import com.example.venue.advertising.config.AdvertisingRetentionProperties;
import com.example.venue.advertising.mapper.AdvertisingRetentionMapper;
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
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AdvertisingRetentionService {
    static final int RAW_FACT_RETENTION_DAYS = 90;
    static final int ATTRIBUTION_RETENTION_DAYS = 180;
    static final int AUDIT_RETENTION_DAYS = 730;

    private static final Logger log = LoggerFactory.getLogger(AdvertisingRetentionService.class);

    private final AdvertisingRetentionProperties properties;
    private final AdvertisingRetentionMapper retentionMapper;
    private final Clock clock;
    private final TransactionTemplate batchTransactionTemplate;
    private final AtomicBoolean running = new AtomicBoolean();

    public AdvertisingRetentionService(AdvertisingRetentionProperties properties,
                                       AdvertisingRetentionMapper retentionMapper,
                                       PlatformTransactionManager transactionManager,
                                       Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.retentionMapper = Objects.requireNonNull(retentionMapper, "retentionMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.batchTransactionTemplate = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager"));
        this.batchTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void runOnce() {
        if (!running.compareAndSet(false, true)) {
            log.info("广告事实保留任务重叠，跳过本轮");
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            if (!process("advertising_event", now.minusDays(RAW_FACT_RETENTION_DAYS),
                retentionMapper::selectExpiredEventIds, retentionMapper::deleteEventIds)) {
                return;
            }
            if (!process("advertising_decision", now.minusDays(RAW_FACT_RETENTION_DAYS),
                retentionMapper::selectExpiredDecisionIds, retentionMapper::deleteDecisionIds)) {
                return;
            }
            if (!process("advertising_conversion_callback", now.minusDays(ATTRIBUTION_RETENTION_DAYS),
                retentionMapper::selectExpiredProcessedCallbackIds, retentionMapper::deleteCallbackIds)) {
                return;
            }
            if (!process("advertising_attribution", now.minusDays(ATTRIBUTION_RETENTION_DAYS),
                retentionMapper::selectExpiredAttributionIds, retentionMapper::deleteAttributionIds)) {
                return;
            }
            process("advertising_audit_event", now.minusDays(AUDIT_RETENTION_DAYS),
                retentionMapper::selectExpiredAuditIds, retentionMapper::deleteAuditIds);
        } finally {
            running.set(false);
        }
    }

    private boolean process(String factType,
                            LocalDateTime cutoff,
                            CandidateSelector selector,
                            CandidateDeleter deleter) {
        for (int batchNumber = 1; batchNumber <= properties.getMaxBatchesPerRun(); batchNumber++) {
            try {
                List<Long> ids = selector.select(cutoff, properties.getBatchSize());
                if (ids == null || ids.isEmpty()) {
                    return true;
                }
                List<Long> batch = List.copyOf(ids);
                batchTransactionTemplate.executeWithoutResult(status -> deleter.delete(batch));
            } catch (RuntimeException exception) {
                log.error("广告事实保留失败 factType={} cutoff={} batch={} exceptionClass={}",
                    factType, cutoff, batchNumber, exception.getClass().getName());
                return false;
            }
        }
        return true;
    }

    @FunctionalInterface
    interface CandidateSelector {
        List<Long> select(LocalDateTime cutoff, int limit);
    }

    @FunctionalInterface
    interface CandidateDeleter {
        int delete(List<Long> ids);
    }
}
