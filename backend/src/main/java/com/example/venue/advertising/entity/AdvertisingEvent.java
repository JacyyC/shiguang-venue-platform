package com.example.venue.advertising.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("advertising_event")
public class AdvertisingEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String eventNo;
    public String eventKey;
    public String eventType;
    public Long decisionId;
    public Long userId;
    public String slotCode;
    public Long advertiserId;
    public Long campaignId;
    public Long creativeVersionId;
    public String requestHash;
    public BigDecimal visibleRatio;
    public Integer visibleDurationMs;
    public String resultStatus;
    public String rejectionCode;
    public String policyVersion;
    public LocalDateTime occurredAt;
    public LocalDateTime createTime;
}
