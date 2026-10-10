package com.example.venue.inventory.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@TableName("venue_business_calendar")
public class VenueBusinessCalendar {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long venueId;
    public LocalDate calendarDate;
    public LocalTime openTime;
    public LocalTime closeTime;
    public String businessStatus;
    public String reasonCode;
    public String reasonText;
    public String sourceType;
    public String providerEnv;
    public String providerName;
    public String externalRef;
    public String idempotencyKey;
    public String metadataJson;
    public Long createdBy;
    public Long updatedBy;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    public Integer activeFlag;
}
