package com.example.venue.promotion.dto;

import java.math.BigDecimal;

public class CouponPricingView {
    public Long userCouponId;
    public Long templateId;
    public String templateCode;
    public String templateName;
    public String couponType;
    public BigDecimal discountValue;
    public BigDecimal thresholdAmount;
    public Boolean allowActivityStack;
    public BigDecimal discountAmount;
    public String unavailableReason;
}
