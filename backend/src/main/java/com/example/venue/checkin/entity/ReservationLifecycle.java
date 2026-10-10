package com.example.venue.checkin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("reservation_lifecycle")
public class ReservationLifecycle {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long reservationId;
    public Long orderId;
    public Long userId;
    public Long venueId;
    public Long spaceId;
    public String checkinCodeHash;
    public String checkinCodeLast4;
    public String credentialStatus;
    public LocalDateTime credentialIssuedAt;
    public LocalDateTime credentialExpiresAt;
    public LocalDateTime checkinWindowStart;
    public LocalDateTime checkinWindowEnd;
    public String attendanceStatus;
    public String checkinStatus;
    public String noShowStatus;
    public LocalDateTime checkedInAt;
    public Long checkedInBy;
    public String checkinSourceType;
    public String deviceRef;
    public Integer manualOverride;
    public Integer lateMinutes;
    public String reasonCode;
    public String reasonText;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
