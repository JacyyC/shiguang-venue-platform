package com.example.venue.payment.dto;

import java.math.BigDecimal;

public class ProviderStatementItem {
    public String businessType;
    public String externalRef;
    public String transactionId;
    public String orderNo;
    public BigDecimal amount;
    public BigDecimal feeAmount;
    public BigDecimal netAmount;
    public String dataGap;

    public ProviderStatementItem() {
    }

    public static ProviderStatementItem payment(String externalRef, String orderNo, BigDecimal amount,
                                                BigDecimal feeAmount, BigDecimal netAmount) {
        ProviderStatementItem item = new ProviderStatementItem();
        item.businessType = "PAYMENT";
        item.externalRef = externalRef;
        item.transactionId = externalRef;
        item.orderNo = orderNo;
        item.amount = amount;
        item.feeAmount = feeAmount;
        item.netAmount = netAmount;
        return item;
    }

    public ImmutableSnapshot immutableSnapshot() {
        return ImmutableSnapshot.from(this);
    }

    public record ImmutableSnapshot(
        String businessType,
        String externalRef,
        String transactionId,
        String orderNo,
        BigDecimal amount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        String dataGap
    ) {
        public static ImmutableSnapshot from(ProviderStatementItem item) {
            if (item == null) {
                throw new IllegalArgumentException("statement item must not be null");
            }
            return new ImmutableSnapshot(
                item.businessType,
                item.externalRef,
                item.transactionId,
                item.orderNo,
                item.amount,
                item.feeAmount,
                item.netAmount,
                item.dataGap);
        }
    }
}
