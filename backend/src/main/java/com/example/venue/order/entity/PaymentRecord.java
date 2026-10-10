package com.example.venue.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("payment_record")
public class PaymentRecord {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long paymentAttemptId;
    public Long orderId;
    public String orderNo;
    public String channel;
    public String transactionId;
    public BigDecimal payAmount;
    public BigDecimal feeAmount;
    public BigDecimal netAmount;
    public String status;
    public LocalDateTime payTime;
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
