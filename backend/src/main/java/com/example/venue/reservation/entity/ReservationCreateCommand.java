package com.example.venue.reservation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 预约创建的请求身份与结果核对记录。
 *
 * <p>归属预约域：一个用户的一次提交意图（Idempotency-Key + 载荷指纹）只对应一条预约。
 * 只保存请求指纹与业务主键，不保存联系人等个人信息正文。</p>
 */
@TableName("reservation_create_command")
public class ReservationCreateCommand {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String idempotencyKey;
    public String requestHash;
    public String status;
    public Long reservationId;
    public String resultStatus;
    public LocalDateTime createTime;
    public LocalDateTime completedAt;
}
