package com.example.venue.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("finance_reconciliation_item")
public class FinanceReconciliationItem {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String reconcileNo;
    public LocalDate reconcileDate;
    public String businessType;
    public String providerEnv;
    public String providerName;
    public Long orderId;
    public Long refundId;
    public Long paymentRecordId;
    public Long paymentAttemptId;
    public String externalRef;
    public BigDecimal systemAmount;
    public BigDecimal providerAmount;
    public BigDecimal feeAmount;
    public BigDecimal netAmount;
    public BigDecimal differenceAmount;
    public String status;
    public BigDecimal adjustmentAmount;
    public String adjustmentReason;
    public Long operatorId;
    public String evidenceJson;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
