package com.example.venue.advertising.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("advertising_attribution")
public class AdvertisingAttribution {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String attributionNo;
    public Long reservationId;
    public Long userId;
    public Long clickEventId;
    public Long decisionId;
    public Long advertiserId;
    public Long campaignId;
    public Long creativeVersionId;
    public LocalDateTime clickOccurredAt;
    public LocalDateTime conversionOccurredAt;
    public String policyVersion;
    public String ruleVersionSnapshot;
    public String status;
    public String reasonCode;
    public String sourceStatus;
    public String sourceEventKey;
    public LocalDateTime revokedAt;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
