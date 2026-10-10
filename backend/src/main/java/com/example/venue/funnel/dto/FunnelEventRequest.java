package com.example.venue.funnel.dto;

import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * A26-P2-24：各字段长度约束按 funnel_event/funnel_session schema 列长度对齐，
 * 避免超长字段触发数据库 500 且 batch 整批回滚。{@code @Size} 注解需 Controller 端
 * {@code @Valid} 触发；{@code FunnelEventService.validate} 同步做防御性强制校验。
 *
 * <p>occurredAt 时间窗校验在 Service 层强制执行（不允许未来时间，不允许早于 7 天前），
 * 避免匿名端点伪造 occurredAt 把事件精确投放进管理端任意日期窗口。</p>
 */
public class FunnelEventRequest {
    @Size(max = 96, message = "sessionKey 长度不能超过 96")
    public String sessionKey;
    @Size(max = 128, message = "visitorId 长度不能超过 128")
    public String visitorId;
    @Size(max = 64, message = "eventName 长度不能超过 64")
    public String eventName;
    @Size(max = 64, message = "eventStage 长度不能超过 64")
    public String eventStage;
    @Size(max = 64, message = "sourceChannel 长度不能超过 64")
    public String sourceChannel;
    @Size(max = 128, message = "campaignId 长度不能超过 128")
    public String campaignId;
    @Size(max = 255, message = "pagePath 长度不能超过 255")
    public String pagePath;
    @Size(max = 255, message = "referrerPath 长度不能超过 255")
    public String referrerPath;
    @Size(max = 128, message = "keyword 长度不能超过 128")
    public String keyword;
    public Long venueId;
    public Long spaceId;
    public Long reservationId;
    public Long orderId;
    public Long userCouponId;
    public Long promotionCampaignId;
    public Long priceRuleId;
    public BigDecimal payableAmount;
    @Size(max = 32, message = "resultStatus 长度不能超过 32")
    public String resultStatus;
    @Size(max = 64, message = "dropoffReasonCode 长度不能超过 64")
    public String dropoffReasonCode;
    @Size(max = 255, message = "reasonText 长度不能超过 255")
    public String reasonText;
    @Size(max = 128, message = "idempotencyKey 长度不能超过 128")
    public String idempotencyKey;
    @Size(max = 512, message = "userAgent 长度不能超过 512")
    public String userAgent;
    @Size(max = 32, message = "deviceType 长度不能超过 32")
    public String deviceType;
    @Size(max = 64, message = "browserFamily 长度不能超过 64")
    public String browserFamily;
    public LocalDateTime occurredAt;
    public Map<String, Object> metadata;
}
