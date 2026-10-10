package com.example.venue.payment.dto;

public enum PaymentProviderOperationType {
    CREATE_PAYMENT,
    QUERY_PAYMENT,
    CLOSE_PAYMENT,
    HANDLE_CALLBACK,
    REQUEST_REFUND,
    QUERY_REFUND,
    STATEMENT
}
