package com.example.venue.provider.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class VenueObservationLocationResponse {
    public Long id;
    public Long venueId;
    public String areaCode;
    public BigDecimal latitude;
    public BigDecimal longitude;
    public String locationLabel;
    public String timezone;
    public String status;
    public String sourceType;
    public BigDecimal confidence;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
