package com.example.venue.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RefundApplyRequest {
    @NotBlank
    @Size(max = 512, message = "退款原因长度不能超过 512 位")
    public String reason;
}
