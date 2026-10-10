package com.example.venue.storedvalue.provider;

import java.math.BigDecimal;

public record StoredValueProviderVerification(
    boolean accepted,
    String status,
    String failureCode,
    BigDecimal amount,
    String currency,
    String externalRef,
    String responseDigest,
    String responseSummary
) {
}
