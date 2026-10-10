package com.example.venue.community.enums;

public enum CommunityMemberStatus {
    PENDING,
    ACTIVE,
    REJECTED,
    LEFT,
    REMOVED;

    public boolean matches(String persistedValue) {
        return name().equals(persistedValue);
    }

    public static boolean any(String persistedValue, CommunityMemberStatus... candidates) {
        if (candidates == null) {
            return false;
        }
        for (CommunityMemberStatus candidate : candidates) {
            if (candidate != null && candidate.matches(persistedValue)) {
                return true;
            }
        }
        return false;
    }
}
