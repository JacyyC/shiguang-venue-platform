package com.example.venue.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("refund_record")
public class RefundRecord {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String refundNo;
    public Long orderId;
    public Long userId;
    public BigDecimal refundAmount;
    public BigDecimal feeAmount;
    public BigDecimal netAmount;
    public String refundReason;
    public String status;
    public String auditReason;
    public String sourceType;
    public BigDecimal confidence;
    public String providerEnv;
    public String providerName;
    public String externalRef;
    public String idempotencyKey;
    public String requestSummaryJson;
    public String responseSummaryJson;
    public String failureCode;
    public String configVersion;
    public Long durationMs;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
