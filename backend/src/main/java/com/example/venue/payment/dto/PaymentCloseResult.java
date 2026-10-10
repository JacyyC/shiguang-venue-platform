package com.example.venue.payment.dto;

/**
 * A masked provider response for an attempted payment close operation.
 */
public record PaymentCloseResult(
    String status,
    String externalRef,
    String requestSummaryJson,
    String responseSummaryJson
) {
}
