package com.example.venue.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class InventorySlot {
    public Long venueId;
    public Long spaceId;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public String status;
    public String label;
    public BigDecimal price;
    public String reasonCode;
    public String reasonText;
    public String blockType;
    public Long blockId;
    public Long reservationId;
    public String reservationStatus;
    public String sourceType;
    public String relatedEntityType;
    public Long relatedEntityId;
}
