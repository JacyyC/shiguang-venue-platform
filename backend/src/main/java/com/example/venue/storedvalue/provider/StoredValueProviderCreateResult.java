package com.example.venue.storedvalue.provider;

public record StoredValueProviderCreateResult(
    String status,
    String providerBusinessNo,
    String externalRef,
    String requestDigest,
    String requestSummary,
    String responseDigest,
    String responseSummary,
    String failureCode
) {
    public boolean failed() {
        return "FAILED".equals(status);
    }
}
