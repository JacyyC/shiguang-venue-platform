package com.example.venue.member.service;

public interface MemberRewardExecutionFaultInjector {
    void afterCouponIssued(Long redemptionId);
    void afterPointsDebited(Long redemptionId);
    void afterSuccessfulCommit(Long redemptionId);
}
