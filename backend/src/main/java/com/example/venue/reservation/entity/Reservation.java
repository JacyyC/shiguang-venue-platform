package com.example.venue.reservation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("reservation")
public class Reservation {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long venueId;
    public Long spaceId;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public String status;
    public String contactName;
    public String contactPhone;
    public String remark;
    public String rejectReason;
    public BigDecimal amount;
    public BigDecimal originalAmount;
    public BigDecimal memberDiscountAmount;
    public BigDecimal couponDiscountAmount;
    public BigDecimal activityDiscountAmount;
    public BigDecimal payableAmount;
    public BigDecimal paidAmount;
    public BigDecimal feeAmount;
    public BigDecimal netAmount;
    public String pricingSnapshotJson;
    public Long promotionCampaignId;
    public Long priceRuleId;
    public Long couponTemplateId;
    public Long userCouponId;
    public String couponLockToken;
    public LocalDateTime payExpireTime;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
    // AUD-P2-04：活动预算回补幂等标记（0=未回补，1=已回补）。回补时 CAS 占位防双终态路径重复回补。
    // 改期绑定新活动时由 applyPromotionPricing 重置为 0；不返回前端。
    @JsonIgnore
    public Integer budgetReleased;
    public String resourceCategorySnapshot;
    public String bookingModeSnapshot;
    public Long ruleIdSnapshot;
    public Integer ruleVersionSnapshot;
    public Integer participantCount;
    public String allocationSnapshotJson;
    @TableField(exist = false)
    public String venueName;
    @TableField(exist = false)
    public Long orderId;
}
