package com.example.venue.checkin.dto;

import java.time.LocalDateTime;

public class LifecycleEventResponse {
    public String eventType;
    public String fromStatus;
    public String toStatus;
    public String sourceType;
    public String reasonCode;
    public String reasonText;
    public LocalDateTime createTime;
}
