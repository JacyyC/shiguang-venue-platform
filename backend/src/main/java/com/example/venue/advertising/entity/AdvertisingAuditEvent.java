package com.example.venue.advertising.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("advertising_audit_event")
public class AdvertisingAuditEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String auditNo;
    public String entityType;
    public Long entityId;
    public String entityNo;
    public Long venueId;
    public String relatedEntityType;
    public Long relatedEntityId;
    public String action;
    public Long actorId;
    public String sourceType;
    public String reasonCode;
    public String reasonText;
    public String beforeStatus;
    public String afterStatus;
    public String resultStatus;
    public LocalDateTime createTime;
}
