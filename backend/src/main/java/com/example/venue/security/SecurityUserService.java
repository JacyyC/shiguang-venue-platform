package com.example.venue.security;

import com.example.venue.common.BusinessException;
import com.example.venue.common.Constants;
import com.example.venue.common.CurrentUser;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
public class SecurityUserService {
    private final SysUserMapper userMapper;

    public SecurityUserService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public SysUser loadEnabledUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.deleted != null && user.deleted == 1) {
            throw BusinessException.notFound("用户不存在");
        }
        if (!Constants.ACTIVE.equals(user.status)) {
            throw new DisabledException("用户已被禁用");
        }
        return user;
    }

    public CurrentUser toCurrentUser(SysUser user) {
        Set<String> roles = new HashSet<>(userMapper.findRoleKeys(user.id));
        Set<String> permissions = new HashSet<>(userMapper.findPermissions(user.id));
        return new CurrentUser(user.id, user.username, roles, permissions);
    }
}
