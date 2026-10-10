package com.example.venue.advertising.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("advertising_decision")
public class AdvertisingDecision {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String decisionNo;
    public Long userId;
    public String slotCode;
    public Long advertiserId;
    public Long campaignId;
    public Long creativeVersionId;
    public String tokenHash;
    public String policyVersion;
    public String ruleVersionSnapshot;
    public String privacySnapshotJson;
    public String frequencySnapshotJson;
    public String creativeSnapshotJson;
    public String arbitrationSnapshotJson;
    public LocalDateTime expiresAt;
    public LocalDateTime consumedAt;
    public String status;
    public LocalDateTime createTime;
}
