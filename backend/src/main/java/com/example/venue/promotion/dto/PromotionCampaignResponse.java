package com.example.venue.promotion.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PromotionCampaignResponse {
    public Long id;
    public String campaignCode;
    public String campaignName;
    public String campaignType;
    public Long venueId;
    public Long spaceId;
    public String targetMemberLevel;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public BigDecimal budgetAmount;
    public String discountType;
    public BigDecimal discountValue;
    public BigDecimal maxDiscountAmount;
    public Integer priority;
    public Boolean allowCouponStack;
    public String status;
}
