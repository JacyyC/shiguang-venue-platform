package com.example.venue.system.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.system.dto.CreateManagedRoleRequest;
import com.example.venue.system.dto.ManagedRoleResponse;
import com.example.venue.system.dto.PermissionMenuResponse;
import com.example.venue.system.dto.ReplaceRolePermissionsRequest;
import com.example.venue.system.dto.ResetUserPasswordRequest;
import com.example.venue.system.dto.RoleOptionResponse;
import com.example.venue.system.dto.SystemUserResponse;
import com.example.venue.system.dto.UpdateConfigRequest;
import com.example.venue.system.dto.UpdateManagedRoleRequest;
import com.example.venue.system.dto.UpdateSystemUserRequest;
import com.example.venue.system.dto.UpdateUserStatusRequest;
import com.example.venue.system.dto.UpdateVenueAdminAccessRequest;
import com.example.venue.system.entity.SysConfig;
import com.example.venue.system.entity.SysDictData;
import com.example.venue.system.entity.SysLoginLog;
import com.example.venue.system.entity.SysOperationLog;
import com.example.venue.system.service.SystemService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final SystemService systemService;

    public SystemController(SystemService systemService) {
        this.systemService = systemService;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('system:user:manage')")
    public Result<PageResult<SystemUserResponse>> users(@Valid PageQuery query, @RequestParam(required = false) String keyword) {
        return Result.ok(systemService.users(query, keyword));
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('system:user:manage')")
    public Result<List<RoleOptionResponse>> assignableRoles() {
        return Result.ok(systemService.assignableRoles());
    }

    @GetMapping("/role-management/roles")
    @PreAuthorize("hasAuthority('system:role:manage')")
    public Result<List<ManagedRoleResponse>> managedRoles() {
        return Result.ok(systemService.managedRoles());
    }

    @GetMapping("/role-management/permissions")
    @PreAuthorize("hasAuthority('system:role:manage')")
    public Result<List<PermissionMenuResponse>> permissionMenus() {
        return Result.ok(systemService.permissionMenus());
    }

    @GetMapping("/role-management/roles/{id}/permissions")
    @PreAuthorize("hasAuthority('system:role:manage')")
    public Result<List<Long>> rolePermissions(@PathVariable Long id) {
        return Result.ok(systemService.rolePermissionMenuIds(id));
    }

    @AuditLog(module = "角色权限", action = "创建动态角色")
    @PostMapping("/role-management/roles")
    @PreAuthorize("hasAuthority('system:role:manage')")
    public Result<ManagedRoleResponse> createRole(@Valid @RequestBody CreateManagedRoleRequest request) {
        return Result.ok(systemService.createManagedRole(request));
    }

    @AuditLog(module = "角色权限", action = "修改动态角色")
    @PutMapping("/role-management/roles/{id}")
    @PreAuthorize("hasAuthority('system:role:manage')")
    public Result<ManagedRoleResponse> updateRole(@PathVariable Long id,
                                                   @Valid @RequestBody UpdateManagedRoleRequest request) {
        return Result.ok(systemService.updateManagedRole(id, request));
    }

    @AuditLog(module = "角色权限", action = "替换角色权限")
    @PutMapping("/role-management/roles/{id}/permissions")
    @PreAuthorize("hasAuthority('system:role:manage')")
    public Result<Void> replaceRolePermissions(@PathVariable Long id,
                                               @Valid @RequestBody ReplaceRolePermissionsRequest request) {
        systemService.replaceRolePermissions(id, request.menuIds);
        return Result.ok();
    }

    @AuditLog(module = "系统用户", action = "修改用户资料")
    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('system:user:manage')")
    public Result<SystemUserResponse> updateUser(@PathVariable Long id, @Valid @RequestBody UpdateSystemUserRequest request) {
        return Result.ok(systemService.updateUser(id, request));
    }

    @AuditLog(module = "系统用户", action = "修改用户状态")
    @PutMapping("/users/{id}/status")
    @PreAuthorize("hasAuthority('system:user:manage')")
    public Result<Void> updateUserStatus(@PathVariable Long id, @Valid @RequestBody UpdateUserStatusRequest request) {
        systemService.updateUserStatus(id, request.status);
        return Result.ok();
    }

    @AuditLog(module = "系统用户", action = "分配角色")
    @PutMapping("/users/{id}/roles")
    @PreAuthorize("hasAuthority('system:user:manage')")
    public Result<Void> assignRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        systemService.assignRoles(id, roleIds);
        return Result.ok();
    }

    @GetMapping("/users/{id}/roles")
    @PreAuthorize("hasAuthority('system:user:manage')")
    public Result<List<Long>> userRoleIds(@PathVariable Long id) {
        return Result.ok(systemService.userRoleIds(id));
    }

    @GetMapping("/users/{id}/venue-access")
    @PreAuthorize("hasAuthority('system:user:manage')")
    public Result<List<Long>> venueAccessIds(@PathVariable Long id) {
        return Result.ok(systemService.venueAccessIds(id));
    }

    @AuditLog(module = "系统用户", action = "维护场馆授权")
    @PutMapping("/users/{id}/venue-access")
    @PreAuthorize("hasAuthority('system:user:manage')")
    public Result<Void> updateVenueAccess(@PathVariable Long id,
                                          @Valid @RequestBody UpdateVenueAdminAccessRequest request) {
        systemService.updateVenueAdminAccess(id, request.venueIds);
        return Result.ok();
    }

    @AuditLog(module = "系统用户", action = "重置用户密码")
    @PutMapping("/users/{id}/password")
    @PreAuthorize("hasAuthority('system:user:manage')")
    public Result<Void> resetUserPassword(@PathVariable Long id, @Valid @RequestBody ResetUserPasswordRequest request) {
        systemService.resetUserPassword(id, request.password);
        return Result.ok();
    }

    @AuditLog(module = "系统用户", action = "删除用户")
    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasAuthority('system:user:manage')")
    public Result<Void> deleteUser(@PathVariable Long id) {
        systemService.deleteUser(id);
        return Result.ok();
    }

    @GetMapping("/configs")
    @PreAuthorize("hasAuthority('system:config:manage')")
    public Result<List<SysConfig>> configs() {
        return Result.ok(systemService.configs());
    }

    @AuditLog(module = "系统参数", action = "修改参数")
    @PutMapping("/configs/{key}")
    @PreAuthorize("hasAuthority('system:config:manage')")
    public Result<Void> updateConfig(@PathVariable String key, @Valid @RequestBody UpdateConfigRequest request) {
        systemService.updateConfig(key, request.value);
        return Result.ok();
    }

    @GetMapping("/dicts/{type}")
    @PreAuthorize("isAuthenticated()")
    public Result<List<SysDictData>> dict(@PathVariable String type) {
        return Result.ok(systemService.dict(type));
    }

    @GetMapping("/logs/login")
    @PreAuthorize("hasAuthority('system:log:view')")
    public Result<PageResult<SysLoginLog>> loginLogs(@Valid PageQuery query) {
        return Result.ok(systemService.loginLogs(query));
    }

    @GetMapping("/logs/operation")
    @PreAuthorize("hasAuthority('system:log:view')")
    public Result<PageResult<SysOperationLog>> operationLogs(@Valid PageQuery query) {
        return Result.ok(systemService.operationLogs(query));
    }
}
