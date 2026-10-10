package com.example.venue.checkin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("reservation_lifecycle_event")
public class ReservationLifecycleEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long reservationId;
    public Long lifecycleId;
    public String eventType;
    public String fromStatus;
    public String toStatus;
    public Long actorUserId;
    public String sourceType;
    public String reasonCode;
    public String reasonText;
    public String relatedEntityType;
    public Long relatedEntityId;
    public String idempotencyKey;
    public String metadataJson;
    public LocalDateTime createTime;
}
