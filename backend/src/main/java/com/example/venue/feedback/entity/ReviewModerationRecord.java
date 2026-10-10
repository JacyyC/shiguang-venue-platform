package com.example.venue.feedback.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("review_moderation_record")
public class ReviewModerationRecord {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String moderationNo;
    public Long reviewId;
    public String action;
    public String fromStatus;
    public String toStatus;
    public String reasonCode;
    public String reasonText;
    public Long operatorUserId;
    public String metadataJson;
    public LocalDateTime occurredAt;
    public LocalDateTime createTime;
}
