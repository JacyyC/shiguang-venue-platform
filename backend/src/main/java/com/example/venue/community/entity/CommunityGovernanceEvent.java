package com.example.venue.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("community_governance_event")
public class CommunityGovernanceEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String eventNo;
    public String eventType;
    public String subjectType;
    public Long subjectId;
    public Long reportId;
    public Long actorUserId;
    public String actorScope;
    public String sourceType;
    public String fromStatus;
    public String toStatus;
    public String reasonCode;
    public String reasonSummary;
    public String resultStatus;
    public String idempotencyKey;
    public String metadataJson;
    public LocalDateTime occurredAt;
    public LocalDateTime retainUntil;
    public LocalDateTime createTime;
}
