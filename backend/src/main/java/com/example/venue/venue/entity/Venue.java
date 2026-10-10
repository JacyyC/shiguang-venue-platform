package com.example.venue.venue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
import java.time.LocalTime;

@TableName("venue")
public class Venue {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String name;
    public String address;
    public String area;
    public String phone;
    public LocalTime openTime;
    public LocalTime closeTime;
    public String description;
    public String image;
    public String status;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
