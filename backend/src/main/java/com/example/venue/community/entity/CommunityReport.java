package com.example.venue.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("community_report")
public class CommunityReport {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String reportNo;
    public Long reporterUserId;
    public String targetType;
    public Long targetId;
    public String reasonText;
    public String status;
    public String idempotencyKey;
    public String requestHash;
    public Long assignedTo;
    public String decisionCode;
    public String internalNote;
    public LocalDateTime closedAt;
    public LocalDateTime reasonRetainUntil;
    public LocalDateTime reasonPurgedAt;
    public LocalDateTime minimalRetainUntil;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
