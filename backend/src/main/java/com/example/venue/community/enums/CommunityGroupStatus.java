package com.example.venue.community.enums;

public enum CommunityGroupStatus {
    ACTIVE,
    HIDDEN,
    DELETED,
    PURGED;

    public boolean matches(String persistedValue) {
        return name().equals(persistedValue);
    }

    public static boolean any(String persistedValue, CommunityGroupStatus... candidates) {
        if (candidates == null) {
            return false;
        }
        for (CommunityGroupStatus candidate : candidates) {
            if (candidate != null && candidate.matches(persistedValue)) {
                return true;
            }
        }
        return false;
    }
}
