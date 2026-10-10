package com.example.venue.reservation.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 场馆级可约聚合（只读）：给定日期下该场馆全部在售场地的 earliest 可约时段与可约档位数。
 * available=false 表示当天暂无可约时段；price 为该 earliest 时段单价（元/小时）。
 */
public class VenueAvailabilitySummary {
    public Long venueId;
    public Boolean available;
    public Long spaceId;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public BigDecimal price;
    public Integer availableSlotCount;
}
