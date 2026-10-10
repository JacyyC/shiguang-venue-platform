package com.example.venue.funnel.dto;

import java.time.LocalDateTime;

public class FunnelEventResponse {
    public Long id;
    public String eventName;
    public String eventStage;
    public String resultStatus;
    public Long venueId;
    public LocalDateTime occurredAt;
}
