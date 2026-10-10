package com.example.venue.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.venue.order.dto.OrderRefundSummary;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("biz_order")
public class BizOrder {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String orderNo;
    public Long reservationId;
    public Long userId;
    public Long venueId;
    public BigDecimal totalAmount;
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
    public String status;
    public String payType;
    /** Stable payment-tool fact written after the local payment fact succeeds. */
    public String paymentToolType;
    public String paymentFactId;
    public String paymentCurrency;
    public LocalDateTime payTime;
    public LocalDateTime expireTime;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    @TableField(exist = false)
    public String venueName;
    /** 非持久化：用户侧订单列表的本人退款概要（最近一条），仅 myOrders 富化；见 OrderRefundSummary。 */
    @TableField(exist = false)
    public OrderRefundSummary refundSummary;
}
