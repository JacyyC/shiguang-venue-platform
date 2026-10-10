package com.example.venue.payment.dto;

import java.math.BigDecimal;

public class AdjustReconciliationRequest {
    public BigDecimal adjustmentAmount;
    public String adjustmentReason;

    public AdjustReconciliationRequest() {
    }

    public AdjustReconciliationRequest(BigDecimal adjustmentAmount, String adjustmentReason) {
        this.adjustmentAmount = adjustmentAmount;
        this.adjustmentReason = adjustmentReason;
    }
}
