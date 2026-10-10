package com.example.venue.advertising.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("advertising_advertiser")
public class AdvertisingAdvertiser {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String advertiserNo;
    public String advertiserName;
    public String status;
    public Long createdBy;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
