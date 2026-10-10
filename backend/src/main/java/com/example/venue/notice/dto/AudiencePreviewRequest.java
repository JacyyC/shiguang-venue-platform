package com.example.venue.notice.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class AudiencePreviewRequest {
    @NotBlank
    public String targetScope;
    public Long targetVenueId;
    public Long memberLevelId;
    public Long campaignId;
    public List<Long> userIds;
}
