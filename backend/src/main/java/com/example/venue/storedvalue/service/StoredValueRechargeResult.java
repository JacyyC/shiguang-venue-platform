package com.example.venue.storedvalue.service;

import java.math.BigDecimal;

public record StoredValueRechargeResult(
    Long id,
    String rechargeNo,
    String status,
    BigDecimal principalAmount,
    BigDecimal expectedBonusAmount,
    BigDecimal actualBonusAmount,
    String providerKey,
    String providerEnv,
    String providerName,
    String providerBusinessNo,
    String externalRef,
    String failureCode
) {
}
