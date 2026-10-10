package com.example.venue.system.dto;

import com.example.venue.system.entity.SysMenu;

public record PermissionMenuResponse(Long id, String name, Long parentId, String path, String permission,
                                     String menuType, String visible) {
    public static PermissionMenuResponse from(SysMenu menu) {
        return new PermissionMenuResponse(menu.id, menu.menuName, menu.parentId, menu.path, menu.perms,
            menu.menuType, menu.visible);
    }
}
