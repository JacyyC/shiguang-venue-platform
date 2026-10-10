package com.example.venue.promotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("coupon_template")
public class CouponTemplate {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String templateCode;
    public String templateName;
    public String couponType;
    public Long venueId;
    public Long spaceId;
    public Long campaignId;
    public BigDecimal discountValue;
    public BigDecimal thresholdAmount;
    public BigDecimal maxDiscountAmount;
    public Integer totalQuantity;
    public Integer issuedQuantity;
    public Integer usedQuantity;
    public Integer perUserLimit;
    public LocalDateTime claimStartTime;
    public LocalDateTime claimEndTime;
    public LocalDateTime validStartTime;
    public LocalDateTime validEndTime;
    public Integer validityDays;
    public Boolean allowActivityStack;
    public String costOwner;
    public String status;
    public String sourceType;
    public Integer sortOrder;
    public String metadataJson;
    public Long createdBy;
    public Long updatedBy;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
