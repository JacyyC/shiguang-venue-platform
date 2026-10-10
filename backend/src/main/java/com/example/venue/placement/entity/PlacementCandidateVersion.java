package com.example.venue.placement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("placement_candidate_version")
public class PlacementCandidateVersion {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long slotId;
    public String candidateNo;
    public String sourceType;
    public Long sourceId;
    public String sourceVersion;
    public LocalDateTime effectiveFrom;
    public LocalDateTime effectiveUntil;
    public String audienceType;
    public String audienceLevelCode;
    public Integer priority;
    public Integer frequencyCapPerUser;
    public Integer frequencyCapWindowHours;
    public String ruleVersion;
    public String status;
    public Long createdBy;
    public Long publishedBy;
    public LocalDateTime publishedAt;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
