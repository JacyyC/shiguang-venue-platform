package com.example.venue.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("venue_resource_seat")
public class VenueResourceSeat {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long spaceId;
    public Long ruleId;
    public String seatCode;
    public String seatLabel;
    public String zoneCode;
    public Integer sortOrder;
    public String status;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
