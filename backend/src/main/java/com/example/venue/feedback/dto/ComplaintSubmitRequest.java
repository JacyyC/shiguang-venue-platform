package com.example.venue.feedback.dto;

public class ComplaintSubmitRequest {
    public Long venueId;
    public Long spaceId;
    public Long reservationId;
    public Long orderId;
    public Long lifecycleId;
    public Long noticeId;
    public Long assetId;
    public Long siteIncidentId;
    public String complaintType;
    public String severity;
    public String title;
    public String contentText;
    public String requestedResolution;
}
