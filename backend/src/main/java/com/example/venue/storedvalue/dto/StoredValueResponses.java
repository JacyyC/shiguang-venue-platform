package com.example.venue.storedvalue.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class StoredValueResponses {
    private StoredValueResponses() {
    }

    public record ProductResponse(
        Long id,
        String productCode,
        String productName,
        String issuerCode,
        String currency,
        String environment,
        String status,
        boolean rechargeEnabled,
        boolean spendEnabled,
        String policyVersion,
        BigDecimal minimumRecharge,
        BigDecimal maximumRecharge,
        BigDecimal dailyRechargeLimit,
        BigDecimal principalBalanceLimit,
        BigDecimal totalBalanceLimit,
        BigDecimal bonusRatePercent,
        BigDecimal bonusPerRechargeLimit,
        BigDecimal monthlyBonusLimit,
        int bonusValidDays,
        int refundCompensationValidDays
    ) {
    }

    public record AccountResponse(
        Long accountId,
        Long userId,
        Long productId,
        String status,
        String currency,
        BigDecimal principalAvailable,
        BigDecimal principalFrozen,
        BigDecimal bonusAvailable,
        BigDecimal bonusFrozen,
        BigDecimal availableTotal,
        BigDecimal totalBalance,
        long version
    ) {
    }

    public record BonusBatchResponse(
        Long id,
        Long rechargeId,
        BigDecimal originalAmount,
        BigDecimal availableAmount,
        BigDecimal frozenAmount,
        LocalDateTime issuedAt,
        LocalDateTime expiresAt,
        String status
    ) {
    }

    public record SelfResponse(
        ProductResponse product,
        List<ProductResponse> products,
        AccountResponse account,
        boolean identityVerified,
        String eligibilityStatus,
        String eligibilityMessage
    ) {
    }

    public record RechargeResponse(
        Long id,
        String rechargeNo,
        Long productId,
        String status,
        BigDecimal principalAmount,
        BigDecimal expectedBonusAmount,
        BigDecimal actualBonusAmount,
        String currency,
        String providerKey,
        String providerEnv,
        String providerName,
        String providerBusinessNo,
        String externalRef,
        String failureCode,
        LocalDateTime createTime,
        LocalDateTime updateTime
    ) {
    }

    public record LedgerResponse(
        Long transactionId,
        String transactionType,
        String sourceType,
        String sourceId,
        String bucket,
        String direction,
        BigDecimal amount,
        String currency,
        String reason,
        LocalDateTime occurredAt
    ) {
    }

    public record OrderPaymentOptionResponse(
        Long orderId,
        BigDecimal amount,
        String currency,
        boolean available,
        String reason,
        ProductResponse product,
        AccountResponse account,
        boolean identityVerified
    ) {
    }

    public record OrderPaymentResponse(
        Long paymentId,
        String paymentNo,
        Long orderId,
        String status,
        BigDecimal amount,
        BigDecimal principalAmount,
        BigDecimal bonusAmount,
        boolean replayed
    ) {
    }

    public record OrderRefundResponse(
        Long refundId,
        String refundNo,
        Long orderId,
        String status,
        BigDecimal amount,
        BigDecimal principalAmount,
        BigDecimal bonusAmount,
        BigDecimal compensationAmount,
        boolean replayed
    ) {
    }

    public record OrderStoredValueStatusResponse(
        OrderPaymentResponse payment,
        OrderRefundResponse refund
    ) {
    }

    /** B5-H11：财务侧原桶分摊只读摘要，仅受控引用与金额，不含完整个人信息或内部主键。 */
    public record OrderStoredFactPaymentResponse(
        String paymentNoSuffix,
        String status,
        BigDecimal amount,
        BigDecimal principalAmount,
        BigDecimal bonusAmount
    ) {
    }

    public record OrderStoredFactRefundResponse(
        String refundNoSuffix,
        String status,
        BigDecimal amount,
        BigDecimal principalAmount,
        BigDecimal bonusAmount,
        BigDecimal compensationAmount
    ) {
    }

    public record OrderStoredFactsResponse(
        String orderNo,
        OrderStoredFactPaymentResponse payment,
        OrderStoredFactRefundResponse refund
    ) {
    }

    public record FinanceAccountResponse(
        String accountRef,
        String userRef,
        String productCode,
        String environment,
        String status,
        BigDecimal principalAvailable,
        BigDecimal principalFrozen,
        BigDecimal bonusAvailable,
        BigDecimal bonusFrozen,
        BigDecimal availableTotal,
        BigDecimal totalBalance,
        LocalDateTime updateTime
    ) {
    }

    public record FinanceRechargeResponse(
        String rechargeRef,
        String rechargeNoSuffix,
        String userRef,
        String productCode,
        String status,
        BigDecimal principalAmount,
        BigDecimal expectedBonusAmount,
        BigDecimal actualBonusAmount,
        String currency,
        String providerKey,
        String providerEnv,
        String providerName,
        String externalRefSummary,
        String failureCode,
        LocalDateTime createTime,
        LocalDateTime updateTime
    ) {
    }

    public record FinanceLedgerResponse(
        String transactionRef,
        String accountRef,
        String userRef,
        String transactionType,
        String sourceType,
        String sourceRef,
        String bucket,
        String direction,
        BigDecimal amount,
        String currency,
        String reason,
        LocalDateTime occurredAt
    ) {
    }

    public record ReconciliationRunResponse(
        String runRef,
        LocalDate reconcileDate,
        String providerEnv,
        String providerName,
        String scopeType,
        String status,
        int differenceCount,
        LocalDateTime completedAt
    ) {
    }

    public record ReconciliationDifferenceResponse(
        String differenceRef,
        String runRef,
        String differenceType,
        BigDecimal providerAmount,
        BigDecimal localAmount,
        String currency,
        String status,
        String reason,
        String ledgerTransactionRef,
        LocalDateTime createTime
    ) {
    }

    public record FinanceSummaryResponse(
        long accountCount,
        long pendingRechargeCount,
        long openDifferenceCount,
        BigDecimal availablePrincipal,
        BigDecimal availableBonus
    ) {
    }

    public record ProviderResponse(
        String providerKey,
        String providerEnv,
        String providerName,
        String configVersion,
        boolean enabled
    ) {
    }

    public record ApprovalListResponse(
        String approvalNo,
        String actionType,
        String targetType,
        String targetRef,
        String requesterRef,
        boolean requesterIsCurrentActor,
        BigDecimal amount,
        String currency,
        String reviewPermission,
        String executePermission,
        String status,
        LocalDateTime expiresAt,
        LocalDateTime createTime,
        LocalDateTime updateTime
    ) {
    }

    public record ApprovalDetailResponse(
        String approvalNo,
        String actionType,
        String targetType,
        String targetRef,
        String requesterRef,
        String reviewerRef,
        String executorRef,
        boolean requesterIsCurrentActor,
        BigDecimal amount,
        String currency,
        String reason,
        String reviewPermission,
        String executePermission,
        String status,
        LocalDateTime expiresAt,
        String policyVersion,
        long version,
        LocalDateTime reviewedAt,
        String reviewReason,
        LocalDateTime executedAt,
        String executionResult,
        LocalDateTime createTime,
        LocalDateTime updateTime
    ) {
    }

    public record ActionResponse(String status, String message) {
    }
}
