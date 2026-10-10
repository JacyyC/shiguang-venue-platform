package com.example.venue.storedvalue.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class StoredValueCommands {
    private StoredValueCommands() {
    }

    public record CreateRecharge(
        Long userId,
        Long productId,
        BigDecimal principalAmount,
        String currency,
        String clientRequestId,
        String providerKey,
        String sourceType
    ) {
    }

    public record Callback(
        String providerKey,
        String providerEnv,
        String providerName,
        String configVersion,
        String rechargeNo,
        String externalEventId,
        String externalRef,
        String merchantIdentifier,
        String status,
        String currency,
        BigDecimal amount,
        String signatureDigest,
        String minimalSummary,
        String requestId
    ) {
        public Callback(String providerKey, String providerEnv, String providerName, String configVersion,
                        String rechargeNo, String externalEventId, String externalRef,
                        String merchantIdentifier, String status, String currency, BigDecimal amount,
                        String signatureDigest, String minimalSummary) {
            this(providerKey, providerEnv, providerName, configVersion, rechargeNo, externalEventId,
                externalRef, merchantIdentifier, status, currency, amount, signatureDigest, minimalSummary, null);
        }
    }

    public record Refund(String rechargeNo, Long operatorId, Long reviewerId, String reason) {
    }

    public record Dispute(String rechargeNo, String providerCaseId, BigDecimal amount,
                          Long operatorId, String reason) {
    }

    public record OrderPayment(Long orderId, Long userId, Long productId,
                               String idempotencyKey, String sourceType) {
    }

    public record OrderRefund(Long orderId, Long refundRecordId, String idempotencyKey,
                              String reason) {
    }

    public record Ledger(
        Long issuerId,
        Long productId,
        Long accountId,
        String currency,
        String transactionType,
        String sourceType,
        String sourceId,
        String businessKey,
        String policyVersion,
        String reason,
        Long operatorId,
        String systemSource,
        LocalDateTime occurredAt,
        List<LedgerLine> lines
    ) {
    }

    public record LedgerLine(Long accountId, Long bonusBatchId, String bucket,
                             String direction, BigDecimal amount) {
    }
}
