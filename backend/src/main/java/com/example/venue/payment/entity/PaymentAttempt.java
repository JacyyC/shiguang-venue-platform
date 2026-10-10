package com.example.venue.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("payment_attempt")
public class PaymentAttempt {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String paymentNo;
    public Long orderId;
    public String orderNo;
    public Long userId;
    public Long venueId;
    public Long reservationId;
    public String providerEnv;
    public String providerName;
    public String channel;
    public String status;
    public BigDecimal payableAmount;
    public BigDecimal paidAmount;
    public BigDecimal feeAmount;
    public BigDecimal netAmount;
    public String currency;
    public String externalRef;
    public String idempotencyKey;
    public String failureCode;
    public String failureMessage;
    public String configVersion;
    public String requestSummaryJson;
    public String responseSummaryJson;
    public String metadataJson;
    public LocalDateTime expireTime;
    public LocalDateTime paidTime;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
