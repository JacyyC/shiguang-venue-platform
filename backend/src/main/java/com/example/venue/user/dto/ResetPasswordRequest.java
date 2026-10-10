package com.example.venue.user.dto;

import com.example.venue.user.validation.Utf8ByteSize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ResetPasswordRequest {
    @NotBlank(message = "重置凭证不能为空")
    public String token;

    @NotBlank
    @Size(min = 6, max = 64)
    @Utf8ByteSize(max = 72)
    public String password;
}
