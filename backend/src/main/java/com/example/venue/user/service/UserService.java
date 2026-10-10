package com.example.venue.user.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.security.SecurityContext;
import com.example.venue.user.dto.AuthorizationSnapshot;
import com.example.venue.user.dto.ChangePasswordRequest;
import com.example.venue.user.dto.UpdateProfileRequest;
import com.example.venue.user.dto.UserProfile;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class UserService {
    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(SysUserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public UserProfile currentProfile() {
        return UserProfile.from(requireCurrentUserEntity());
    }

    public AuthorizationSnapshot currentAuthorization() {
        CurrentUser user = SecurityContext.currentUser();
        if (user == null) {
            throw BusinessException.forbidden("未登录");
        }
        return AuthorizationSnapshot.from(user);
    }

    @Transactional
    public UserProfile updateProfile(UpdateProfileRequest request) {
        SysUser user = requireCurrentUserEntity();
        LocalDateTime updateTime = LocalDateTime.now();
        if (userMapper.updateProfile(user.id, request.nickname, request.phone, request.email, request.avatar,
            updateTime, userVersion(user)) == 0) {
            throw BusinessException.conflict("用户资料已被其他操作更新，请刷新后重试");
        }
        user.nickname = request.nickname;
        user.phone = request.phone;
        user.email = request.email;
        user.avatar = request.avatar;
        user.updateTime = updateTime;
        user.userVersion = userVersion(user) + 1;
        return UserProfile.from(user);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        SysUser user = requireCurrentUserEntity();
        if (!passwordEncoder.matches(request.oldPassword, user.password)) {
            throw BusinessException.badRequest("原密码不正确");
        }
        if (userMapper.changePassword(user.id, passwordEncoder.encode(request.newPassword), user.password,
            LocalDateTime.now(), userVersion(user)) == 0) {
            throw BusinessException.conflict("账号状态或密码已变化，请重新登录后重试");
        }
    }

    private SysUser requireCurrentUserEntity() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.notFound("用户不存在");
        }
        return user;
    }

    private long userVersion(SysUser user) {
        return user.userVersion == null ? 0L : user.userVersion;
    }
}
