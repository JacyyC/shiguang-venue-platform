package com.example.venue.siteops.dto;

import java.time.LocalDateTime;

public class SiteIncidentRequest {
    public Long venueId;
    public Long spaceId;
    public Long shiftId;
    public Long taskId;
    public Long reservationId;
    public Long orderId;
    public Long assetId;
    public Long complaintId;
    public String incidentType;
    public String severity;
    public LocalDateTime occurredAt;
    public String description;
}
