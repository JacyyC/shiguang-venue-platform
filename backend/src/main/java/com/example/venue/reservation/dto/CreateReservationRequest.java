package com.example.venue.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public class CreateReservationRequest {
    @NotNull
    public Long spaceId;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public List<AvailabilitySlot> slots;
    @Min(1)
    public Integer participantCount;
    public List<@NotNull Long> seatIds;
    public Long userCouponId;
    @NotBlank
    @Size(max = 64)
    public String contactName;
    @NotBlank
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入 11 位有效手机号")
    public String contactPhone;
    @Size(max = 200, message = "备注不能超过 200 个字符")
    public String remark;
}
