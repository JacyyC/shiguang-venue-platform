package com.example.venue.funnel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("user_venue_favorite")
public class UserVenueFavorite {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long venueId;
    public String sourceChannel;
    public String campaignId;
    public String status;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
