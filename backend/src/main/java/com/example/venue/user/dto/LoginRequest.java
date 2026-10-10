package com.example.venue.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class LoginRequest {
    @NotBlank
    @Size(max = 64, message = "用户名长度不能超过 64 位")
    public String username;
    @NotBlank
    public String password;
}
