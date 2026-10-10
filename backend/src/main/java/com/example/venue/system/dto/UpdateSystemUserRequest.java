package com.example.venue.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.example.venue.user.validation.OptionalChinaMobile;

public class UpdateSystemUserRequest {
    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名长度不能超过 64 位")
    public String username;

    @Size(max = 64, message = "昵称长度不能超过 64 位")
    public String nickname;

    @OptionalChinaMobile
    public String phone;

    @Email(message = "邮箱格式不合法")
    @Size(max = 128, message = "邮箱长度不能超过 128 位")
    public String email;

    @NotBlank(message = "用户状态不能为空")
    public String status;
}
