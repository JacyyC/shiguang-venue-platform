package com.example.venue.promotion.service;

public interface CouponRewardIssuanceFaultInjector {
    void afterInventoryReserved(Long templateId, Long userId, Long redemptionId);
}
