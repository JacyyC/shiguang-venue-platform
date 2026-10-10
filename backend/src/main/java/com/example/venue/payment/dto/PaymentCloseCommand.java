package com.example.venue.payment.dto;

/**
 * Stable, provider-neutral facts used to close an unpaid payment attempt.
 */
public record PaymentCloseCommand(
    String paymentNo,
    String orderNo,
    String idempotencyKey
) {
}
