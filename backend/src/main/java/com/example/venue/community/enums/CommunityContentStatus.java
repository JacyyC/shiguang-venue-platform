package com.example.venue.community.enums;

public enum CommunityContentStatus {
    PUBLISHED,
    HIDDEN,
    DELETED,
    PURGED;

    public boolean matches(String persistedValue) {
        return name().equals(persistedValue);
    }

    public static boolean any(String persistedValue, CommunityContentStatus... candidates) {
        if (candidates == null) {
            return false;
        }
        for (CommunityContentStatus candidate : candidates) {
            if (candidate != null && candidate.matches(persistedValue)) {
                return true;
            }
        }
        return false;
    }
}
