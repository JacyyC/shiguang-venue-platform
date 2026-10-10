package com.example.venue.member.dto;

import java.math.BigDecimal;

public class PricingPreviewResponse {
    public BigDecimal originalAmount;
    public BigDecimal memberDiscountAmount;
    public BigDecimal couponDiscountAmount;
    public BigDecimal activityDiscountAmount;
    public BigDecimal payableAmount;
    public MemberLevelResponse memberLevel;
    public MemberAccountResponse memberAccount;
    public Long promotionCampaignId;
    public Long priceRuleId;
    public Long couponTemplateId;
    public Long userCouponId;
    public String pricingVersion;
    public String couponUnavailableReason;
    public String pricingSnapshotJson;
}
