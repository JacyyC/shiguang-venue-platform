package com.example.venue.payment.dto;

import java.math.BigDecimal;

public record PaymentCallbackResult(
    String status,
    String externalRef,
    BigDecimal paidAmount,
    BigDecimal feeAmount,
    BigDecimal netAmount,
    String requestSummaryJson,
    String responseSummaryJson
) {
}
