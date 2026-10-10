package com.example.venue.storedvalue.provider;

public record StoredValueProviderRefundResult(
    String status,
    String externalRef,
    String responseDigest,
    String responseSummary,
    String failureCode
) {
    public boolean succeeded() {
        return "SUCCEEDED".equals(status);
    }
}
