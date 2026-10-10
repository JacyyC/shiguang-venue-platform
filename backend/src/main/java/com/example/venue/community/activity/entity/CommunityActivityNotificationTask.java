package com.example.venue.community.activity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("community_activity_notification_task")
public class CommunityActivityNotificationTask {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String eventType;
    public String eventKey;
    public Long recipientUserId;
    public String title;
    public String content;
    public String businessType;
    public Long businessId;
    public String status;
    public Integer attemptCount;
    public LocalDateTime nextRetryTime;
    public String lastErrorCode;
    public String lastErrorMessage;
    public LocalDateTime claimedAt;
    public LocalDateTime deliveredAt;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
