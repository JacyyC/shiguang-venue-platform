package com.example.venue.promotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("coupon_status_log")
public class CouponStatusLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userCouponId;
    public String fromStatus;
    public String toStatus;
    public String reasonCode;
    public String reasonText;
    public Long operatorId;
    public String sourceType;
    public Long reservationId;
    public Long orderId;
    public String metadataJson;
    public LocalDateTime createTime;
}
