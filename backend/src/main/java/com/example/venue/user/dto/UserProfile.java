package com.example.venue.user.dto;

import com.example.venue.user.entity.SysUser;

public class UserProfile {
    public Long id;
    public String username;
    public String nickname;
    public String phone;
    public String email;
    public String avatar;
    public String status;

    public static UserProfile from(SysUser user) {
        UserProfile profile = new UserProfile();
        profile.id = user.id;
        profile.username = user.username;
        profile.nickname = user.nickname;
        profile.phone = user.phone;
        profile.email = user.email;
        profile.avatar = user.avatar;
        profile.status = user.status;
        return profile;
    }
}
