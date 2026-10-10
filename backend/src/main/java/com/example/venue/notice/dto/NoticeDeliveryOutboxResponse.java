package com.example.venue.notice.dto;

import java.time.LocalDateTime;

public class NoticeDeliveryOutboxResponse {
    public Long outboxId;
    public String outboxNo;
    public String eventType;
    public String recipientKind;
    public String maskedRecipient;
    public String channel;
    public String providerKey;
    public String providerEnv;
    public String providerName;
    public String configVersion;
    public String templateKey;
    public String templateVersion;
    public String status;
    public String statusText;
    public String acceptanceStatus;
    public String downstreamStatus;
    public Integer attemptCount;
    public LocalDateTime nextRetryTime;
    public String failureCode;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public LocalDateTime acceptedAt;
}
