package com.example.venue.venue.enums;

import com.example.venue.common.BusinessException;

import java.util.Locale;

public enum VenueResourceStatus {
    ACTIVE("开放中"),
    MAINTENANCE("维护中"),
    CLOSED("已关闭");

    private final String label;

    VenueResourceStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static String normalizeOrDefault(String rawStatus, String fieldName, VenueResourceStatus defaultStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return defaultStatus.name();
        }
        return parse(rawStatus, fieldName).name();
    }

    public static VenueResourceStatus parse(String rawStatus, String fieldName) {
        if (rawStatus == null || rawStatus.isBlank()) {
            throw BusinessException.badRequest(fieldName + "不能为空");
        }
        String normalized = rawStatus.trim().toUpperCase(Locale.ROOT);
        try {
            return VenueResourceStatus.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest(fieldName + "只能为 ACTIVE、MAINTENANCE、CLOSED");
        }
    }

    public static boolean isDisabledAlias(String rawStatus) {
        return rawStatus != null && "DISABLED".equals(rawStatus.trim().toUpperCase(Locale.ROOT));
    }

    public static boolean isActive(String status) {
        return ACTIVE.name().equals(status);
    }
}
