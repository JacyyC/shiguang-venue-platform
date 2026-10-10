package com.example.venue.venue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("venue_space")
public class VenueSpace {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long venueId;
    public String name;
    public String type;
    public String code;
    public Integer capacity;
    public BigDecimal pricePerHour;
    public String facilityText;
    public String description;
    public String status;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
