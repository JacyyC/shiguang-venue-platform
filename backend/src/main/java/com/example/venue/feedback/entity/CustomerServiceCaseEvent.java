package com.example.venue.feedback.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

@TableName("customer_service_case_event")
public class CustomerServiceCaseEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String eventNo;
    public Long caseId;
    public Long complaintId;
    public String eventType;
    public String fromStatus;
    public String toStatus;
    public Long actorUserId;
    public String actorUsername;
    public String noteText;
    public String noteSanitized;
    // A26-P0-04：回复原文落库列，仅审计/管理员可见，前端响应不返回（@JsonIgnore + DTO 不映射）。
    @JsonIgnore
    public String noteRaw;
    public String internalNote;
    public String sourceType;
    public String metadataJson;
    public LocalDateTime occurredAt;
    public LocalDateTime createTime;
}
