package com.example.venue.member.service;

import org.springframework.stereotype.Component;

@Component
public class NoopMemberRewardExecutionFaultInjector implements MemberRewardExecutionFaultInjector {
    @Override public void afterCouponIssued(Long redemptionId) { }
    @Override public void afterPointsDebited(Long redemptionId) { }
    @Override public void afterSuccessfulCommit(Long redemptionId) { }
}
