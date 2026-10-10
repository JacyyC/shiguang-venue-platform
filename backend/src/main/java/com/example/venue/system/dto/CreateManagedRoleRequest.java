package com.example.venue.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CreateManagedRoleRequest {
    @NotBlank(message = "角色名称不能为空")
    @Size(max = 64, message = "角色名称不能超过64个字符")
    public String roleName;

    @NotBlank(message = "角色标识不能为空")
    @Size(max = 64, message = "角色标识不能超过64个字符")
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]*", message = "角色标识只能包含字母、数字和下划线，且必须以字母开头")
    public String roleKey;
}
