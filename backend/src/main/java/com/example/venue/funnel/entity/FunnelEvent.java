package com.example.venue.funnel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("funnel_event")
public class FunnelEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String eventNo;
    public Long sessionId;
    public String sessionKey;
    public Long userId;
    public String visitorIdHash;
    public String eventName;
    public String eventStage;
    public String sourceChannel;
    public String campaignId;
    public String providerEnv;
    public String pagePath;
    public String referrerPath;
    public String keywordNormalized;
    public String keywordHash;
    public Long venueId;
    public Long spaceId;
    public Long reservationId;
    public Long orderId;
    public Long userCouponId;
    public Long promotionCampaignId;
    public Long priceRuleId;
    public BigDecimal payableAmount;
    public String resultStatus;
    public String dropoffReasonCode;
    public String reasonText;
    public String idempotencyKey;
    public String metadataJson;
    public LocalDateTime occurredAt;
    public LocalDateTime createTime;
    public Integer deleted;
}
