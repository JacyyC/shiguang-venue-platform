package com.example.venue.system.dto;

import jakarta.validation.constraints.NotBlank;

public class UpdateConfigRequest {
    @NotBlank(message = "配置值不能为空")
    public String value;
}
