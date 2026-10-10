package com.example.venue.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import com.example.venue.user.validation.OptionalChinaMobile;

public class UpdateProfileRequest {
    @Size(max = 64, message = "昵称长度不能超过 64 位")
    public String nickname;
    @OptionalChinaMobile
    public String phone;
    @Email
    @Size(max = 128, message = "邮箱长度不能超过 128 位")
    public String email;
    @Size(max = 512, message = "头像长度不能超过 512 位")
    public String avatar;
}
