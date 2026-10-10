package com.example.venue.payment.dto;

import java.math.BigDecimal;

public record PaymentCreateResult(
    String providerEnv,
    String providerName,
    String configVersion,
    String status,
    BigDecimal payableAmount,
    BigDecimal feeAmount,
    BigDecimal netAmount,
    String currency,
    String requestSummaryJson,
    String responseSummaryJson,
    PaymentAction paymentAction
) {
}
