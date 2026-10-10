package com.example.venue.storedvalue.service;

import java.math.BigDecimal;

public record StoredValueOrderPaymentResult(
    Long paymentId,
    String paymentNo,
    Long orderId,
    String status,
    BigDecimal amount,
    BigDecimal principalAmount,
    BigDecimal bonusAmount,
    boolean replayed
) {
}
