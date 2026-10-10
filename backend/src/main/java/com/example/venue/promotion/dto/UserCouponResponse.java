package com.example.venue.promotion.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class UserCouponResponse {
    public Long id;
    public Long templateId;
    public String templateCode;
    public String templateName;
    public String couponType;
    public BigDecimal discountValue;
    public BigDecimal thresholdAmount;
    public String status;
    public LocalDateTime claimTime;
    public LocalDateTime validStartTime;
    public LocalDateTime validEndTime;
    public Long reservationId;
    public Long orderId;
    public BigDecimal discountAmount;
    public String sourceType;
    public LocalDateTime releasedAt;
}
