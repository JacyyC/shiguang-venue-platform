package com.example.venue.payment.dto;

import java.math.BigDecimal;

public record PaymentCallbackCommand(
    String paymentNo,
    String orderNo,
    String externalRef,
    BigDecimal amount,
    DevPaymentScenario scenario
) {
    public static PaymentCallbackCommand devScenario(String paymentNo, String orderNo, String externalRef,
                                                     BigDecimal amount, DevPaymentScenario scenario) {
        return new PaymentCallbackCommand(paymentNo, orderNo, externalRef, amount, scenario);
    }
}
