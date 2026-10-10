package com.example.venue.venue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("venue_admin_relation")
public class VenueAdminRelation {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long venueId;
}
