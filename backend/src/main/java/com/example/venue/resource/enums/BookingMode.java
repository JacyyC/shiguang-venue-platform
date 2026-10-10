package com.example.venue.resource.enums;

import com.example.venue.common.BusinessException;

import java.util.Locale;

public enum BookingMode {
    EXCLUSIVE,
    CAPACITY,
    ASSIGNED_SEAT;

    public static BookingMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.badRequest("预订模式不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("预订模式只能为 EXCLUSIVE、CAPACITY、ASSIGNED_SEAT");
        }
    }
}
