package com.example.venue.promotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("user_coupon")
public class UserCoupon {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long templateId;
    public Long userId;
    public String status;
    public LocalDateTime claimTime;
    public LocalDateTime validStartTime;
    public LocalDateTime validEndTime;
    public LocalDateTime lockedAt;
    public LocalDateTime usedAt;
    public LocalDateTime releasedAt;
    public LocalDateTime refundedAt;
    public Long reservationId;
    public Long orderId;
    public String lockToken;
    public BigDecimal discountAmount;
    public String sourceType;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
