package com.example.venue.payment.dto;

public enum PaymentAttemptStatus {
    CREATED,
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELED,
    TIMEOUT,
    CLOSED
}
