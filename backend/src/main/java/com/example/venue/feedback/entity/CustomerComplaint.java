package com.example.venue.feedback.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

@TableName("customer_complaint")
public class CustomerComplaint {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String complaintNo;
    public Long userId;
    public Long venueId;
    public Long spaceId;
    public Long reservationId;
    public Long orderId;
    public Long lifecycleId;
    public Long noticeId;
    public Long assetId;
    public Long siteIncidentId;
    public String complaintType;
    public String severity;
    public String status;
    public String title;
    public String contentText;
    public String contentSanitized;
    // A26-P0-04：原文落库列，仅审计/管理员可见，前端响应不返回（@JsonIgnore + DTO 不映射）。
    @JsonIgnore
    public String contentRaw;
    public Integer sensitiveFlag;
    public String requestedResolution;
    public String sourceType;
    public LocalDateTime submittedAt;
    public Long triagedBy;
    public LocalDateTime triagedAt;
    public LocalDateTime resolvedAt;
    public LocalDateTime closedAt;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
