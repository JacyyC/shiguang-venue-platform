package com.example.venue.inventory.dto;

import java.time.LocalDate;
import java.time.LocalTime;

public class SaveBusinessCalendarRequest {
    public Long venueId;
    public LocalDate calendarDate;
    public LocalTime openTime;
    public LocalTime closeTime;
    public String businessStatus;
    public String reasonCode;
    public String reasonText;
    public String sourceType;
    public String providerEnv;
    public String providerName;
    public String externalRef;
    public String idempotencyKey;
    public String metadataJson;
}
