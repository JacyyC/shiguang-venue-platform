package com.example.venue.provider.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class WeatherSnapshotResponse {
    public Long id;
    public String snapshotNo;
    public Long venueId;
    public Long locationId;
    public String providerKey;
    public String providerEnv;
    public String providerName;
    public String configVersion;
    public LocalDateTime observedAt;
    public LocalDateTime expiresAt;
    public BigDecimal temperatureC;
    public BigDecimal precipitationMm;
    public BigDecimal windSpeed;
    public String weatherCode;
    public String alertLevel;
    public String summaryText;
    public BigDecimal confidence;
    public String rawSummaryJson;
    public LocalDateTime createTime;
}
