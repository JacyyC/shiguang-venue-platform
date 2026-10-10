package com.example.venue.community.activity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("community_activity_state_event")
public class CommunityActivityStateEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String eventNo;
    public String eventType;
    public String subjectType;
    public Long subjectId;
    public Long actorUserId;
    public String sourceType;
    public String fromStatus;
    public String toStatus;
    public String reasonCode;
    public String idempotencyKey;
    public String metadataJson;
    public LocalDateTime occurredAt;
    public LocalDateTime createTime;
}
