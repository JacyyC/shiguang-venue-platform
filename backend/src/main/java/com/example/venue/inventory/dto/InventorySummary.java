package com.example.venue.inventory.dto;

import java.time.LocalDate;

public class InventorySummary {
    public Long venueId;
    public Long spaceId;
    public LocalDate date;
    public int availableSlots;
    public int reservedSlots;
    public int unavailableSlots;
    public int manualHoldSlots;
    public int closedSlots;
    public int bufferSlots;
    public int availableMinutes;
    public int reservedMinutes;
    public int unavailableMinutes;
    public int manualHoldMinutes;
    public int closedMinutes;
    public int bufferMinutes;
}
