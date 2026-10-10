package com.example.venue.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("member_account_log")
public class MemberAccountLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long accountId;
    public Long userId;
    public String eventType;
    public String beforeLevelCode;
    public String afterLevelCode;
    public Integer growthDelta;
    public Integer pointsDelta;
    public BigDecimal consumptionDelta;
    public Long reservationId;
    public Long orderId;
    public String sourceType;
    public String reasonCode;
    public String reasonText;
    public String metadataJson;
    public String businessKey;
    public String ruleVersion;
    public Integer pointsBalanceBefore;
    public Integer pointsBalanceAfter;
    public LocalDateTime createTime;
}
