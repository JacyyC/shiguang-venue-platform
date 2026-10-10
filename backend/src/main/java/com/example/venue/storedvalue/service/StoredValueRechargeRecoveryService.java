package com.example.venue.storedvalue.service;

import com.example.venue.storedvalue.domain.StoredValueRechargeStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The scheduled and operator-triggered paths share one in-process guard. The
 * database claim remains the correctness boundary for multiple instances.
 */
@Service
public class StoredValueRechargeRecoveryService {
    private final StoredValueRechargePersistenceService persistence;
    private final StoredValueRechargeService rechargeService;
    private final ReentrantLock recoveryGuard = new ReentrantLock();

    public StoredValueRechargeRecoveryService(StoredValueRechargePersistenceService persistence,
                                              StoredValueRechargeService rechargeService) {
        this.persistence = persistence;
        this.rechargeService = rechargeService;
    }

    @Scheduled(fixedDelayString = "${app.stored-value.recharge-recovery-fixed-delay-ms:60000}",
               initialDelayString = "${app.stored-value.recharge-recovery-initial-delay-ms:60000}")
    public void scheduledRecover() {
        recoverPendingPostings(50);
    }

    public RecoverySummary recoverPendingPostings(int limit) {
        if (!recoveryGuard.tryLock()) {
            return new RecoverySummary(0, 0, 0, 1);
        }
        try {
            int scanned = 0;
            int succeeded = 0;
            int manualReview = 0;
            int deferred = 0;
            for (String rechargeNo : persistence.findRecoverablePostingNos(limit, LocalDateTime.now())) {
                scanned++;
                try {
                    StoredValueRechargeResult result = rechargeService.postPending(
                        rechargeNo, null, "SCHEDULED_RECOVERY", null, "自动恢复渠道成功待入账", false);
                    if (StoredValueRechargeStatus.SUCCEEDED.matches(result.status())) {
                        succeeded++;
                    } else if (StoredValueRechargeStatus.MANUAL_REVIEW.matches(result.status())) {
                        manualReview++;
                    } else {
                        deferred++;
                    }
                } catch (RuntimeException exception) {
                    deferred++;
                }
            }
            return new RecoverySummary(scanned, succeeded, manualReview, deferred);
        } finally {
            recoveryGuard.unlock();
        }
    }

    public StoredValueRechargeResult recoverManually(String rechargeNo, Long operatorId, String reason) {
        recoveryGuard.lock();
        try {
            return rechargeService.recoverPosting(rechargeNo, operatorId, reason);
        } finally {
            recoveryGuard.unlock();
        }
    }

    public record RecoverySummary(int scanned, int succeeded, int manualReview, int deferred) {
    }
}
