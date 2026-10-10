package com.example.venue.provider.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("weather_observation_snapshot")
public class WeatherObservationSnapshot {
    @TableId(type = IdType.AUTO)
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
    public Integer deleted;
}
