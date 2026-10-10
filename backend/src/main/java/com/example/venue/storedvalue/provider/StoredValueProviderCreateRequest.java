package com.example.venue.storedvalue.provider;

import java.math.BigDecimal;

public record StoredValueProviderCreateRequest(
    String rechargeNo,
    String providerBusinessNo,
    BigDecimal amount,
    String currency,
    String policyVersion
) {
}
