package com.example.venue.siteops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("asset_lifecycle_event")
public class AssetLifecycleEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String eventNo;
    public Long assetId;
    public Long venueId;
    public Long spaceId;
    public String eventType;
    public String fromStatus;
    public String toStatus;
    public Long operatorUserId;
    public String sourceType;
    public String reasonCode;
    public String reasonText;
    public BigDecimal costAmount;
    public LocalDateTime downtimeStart;
    public LocalDateTime downtimeEnd;
    public Long siteTaskId;
    public Long repairRecordId;
    public Long inspectionRecordId;
    public Long unavailableBlockId;
    public String metadataJson;
    public LocalDateTime occurredAt;
    public LocalDateTime createTime;
}
