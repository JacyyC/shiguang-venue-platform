package com.example.venue.storedvalue.service;

import java.math.BigDecimal;

public record StoredValueOrderRefundResult(
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
