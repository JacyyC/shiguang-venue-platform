package com.example.venue.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("notice_delivery_log")
public class NoticeDeliveryLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long messageId;
    public String channel;
    public String target;
    public String status;
    public String errorMessage;
    public String sourceType;
    public BigDecimal confidence;
    public String providerEnv;
    public String providerName;
    public String externalRef;
    public String idempotencyKey;
    public String requestSummaryJson;
    public String responseSummaryJson;
    public String failureCode;
    public String configVersion;
    public Long durationMs;
    public String metadataJson;
    public LocalDateTime createTime;
}
