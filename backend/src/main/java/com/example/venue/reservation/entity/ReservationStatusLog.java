package com.example.venue.reservation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("reservation_status_log")
public class ReservationStatusLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long reservationId;
    public String fromStatus;
    public String toStatus;
    public Long operatorId;
    public String reason;
    public String source;
    public LocalDateTime createTime;
}
