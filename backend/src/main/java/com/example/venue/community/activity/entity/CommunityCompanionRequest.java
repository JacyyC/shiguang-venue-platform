package com.example.venue.community.activity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("community_companion_request")
public class CommunityCompanionRequest {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String requestNo;
    public Long groupId;
    public Long activityId;
    public Long referencePostId;
    public Long ownerUserId;
    public String activityType;
    public String title;
    public String description;
    public LocalDateTime windowStart;
    public LocalDateTime windowEnd;
    public String locationType;
    public Long venueId;
    public Long spaceId;
    public String areaCode;
    public Long reservationId;
    public String locationCommitmentStatus;
    public Long commitmentVersion;
    public Integer capacityTotal;
    public Integer participantCount;
    public String status;
    public Long versionNo;
    public LocalDateTime publishedAt;
    public LocalDateTime closedAt;
    public LocalDateTime canceledAt;
    public LocalDateTime expiredAt;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
