package com.example.venue.community.activity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("community_activity")
public class CommunityActivity {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String activityNo;
    public Long groupId;
    public Long referencePostId;
    public Long organizerUserId;
    public String activityType;
    public String title;
    public String description;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
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
