package com.example.venue.promotion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class SavePromotionCampaignRequest {
    @NotBlank
    public String campaignCode;
    @NotBlank
    public String campaignName;
    public String campaignType;
    public Long venueId;
    public Long spaceId;
    public String targetMemberLevel;
    @NotNull
    public LocalDateTime startTime;
    @NotNull
    public LocalDateTime endTime;
    public BigDecimal budgetAmount;
    public String discountType;
    public BigDecimal discountValue;
    public BigDecimal maxDiscountAmount;
    public Integer priority;
    public Boolean allowCouponStack;
    public String status;
}
