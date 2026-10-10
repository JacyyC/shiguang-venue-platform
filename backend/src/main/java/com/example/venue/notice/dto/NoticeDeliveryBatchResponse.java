package com.example.venue.notice.dto;

import java.time.LocalDateTime;

public class NoticeDeliveryBatchResponse {
    public Long batchId;
    public String batchNo;
    public Long noticeId;
    public String targetScope;
    public int audienceCount;
    public int deliverableCount;
    public int excludedCount;
    public long sentCount;
    public long failedCount;
    public long unreadCount;
    public long readCount;
    public String status;
    public String providerEnv;
    public String providerName;
    public String configVersion;
    public String sourceType;
    public Long publisherId;
    public Long experimentId;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
