package com.example.venue.system.dto;

import jakarta.validation.constraints.NotBlank;

public class UpdateUserStatusRequest {
    @NotBlank(message = "用户状态不能为空")
    public String status;
}
