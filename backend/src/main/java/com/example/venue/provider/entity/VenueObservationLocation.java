package com.example.venue.provider.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("venue_observation_location")
public class VenueObservationLocation {
    @TableId(type = IdType.AUTO)
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
    public Integer deleted;
}
