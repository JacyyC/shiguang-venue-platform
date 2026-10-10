package com.example.venue.venue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("venue_facility_maintenance")
public class VenueFacilityMaintenance {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long facilityId;
    public Long spaceId;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public String reason;
    public String status;
    public Long unavailableBlockId;
    public String sourceType;
    public String metadataJson;
}
