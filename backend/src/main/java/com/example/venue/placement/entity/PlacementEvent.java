package com.example.venue.placement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("placement_event")
public class PlacementEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String eventNo;
    public String eventKey;
    public String eventType;
    public Long decisionId;
    public Long userId;
    public Long slotId;
    public Long candidateVersionId;
    public String requestHash;
    public BigDecimal visibleRatio;
    public Integer visibleDurationMs;
    public String resultStatus;
    public String rejectionCode;
    public LocalDateTime occurredAt;
    public LocalDateTime createTime;
}
