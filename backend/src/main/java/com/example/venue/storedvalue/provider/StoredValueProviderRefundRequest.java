package com.example.venue.storedvalue.provider;

import java.math.BigDecimal;

public record StoredValueProviderRefundRequest(
    String rechargeNo,
    String refundNo,
    String providerBusinessNo,
    BigDecimal amount,
    String currency,
    String policyVersion
) {
}
