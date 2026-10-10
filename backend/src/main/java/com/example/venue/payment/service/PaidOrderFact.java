package com.example.venue.payment.service;

import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.entity.PaymentRecord;
import com.example.venue.payment.entity.PaymentAttempt;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The minimum trusted fact needed after a payment tool has already succeeded.
 * Provider and stored-value orchestration stay outside this contract.
 */
public record PaidOrderFact(
    BizOrder order,
    PaymentAttempt attempt,
    PaymentRecord payment,
    String paymentToolType,
    String paymentFactId,
    String channel,
    String payType,
    String sourceType,
    BigDecimal paidAmount,
    BigDecimal feeAmount,
    BigDecimal netAmount,
    LocalDateTime paidTime
) {
}
