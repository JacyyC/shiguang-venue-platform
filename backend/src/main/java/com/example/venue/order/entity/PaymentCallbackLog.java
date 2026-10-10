package com.example.venue.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("payment_callback_log")
public class PaymentCallbackLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String channel;
    public String orderNo;
    public String transactionId;
    public String payload;
    public String status;
    public String message;
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
}
