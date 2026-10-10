package com.example.venue.siteops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("site_shift")
public class SiteShift {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String shiftNo;
    public Long venueId;
    public Long spaceId;
    public LocalDate shiftDate;
    public String shiftName;
    public String roleCode;
    public Long assignedUserId;
    public LocalDateTime plannedStartTime;
    public LocalDateTime plannedEndTime;
    public LocalDateTime actualClockInTime;
    public LocalDateTime actualClockOutTime;
    public String status;
    public String sourceType;
    public String handoverStatus;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
