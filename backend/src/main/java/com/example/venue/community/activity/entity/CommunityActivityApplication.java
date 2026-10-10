package com.example.venue.community.activity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("community_activity_application")
public class CommunityActivityApplication {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String applicationNo;
    public String targetType;
    public Long targetId;
    public Long applicantUserId;
    public String status;
    public String reasonCode;
    public LocalDateTime appliedAt;
    public Long decidedBy;
    public LocalDateTime decidedAt;
    public LocalDateTime withdrawnAt;
    public Long versionNo;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
