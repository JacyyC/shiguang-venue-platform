package com.example.venue.member.dto;

import com.example.venue.member.entity.MemberRewardRedemption;
import java.time.LocalDateTime;

public class MemberRewardRedemptionResponse {
    public Long id;
    public String redemptionNo;
    public String itemCode;
    public String itemName;
    public Integer versionNo;
    public Integer pointsCost;
    public String status;
    public String failureCode;
    public String failureMessage;
    public Long userCouponId;
    public LocalDateTime completedAt;
    public LocalDateTime compensatedAt;
    public LocalDateTime createTime;

    public static MemberRewardRedemptionResponse from(MemberRewardRedemption entity) {
        MemberRewardRedemptionResponse response = new MemberRewardRedemptionResponse();
        response.id = entity.id;
        response.redemptionNo = entity.redemptionNo;
        response.itemCode = entity.itemCodeSnapshot;
        response.itemName = entity.itemNameSnapshot;
        response.versionNo = entity.catalogVersionNo;
        response.pointsCost = entity.pointsCost;
        response.status = entity.status;
        response.failureCode = entity.failureCode;
        response.failureMessage = entity.failureMessage;
        response.userCouponId = entity.userCouponId;
        response.completedAt = entity.completedAt;
        response.compensatedAt = entity.compensatedAt;
        response.createTime = entity.createTime;
        return response;
    }
}
