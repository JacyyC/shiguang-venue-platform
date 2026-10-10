package com.example.venue.promotion.service;

import org.springframework.stereotype.Component;

@Component
public class NoopCouponRewardIssuanceFaultInjector implements CouponRewardIssuanceFaultInjector {
    @Override
    public void afterInventoryReserved(Long templateId, Long userId, Long redemptionId) {
    }
}
