package com.example.venue.notice.provider;

public record NotificationDeliveryResult(
    String status,
    String providerRequestId,
    boolean retryable,
    String failureCode,
    String failureMessage,
    String responseSummaryJson
) {
    public static final String ACCEPTED = "ACCEPTED";
    public static final String FAILED_RETRYABLE = "FAILED_RETRYABLE";
    public static final String FAILED_PERMANENT = "FAILED_PERMANENT";
}
