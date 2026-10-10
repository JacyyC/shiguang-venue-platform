package com.example.venue.community.activity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("community_activity_participation")
public class CommunityActivityParticipation {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String participationNo;
    public String targetType;
    public Long targetId;
    public Long userId;
    public String roleType;
    public String status;
    public Long applicationId;
    public LocalDateTime joinedAt;
    public LocalDateTime leftAt;
    public Long versionNo;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
