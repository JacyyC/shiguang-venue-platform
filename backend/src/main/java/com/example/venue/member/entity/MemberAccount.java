package com.example.venue.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("member_account")
public class MemberAccount {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long levelId;
    public String levelCode;
    public String levelName;
    public Integer growthValue;
    public Integer pointsBalance;
    public BigDecimal totalConsumptionAmount;
    public Integer totalReservations;
    public BigDecimal totalMemberDiscountAmount;
    public LocalDateTime lastActiveTime;
    public String profileTagsJson;
    public String status;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
