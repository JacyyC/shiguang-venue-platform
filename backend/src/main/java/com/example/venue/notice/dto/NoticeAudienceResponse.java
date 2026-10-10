package com.example.venue.notice.dto;

import java.time.LocalDateTime;

public class NoticeAudienceResponse {
    public Long audienceId;
    public Long batchId;
    public Long noticeId;
    public Long userId;
    public Long venueId;
    public Long memberLevelId;
    public String segmentCode;
    public Long experimentVariantId;
    public String deliveryStatus;
    public String readStatus;
    public String excludeReason;
    public Long messageId;
    public Long lastDeliveryLogId;
    public Long outboxId;
    public LocalDateTime readTime;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
