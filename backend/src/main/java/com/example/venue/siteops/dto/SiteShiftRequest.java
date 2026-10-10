package com.example.venue.siteops.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class SiteShiftRequest {
    public Long venueId;
    public Long spaceId;
    public LocalDate shiftDate;
    public String shiftName;
    public String roleCode;
    public Long assignedUserId;
    public LocalDateTime plannedStartTime;
    public LocalDateTime plannedEndTime;
    public String metadataJson;
}
