package com.example.venue.payment.dto;

public enum PaymentCallbackStatus {
    RECEIVED,
    SUCCESS,
    IGNORED_DUPLICATE,
    SIGNATURE_INVALID,
    AMOUNT_MISMATCH,
    ORDER_STATE_INVALID,
    FAILED
}
