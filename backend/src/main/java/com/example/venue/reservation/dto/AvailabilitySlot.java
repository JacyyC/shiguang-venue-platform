package com.example.venue.reservation.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AvailabilitySlot {
    public Long spaceId;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public String status;
    public String label;
    public BigDecimal price;
    public String reason;
    public String inventoryStatus;
    public String reasonCode;
    public String blockType;
    public Long blockId;
    public Long reservationId;
}
