package com.example.venue.promotion.dto;

import com.example.venue.member.dto.MemberAccountResponse;
import com.example.venue.member.dto.MemberLevelResponse;
import com.example.venue.pricing.AmountBreakdown;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PromotionPricingContext {
    public String pricingVersion = "B3_PROMOTION_PRICING_V1";
    public Long venueId;
    public Long spaceId;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public BigDecimal unitPrice;
    public BigDecimal baseAmount;
    public BigDecimal priceRuleAdjustmentAmount;
    public AmountBreakdown amountBreakdown;
    public MemberLevelResponse memberLevel;
    public MemberAccountResponse memberAccount;
    public PriceRuleResponse priceRule;
    public PromotionCampaignResponse campaign;
    public CouponPricingView coupon;
    public List<Map<String, Object>> discountComponents = new ArrayList<>();
    public List<Map<String, Object>> skippedComponents = new ArrayList<>();
    public String pricingSnapshotJson;
}
