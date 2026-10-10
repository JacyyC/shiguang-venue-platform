package com.example.venue.reservation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@TableName("reservation_occupancy")
public class ReservationOccupancy {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long reservationId;
    public Long spaceId;
    public LocalDate slotDate;
    public LocalTime startTime;
    public LocalTime endTime;
    public Integer activeFlag;
    public Long ruleId;
    public String resourceCategorySnapshot;
    public String bookingMode;
    public Integer ruleVersionSnapshot;
    public Integer quantity;
    public String allocationSnapshotJson;
    public LocalDateTime releasedAt;
    public String releaseReason;
}
