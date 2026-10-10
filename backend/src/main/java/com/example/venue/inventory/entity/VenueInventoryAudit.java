package com.example.venue.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("venue_inventory_audit")
public class VenueInventoryAudit {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String entityType;
    public Long entityId;
    public String action;
    public Long operatorId;
    public String sourceType;
    public String beforeJson;
    public String afterJson;
    public String reasonText;
    public LocalDateTime createTime;
}
