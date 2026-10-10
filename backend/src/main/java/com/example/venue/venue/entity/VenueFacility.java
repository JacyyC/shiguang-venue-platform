package com.example.venue.venue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("venue_facility")
public class VenueFacility {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long venueId;
    public String name;
    public Integer quantity;
    public String status;
}
