package com.example.venue.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PaymentIntentResponse {
    public String paymentNo;
    public Long orderId;
    public String orderNo;
    public String providerEnv;
    public String providerName;
    public String configVersion;
    public String status;
    public BigDecimal payableAmount;
    public BigDecimal paidAmount;
    public BigDecimal feeAmount;
    public BigDecimal netAmount;
    public String currency;
    public String externalRef;
    public LocalDateTime expireTime;
    public String requestSummaryJson;
    public String responseSummaryJson;
    public String providerKey;
    public PaymentAction paymentAction;
}
