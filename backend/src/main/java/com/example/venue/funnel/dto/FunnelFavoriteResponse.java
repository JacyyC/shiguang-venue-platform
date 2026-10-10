package com.example.venue.funnel.dto;

import java.time.LocalDateTime;

public class FunnelFavoriteResponse {
    public Long id;
    public Long userId;
    public Long venueId;
    public String sourceChannel;
    public String campaignId;
    public String status;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
