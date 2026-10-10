package com.example.venue.provider.dto;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record ProviderCallRecord(
    String sourceType,
    Long triggeredBy,
    String providerKey,
    String providerDomain,
    String providerEnv,
    String providerName,
    String idempotencyKey,
    String operationType,
    String status,
    String configVersion,
    String requestSummaryJson,
    String responseSummaryJson,
    String failureCode,
    String failureMessage,
    Long durationMs,
    String externalRef,
    String snapshotType,
    Long snapshotId,
    BigDecimal confidence,
    boolean updateRegistry
) {
    private static final Set<String> ALLOWED_STATUSES = Set.of(
        "SUCCESS", "FAILED", "TIMEOUT", "SKIPPED", "DEGRADED"
    );

    public ProviderCallRecord {
        ProviderRunContext source = new ProviderRunContext(sourceType, triggeredBy);
        sourceType = source.sourceType();
        triggeredBy = source.triggeredBy();
        status = normalizeStatus(status);
        if ("SKIPPED".equals(status) && updateRegistry) {
            throw new IllegalArgumentException("SKIPPED records cannot updateRegistry");
        }
    }

    public static ProviderCallRecord of(ProviderRunContext source,
                                        String providerKey,
                                        String providerDomain,
                                        String providerEnv,
                                        String providerName,
                                        String idempotencyKey,
                                        String operationType,
                                        String status,
                                        String configVersion,
                                        String requestSummaryJson,
                                        String responseSummaryJson,
                                        String failureCode,
                                        String failureMessage,
                                        Long durationMs,
                                        String externalRef,
                                        String snapshotType,
                                        Long snapshotId,
                                        BigDecimal confidence,
                                        boolean updateRegistry) {
        ProviderRunContext validatedSource = Objects.requireNonNull(source, "source");
        return new ProviderCallRecord(
            validatedSource.sourceType(), validatedSource.triggeredBy(), providerKey, providerDomain, providerEnv,
            providerName, idempotencyKey, operationType, status, configVersion, requestSummaryJson,
            responseSummaryJson, failureCode, failureMessage, durationMs, externalRef, snapshotType, snapshotId,
            confidence, updateRegistry);
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Provider call status must be one of " + ALLOWED_STATUSES);
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("Provider call status must be one of " + ALLOWED_STATUSES);
        }
        return normalized;
    }
}
