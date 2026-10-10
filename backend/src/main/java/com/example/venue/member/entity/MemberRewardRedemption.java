package com.example.venue.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("member_reward_redemption")
public class MemberRewardRedemption {
    @TableId(type = IdType.AUTO) public Long id;
    public String redemptionNo;
    public Long userId;
    public Long accountId;
    public Long catalogItemId;
    public Long catalogVersionId;
    public String itemCodeSnapshot;
    public String itemNameSnapshot;
    public Integer catalogVersionNo;
    public Integer pointsCost;
    public String minimumLevelCodeSnapshot;
    public String minimumLevelNameSnapshot;
    public Integer minimumGrowthThresholdSnapshot;
    public Long couponTemplateId;
    public Integer validityDaysSnapshot;
    public String idempotencyKey;
    public String requestHash;
    public String status;
    public String failureCode;
    public String failureMessage;
    public Long userCouponId;
    public Long pointsDebitLogId;
    public Long pointsCompensationLogId;
    public String sourceType;
    public String reasonCode;
    public String reasonText;
    public Long createdBy;
    public LocalDateTime completedAt;
    public LocalDateTime compensatedAt;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
