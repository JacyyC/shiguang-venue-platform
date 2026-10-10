package com.example.venue.community.enums;

public enum CommunityReportStatus {
    OPEN,
    IN_REVIEW,
    RESOLVED,
    DISMISSED;

    public boolean matches(String persistedValue) {
        return name().equals(persistedValue);
    }

    public static boolean any(String persistedValue, CommunityReportStatus... candidates) {
        if (candidates == null) {
            return false;
        }
        for (CommunityReportStatus candidate : candidates) {
            if (candidate != null && candidate.matches(persistedValue)) {
                return true;
            }
        }
        return false;
    }
}
