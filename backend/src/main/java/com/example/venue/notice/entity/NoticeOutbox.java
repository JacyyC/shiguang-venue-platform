package com.example.venue.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("notice_outbox")
public class NoticeOutbox {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String outboxNo;
    public Long batchId;
    public Long audienceId;
    public Long noticeId;
    public Long userId;
    public String eventType;
    public String eventKey;
    public String recipientKind;
    public String channel;
    public String providerEnv;
    public String providerName;
    public String providerKey;
    public String configVersion;
    public String templateKey;
    public String templateVersion;
    public String status;
    public Integer attemptCount;
    public LocalDateTime nextRetryTime;
    public String lastErrorCode;
    public String lastErrorMessage;
    public String idempotencyKey;
    public String requestSummaryJson;
    public String responseSummaryJson;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
