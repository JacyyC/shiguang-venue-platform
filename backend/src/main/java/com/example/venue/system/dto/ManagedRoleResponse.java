package com.example.venue.system.dto;

import com.example.venue.system.entity.SysRole;

public record ManagedRoleResponse(Long id, String name, String key, String dataScope, String status,
                                  boolean builtIn) {
    public static ManagedRoleResponse from(SysRole role, boolean builtIn) {
        return new ManagedRoleResponse(role.id, role.roleName, role.roleKey, role.dataScope, role.status, builtIn);
    }
}
