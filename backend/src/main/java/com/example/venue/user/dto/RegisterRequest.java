package com.example.venue.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.example.venue.user.validation.Utf8ByteSize;
import com.example.venue.user.validation.OptionalChinaMobile;

public class RegisterRequest {
    @NotBlank
    @Size(min = 3, max = 32)
    public String username;
    @NotBlank
    @Size(min = 6, max = 64)
    @Utf8ByteSize(max = 72)
    public String password;
    @Size(max = 64, message = "昵称长度不能超过 64 位")
    public String nickname;
    @OptionalChinaMobile
    public String phone;
    @Email
    @Size(max = 128, message = "邮箱长度不能超过 128 位")
    public String email;
}
