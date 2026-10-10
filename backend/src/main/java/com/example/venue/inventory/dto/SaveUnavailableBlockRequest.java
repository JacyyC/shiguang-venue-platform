package com.example.venue.inventory.dto;

import java.time.LocalDateTime;

public class SaveUnavailableBlockRequest {
    public Long venueId;
    public Long spaceId;
    public String blockType;
    public String reasonCode;
    public String reasonText;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public Integer bufferBeforeMinutes;
    public Integer bufferAfterMinutes;
    public String sourceType;
    public String providerEnv;
    public String providerName;
    public String externalRef;
    public String idempotencyKey;
    public String relatedEntityType;
    public Long relatedEntityId;
    public String metadataJson;
}
