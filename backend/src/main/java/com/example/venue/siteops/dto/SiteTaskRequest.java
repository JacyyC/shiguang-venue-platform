package com.example.venue.siteops.dto;

import java.time.LocalDateTime;

public class SiteTaskRequest {
    public Long venueId;
    public Long spaceId;
    public Long shiftId;
    public String taskType;
    public String title;
    public String priority;
    public Long assignedUserId;
    public Long reservationId;
    public Long orderId;
    public Long lifecycleId;
    public Long assetId;
    public Long unavailableBlockId;
    public Long complaintId;
    public LocalDateTime dueTime;
    public String metadataJson;
}
