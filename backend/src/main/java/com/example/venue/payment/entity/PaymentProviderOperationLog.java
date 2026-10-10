package com.example.venue.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("payment_provider_operation_log")
public class PaymentProviderOperationLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String operationNo;
    public String operationType;
    public String providerEnv;
    public String providerName;
    public Long paymentAttemptId;
    public Long orderId;
    public Long refundId;
    public String externalRef;
    public String idempotencyKey;
    public String status;
    public String requestSummaryJson;
    public String responseSummaryJson;
    public String failureCode;
    public String failureMessage;
    public String configVersion;
    public Long durationMs;
    public String metadataJson;
    public LocalDateTime createTime;
}
