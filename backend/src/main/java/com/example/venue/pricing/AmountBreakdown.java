package com.example.venue.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record AmountBreakdown(
    BigDecimal originalAmount,
    BigDecimal memberDiscountAmount,
    BigDecimal couponDiscountAmount,
    BigDecimal activityDiscountAmount,
    BigDecimal payableAmount,
    BigDecimal paidAmount,
    BigDecimal feeAmount,
    BigDecimal netAmount
) {
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    public AmountBreakdown {
        originalAmount = normalize(originalAmount);
        memberDiscountAmount = normalize(memberDiscountAmount);
        couponDiscountAmount = normalize(couponDiscountAmount);
        activityDiscountAmount = normalize(activityDiscountAmount);
        payableAmount = normalize(payableAmount);
        paidAmount = normalize(paidAmount);
        feeAmount = normalize(feeAmount);
        netAmount = normalize(netAmount);
    }

    public static AmountBreakdown base(BigDecimal originalAmount) {
        BigDecimal normalizedOriginalAmount = normalize(originalAmount);
        return new AmountBreakdown(
            normalizedOriginalAmount,
            ZERO_AMOUNT,
            ZERO_AMOUNT,
            ZERO_AMOUNT,
            normalizedOriginalAmount,
            ZERO_AMOUNT,
            ZERO_AMOUNT,
            ZERO_AMOUNT
        );
    }

    public BigDecimal discountAmount() {
        return normalize(memberDiscountAmount
            .add(couponDiscountAmount)
            .add(activityDiscountAmount));
    }

    private static BigDecimal normalize(BigDecimal amount) {
        if (amount == null) {
            return ZERO_AMOUNT;
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
