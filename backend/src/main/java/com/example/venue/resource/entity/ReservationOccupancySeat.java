package com.example.venue.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("reservation_occupancy_seat")
public class ReservationOccupancySeat {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long occupancyId;
    public Long reservationId;
    public Long seatId;
    public String seatCodeSnapshot;
    public String seatLabelSnapshot;
    public LocalDateTime createTime;
}
