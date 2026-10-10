package com.example.venue.payment.dto;

import java.math.BigDecimal;

public record VerifiedPaymentCallback(
    String paymentNo,
    String externalRef,
    String providerTradeStatus,
    boolean settleable,
    BigDecimal paidAmount,
    String currency,
    String requestSummaryJson,
    String responseSummaryJson
) {
}
