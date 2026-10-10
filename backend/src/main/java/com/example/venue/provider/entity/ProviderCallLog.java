package com.example.venue.provider.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("provider_call_log")
public class ProviderCallLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String callNo;
    public String providerKey;
    public String providerDomain;
    public String providerEnv;
    public String providerName;
    public String operationType;
    public String status;
    public String idempotencyKey;
    public String externalRef;
    public String requestSummaryJson;
    public String responseSummaryJson;
    public String failureCode;
    public String failureMessage;
    public Long durationMs;
    public String configVersion;
    public BigDecimal confidence;
    public String snapshotType;
    public Long snapshotId;
    public String sourceType;
    public Long triggeredBy;
    public LocalDateTime occurredAt;
    public LocalDateTime createTime;
}
