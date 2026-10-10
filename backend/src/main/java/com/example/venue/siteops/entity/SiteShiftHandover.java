package com.example.venue.siteops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("site_shift_handover")
public class SiteShiftHandover {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String handoverNo;
    public Long fromShiftId;
    public Long toShiftId;
    public Long venueId;
    public Long fromUserId;
    public Long toUserId;
    public String status;
    public Integer unresolvedTaskCount;
    public String incidentSummary;
    public String assetIssueSummary;
    public String note;
    public LocalDateTime acceptedAt;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
