package com.example.venue.siteops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("site_incident_log")
public class SiteIncidentLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String incidentNo;
    public Long venueId;
    public Long spaceId;
    public Long shiftId;
    public Long taskId;
    public Long reservationId;
    public Long orderId;
    public Long assetId;
    public Long complaintId;
    public String incidentType;
    public String severity;
    public String status;
    public LocalDateTime occurredAt;
    public Long reportedBy;
    public String description;
    public String resolution;
    public String sourceType;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
