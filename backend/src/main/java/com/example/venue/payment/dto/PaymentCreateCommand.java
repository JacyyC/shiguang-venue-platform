package com.example.venue.payment.dto;

import java.math.BigDecimal;

public record PaymentCreateCommand(
    String paymentNo,
    String orderNo,
    BigDecimal amount,
    String currency,
    String idempotencyKey
) {
}
