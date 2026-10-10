package com.example.venue.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("order_status_log")
public class OrderStatusLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long orderId;
    public String fromStatus;
    public String toStatus;
    public Long operatorId;
    public String reason;
    public LocalDateTime createTime;
}
