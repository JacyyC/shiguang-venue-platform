package com.example.venue.community.activity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("community_activity_command")
public class CommunityActivityCommand {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long actorUserId;
    public String commandType;
    public String idempotencyKey;
    public String requestHash;
    public String targetType;
    public Long targetId;
    public String resultStatus;
    public String status;
    public LocalDateTime createTime;
    public LocalDateTime completedAt;
}
