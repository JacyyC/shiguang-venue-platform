package com.example.venue.system.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public class ReplaceRolePermissionsRequest {
    @NotNull(message = "权限菜单不能为空")
    public List<Long> menuIds;
}
