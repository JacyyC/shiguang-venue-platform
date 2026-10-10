package com.example.venue.member.service;

public record MemberRewardSucceededEvent(Long redemptionId, Long userId, String itemName) {
}
