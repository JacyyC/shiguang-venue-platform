package com.example.venue.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UpdateManagedRoleRequest {
    @NotBlank(message = "角色名称不能为空")
    @Size(max = 64, message = "角色名称不能超过64个字符")
    public String roleName;

    @NotBlank(message = "角色状态不能为空")
    public String status;
}
