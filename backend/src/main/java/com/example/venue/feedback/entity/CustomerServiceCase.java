package com.example.venue.feedback.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("customer_service_case")
public class CustomerServiceCase {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String caseNo;
    public Long complaintId;
    public Long venueId;
    public Long assignedUserId;
    public String priority;
    public String status;
    public String rootCauseCode;
    public String resolutionType;
    public String resolutionSummary;
    public String customerVisibleReply;
    public Integer requiresRefundReview;
    public Integer requiresCouponReview;
    public Integer requiresSiteFollowUp;
    public LocalDateTime openedAt;
    public LocalDateTime assignedAt;
    public LocalDateTime resolvedAt;
    public LocalDateTime closedAt;
    public LocalDateTime reopenedAt;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
