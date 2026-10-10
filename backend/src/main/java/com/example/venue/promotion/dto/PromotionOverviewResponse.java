package com.example.venue.promotion.dto;

import java.math.BigDecimal;

public class PromotionOverviewResponse {
    public Long activeCampaigns;
    public Long activeCouponTemplates;
    public Long activePriceRules;
    public Long claimedCoupons;
    public Long lockedCoupons;
    public Long usedCoupons;
    public Long refundedCoupons;
    public BigDecimal totalCouponDiscountAmount;
}
