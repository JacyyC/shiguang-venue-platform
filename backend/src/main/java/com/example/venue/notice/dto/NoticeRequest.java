package com.example.venue.notice.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class NoticeRequest {
    @NotBlank
    public String title;
    @NotBlank
    public String content;
    public String type;
    public Long targetVenueId;
    public String status;
    public String targetScope;
    public Long memberLevelId;
    public Long campaignId;
    public List<Long> userIds;
    public Long experimentId;
}
