package com.example.venue.system.dto;

import com.example.venue.user.entity.SysUser;

import java.time.LocalDateTime;

public class SystemUserResponse {
    public Long id;
    public String username;
    public String nickname;
    public String phone;
    public String email;
    public String avatar;
    public String status;
    public Long roleId;
    public LocalDateTime lastLoginTime;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;

    public static SystemUserResponse from(SysUser user) {
        SystemUserResponse response = new SystemUserResponse();
        response.id = user.id;
        response.username = user.username;
        response.nickname = user.nickname;
        response.phone = user.phone;
        response.email = user.email;
        response.avatar = user.avatar;
        response.status = user.status;
        response.roleId = user.roleId;
        response.lastLoginTime = user.lastLoginTime;
        response.createTime = user.createTime;
        response.updateTime = user.updateTime;
        return response;
    }
}
