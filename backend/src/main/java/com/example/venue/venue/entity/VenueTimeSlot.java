package com.example.venue.venue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalTime;

@TableName("venue_time_slot")
public class VenueTimeSlot {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long venueId;
    public Long spaceId;
    public LocalTime startTime;
    public LocalTime endTime;
    public Integer dayOfWeek;
    public String status;
}
