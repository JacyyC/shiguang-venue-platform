package com.example.venue.payment.dto;

public class FailureReasonMetric {
    public String failureCode;
    public String failureMessage;
    public long count;

    public FailureReasonMetric() {
    }

    public FailureReasonMetric(String failureCode, String failureMessage, long count) {
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.count = count;
    }
}
