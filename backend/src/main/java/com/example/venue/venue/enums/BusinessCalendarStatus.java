package com.example.venue.venue.enums;

/**
 * 场馆营业日历状态枚举。
 * 用于 VenueInventoryService 日库存生成时的状态计算，
 * 替代原 List.of("OPEN", "CLOSED", "SPECIAL_HOURS", "INTERNAL_ONLY") 硬编码。
 */
public enum BusinessCalendarStatus {
    OPEN,
    CLOSED,
    SPECIAL_HOURS,
    INTERNAL_ONLY
}
