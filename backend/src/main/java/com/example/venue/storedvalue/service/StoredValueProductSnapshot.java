package com.example.venue.storedvalue.service;

import com.example.venue.storedvalue.domain.StoredValuePolicy;

public record StoredValueProductSnapshot(
    Long id,
    Long issuerId,
    String issuerCode,
    String issuerStatus,
    String productCode,
    String currency,
    String environment,
    String status,
    boolean rechargeEnabled,
    boolean storedValueSpendEnabled,
    Long policyId,
    StoredValuePolicy policy
) {
    public boolean enabled() {
        return "ENABLED".equals(status) && "ENABLED".equals(issuerStatus);
    }
}
