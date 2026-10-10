package com.example.venue.checkin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("reservation_reschedule_request")
public class ReservationRescheduleRequest {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long reservationId;
    public Long userId;
    public Long venueId;
    public Long spaceId;
    public LocalDateTime fromStartTime;
    public LocalDateTime fromEndTime;
    public LocalDateTime toStartTime;
    public LocalDateTime toEndTime;
    public String status;
    public String reasonCode;
    public String reasonText;
    public Long reviewedBy;
    public LocalDateTime reviewedAt;
    public String reviewReason;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
