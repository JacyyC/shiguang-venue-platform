package com.example.venue.business.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("business_event")
public class BusinessEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String eventNo;
    public String eventType;
    public String businessDomain;
    public String entityType;
    public Long entityId;
    public String relatedEntityType;
    public Long relatedEntityId;
    public Long actorUserId;
    public String actorUsername;
    public String sourceType;
    public String providerEnv;
    public String providerName;
    public String externalRef;
    public String idempotencyKey;
    public BigDecimal confidence;
    public String reasonCode;
    public String reasonText;
    public String fromStatus;
    public String toStatus;
    public String resultStatus;
    public LocalDateTime occurredAt;
    public String metadataJson;
    public LocalDateTime createTime;
}
