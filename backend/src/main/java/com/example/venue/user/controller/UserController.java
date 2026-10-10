package com.example.venue.user.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.Result;
import com.example.venue.notice.dto.TransactionalEmailPreferenceRequest;
import com.example.venue.notice.dto.TransactionalEmailPreferenceResponse;
import com.example.venue.notice.service.TransactionalEmailPreferenceService;
import com.example.venue.system.dto.MenuNode;
import com.example.venue.system.service.SystemService;
import com.example.venue.user.dto.ChangePasswordRequest;
import com.example.venue.user.dto.AuthorizationSnapshot;
import com.example.venue.user.dto.UpdateProfileRequest;
import com.example.venue.user.dto.UserProfile;
import com.example.venue.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;
    private final SystemService systemService;
    private final TransactionalEmailPreferenceService transactionalEmailPreferenceService;

    public UserController(
        UserService userService,
        SystemService systemService,
        TransactionalEmailPreferenceService transactionalEmailPreferenceService
    ) {
        this.userService = userService;
        this.systemService = systemService;
        this.transactionalEmailPreferenceService = transactionalEmailPreferenceService;
    }

    @GetMapping("/me")
    public Result<UserProfile> me() {
        return Result.ok(userService.currentProfile());
    }

    @GetMapping("/me/authorization")
    public Result<AuthorizationSnapshot> authorization() {
        return Result.ok(userService.currentAuthorization());
    }

    @GetMapping("/me/menus")
    public Result<List<MenuNode>> menus() {
        return Result.ok(systemService.currentMenus());
    }

    @GetMapping("/me/notification-preferences")
    public Result<TransactionalEmailPreferenceResponse> notificationPreferences() {
        return Result.ok(transactionalEmailPreferenceService.current());
    }

    @PutMapping("/me/notification-preferences")
    public Result<TransactionalEmailPreferenceResponse> updateNotificationPreferences(
        @Valid @RequestBody TransactionalEmailPreferenceRequest request
    ) {
        return Result.ok(transactionalEmailPreferenceService.update(request));
    }

    @AuditLog(module = "用户中心", action = "修改资料")
    @PutMapping("/me")
    public Result<UserProfile> updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return Result.ok(userService.updateProfile(request));
    }

    @AuditLog(module = "用户中心", action = "修改密码")
    @PutMapping("/me/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(request);
        return Result.ok();
    }
}
