package com.example.venue.promotion.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class CouponTemplateResponse {
    public Long id;
    public String templateCode;
    public String templateName;
    public String couponType;
    public BigDecimal discountValue;
    public BigDecimal thresholdAmount;
    public BigDecimal maxDiscountAmount;
    public Integer totalQuantity;
    public Integer issuedQuantity;
    public Integer usedQuantity;
    public Integer perUserLimit;
    public LocalDateTime validStartTime;
    public LocalDateTime validEndTime;
    public Long campaignId;
    public Long venueId;
    public Long spaceId;
    public Boolean allowActivityStack;
    public String status;
    /**
     * 成本承担方（SHIGUANG-BATCH-04-REFACTOR-20260914 S03 / B4-P5）。
     *
     * <p>{@code coupon_template.cost_owner} 是既有物理列：请求 DTO 收、实体存、服务端写，
     * 但响应 DTO 此前不含该字段，全仓无任何读取出口，写入后不可见、不可核。此处仅补只读出参，
     * 不改列、不改写入语义、不引入金额归因计算；值域是自由文本，前端按原值展示。</p>
     */
    public String costOwner;
}
