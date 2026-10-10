package com.example.venue.resource.enums;

import com.example.venue.common.BusinessException;

import java.util.Locale;

public enum ResourceCategory {
    SPORTS_FIELD,
    STUDY_ROOM,
    ACTIVITY_ROOM;

    public static ResourceCategory parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.badRequest("资源类别不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("资源类别只能为 SPORTS_FIELD、STUDY_ROOM、ACTIVITY_ROOM");
        }
    }
}
