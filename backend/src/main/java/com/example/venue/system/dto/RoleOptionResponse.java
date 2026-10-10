package com.example.venue.system.dto;

import com.example.venue.system.entity.SysRole;

public record RoleOptionResponse(Long id, String name, String key, String dataScope) {
    public static RoleOptionResponse from(SysRole role) {
        return new RoleOptionResponse(role.id, role.roleName, role.roleKey, role.dataScope);
    }
}
