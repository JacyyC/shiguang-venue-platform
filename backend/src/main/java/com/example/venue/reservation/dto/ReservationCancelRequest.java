package com.example.venue.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ReservationCancelRequest {
    @NotBlank(message = "请填写取消原因")
    @Size(max = 500, message = "取消原因不能超过 500 个字符")
    public String reasonText;
    @Size(max = 64)
    public String reasonCode;
}
