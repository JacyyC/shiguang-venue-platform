package com.example.venue.reservation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 只读报价请求：只携带选择事实，不含联系人。用于预约链路在填写联系方式前给出服务端价格。
 */
public class ReservationQuoteRequest {
    @NotNull
    public Long spaceId;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public List<AvailabilitySlot> slots;
    @Min(1)
    public Integer participantCount;
    public List<@NotNull Long> seatIds;
    public Long userCouponId;
}
