package com.example.venue.payment.dto;

public enum DevPaymentScenario {
    SUCCESS,
    FAILED,
    CANCELED,
    TIMEOUT,
    DUPLICATE,
    AMOUNT_MISMATCH,
    SIGNATURE_INVALID
}
