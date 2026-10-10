package com.example.venue.promotion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class SaveCouponTemplateRequest {
    @NotBlank
    public String templateCode;
    @NotBlank
    public String templateName;
    @NotBlank
    public String couponType;
    @NotNull
    public BigDecimal discountValue;
    public BigDecimal thresholdAmount;
    public BigDecimal maxDiscountAmount;
    public Integer totalQuantity;
    public Integer perUserLimit;
    @NotNull
    public LocalDateTime validStartTime;
    @NotNull
    public LocalDateTime validEndTime;
    public Long campaignId;
    public Long venueId;
    public Long spaceId;
    public Boolean allowActivityStack;
    public String costOwner;
    public String status;
}
