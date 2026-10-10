package com.example.venue.siteops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("site_task")
public class SiteTask {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String taskNo;
    public Long venueId;
    public Long spaceId;
    public Long shiftId;
    public String taskType;
    public String title;
    public String priority;
    public String status;
    public Long assignedUserId;
    public Long reservationId;
    public Long orderId;
    public Long lifecycleId;
    public Long assetId;
    public Long unavailableBlockId;
    public Long complaintId;
    public LocalDateTime dueTime;
    public LocalDateTime startedAt;
    public LocalDateTime completedAt;
    public String exceptionCode;
    public String completionNote;
    public String evidenceJson;
    public String sourceType;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
