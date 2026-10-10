package com.example.venue.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("venue_resource_rule")
public class VenueResourceRule {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long spaceId;
    public String resourceCategory;
    public String bookingMode;
    public Integer capacityLimit;
    public Integer versionNo;
    public String status;
    public Integer activeFlag;
    public LocalDateTime effectiveFrom;
    public Long createdBy;
    public String reasonText;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
