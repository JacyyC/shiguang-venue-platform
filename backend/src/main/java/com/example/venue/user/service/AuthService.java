package com.example.venue.user.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.Constants;
import com.example.venue.common.CurrentUser;
import com.example.venue.security.ClientIpResolver;
import com.example.venue.security.JwtTokenService;
import com.example.venue.security.SecurityUserService;
import com.example.venue.system.entity.SysLoginLog;
import com.example.venue.system.entity.SysRole;
import com.example.venue.system.entity.SysUserRole;
import com.example.venue.system.mapper.SysLoginLogMapper;
import com.example.venue.system.mapper.SysRoleMapper;
import com.example.venue.system.mapper.SysUserRoleMapper;
import com.example.venue.user.dto.AuthResponse;
import com.example.venue.user.dto.ForgotPasswordRequest;
import com.example.venue.user.dto.LoginRequest;
import com.example.venue.user.dto.PasswordResetRequestResponse;
import com.example.venue.user.dto.RegisterRequest;
import com.example.venue.user.dto.ResetPasswordRequest;
import com.example.venue.user.dto.UserProfile;
import com.example.venue.user.entity.PasswordResetToken;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.PasswordResetTokenMapper;
import com.example.venue.user.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class AuthService {
    private static final String DUMMY_BCRYPT_HASH = "$2a$10$wR2t5wjjDliV5rMLTlx5Y.5ocWM8fYCInWw5mRKbXmeNh0YCjWmyS";
    private static final int PASSWORD_RESET_TOKEN_BYTES = 32;
    private static final int LOGIN_LOG_USERNAME_MAX_LENGTH = 64;
    private static final int LOGIN_LOG_USER_AGENT_MAX_LENGTH = 512;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final SecurityUserService securityUserService;
    private final PasswordResetTokenMapper passwordResetTokenMapper;
    private final ClientIpResolver clientIpResolver;
    private final boolean devTokenDelivery;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(SysUserMapper userMapper, SysRoleMapper roleMapper, SysUserRoleMapper userRoleMapper,
                       SysLoginLogMapper loginLogMapper, PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService, SecurityUserService securityUserService,
                       PasswordResetTokenMapper passwordResetTokenMapper,
                       ClientIpResolver clientIpResolver,
                       @Value("${app.auth.password-reset.dev-token-delivery:false}") boolean devTokenDelivery) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.loginLogMapper = loginLogMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.securityUserService = securityUserService;
        this.passwordResetTokenMapper = passwordResetTokenMapper;
        this.clientIpResolver = clientIpResolver;
        this.devTokenDelivery = devTokenDelivery;
    }

    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        SysUser user = userMapper.findByUsername(request.username);
        String passwordHash = user == null ? DUMMY_BCRYPT_HASH : user.password;
        if (!passwordEncoder.matches(request.password, passwordHash) || user == null) {
            writeLoginLog(null, request.username, httpRequest, "FAIL", "用户名或密码错误");
            throw new BadCredentialsException("用户名或密码错误");
        }
        if (!Constants.ACTIVE.equals(user.status)) {
            writeLoginLog(user.id, user.username, httpRequest, "FAIL", "用户已被禁用");
            throw new DisabledException("用户已被禁用");
        }
        LocalDateTime loginTime = LocalDateTime.now();
        if (userMapper.updateLastLoginTime(user.id, loginTime, loginTime) == 0) {
            writeLoginLog(user.id, user.username, httpRequest, "FAIL", "用户已被禁用");
            throw new DisabledException("用户已被禁用");
        }
        user.lastLoginTime = loginTime;
        CurrentUser currentUser = securityUserService.toCurrentUser(user);
        String token = jwtTokenService.createToken(currentUser, credentialVersion(user));
        writeLoginLog(user.id, user.username, httpRequest, "SUCCESS", "登录成功");
        return new AuthResponse(token, jwtTokenService.getAccessTokenSeconds(), UserProfile.from(user), currentUser.roles(), currentUser.permissions());
    }

    public void logout(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(Constants.TOKEN_PREFIX)) {
            return;
        }
        jwtTokenService.revokeToken(authorizationHeader.substring(Constants.TOKEN_PREFIX.length()));
    }

    @Transactional
    public PasswordResetRequestResponse requestPasswordReset(ForgotPasswordRequest request, HttpServletRequest httpRequest) {
        SysUser user = userMapper.findByUsername(request.username);
        String deliveredToken = null;
        if (user != null && Constants.ACTIVE.equals(user.status)) {
            LocalDateTime now = LocalDateTime.now();
            passwordResetTokenMapper.invalidateActiveByUserId(user.id, now);
            String rawToken = newResetToken();
            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.userId = user.id;
            resetToken.tokenHash = sha256(rawToken);
            resetToken.expiresAt = now.plusMinutes(15);
            resetToken.createTime = now;
            passwordResetTokenMapper.insert(resetToken);
            // DEV 止血闸门：仅当显式开启时返回 token 原文，便于联调闭环；生产环境保持关闭，改由通知渠道投递（S03b）。
            // 不持久化明文，只存哈希；token 为 null 时经 Jackson non_null 配置不输出字段，避免泄露用户是否存在。
            if (devTokenDelivery) {
                deliveredToken = rawToken;
            }
        }
        writeLoginLog(user == null ? null : user.id, request.username, httpRequest, "INFO", "找回密码请求已提交");
        return new PasswordResetRequestResponse(deliveredToken);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        LocalDateTime now = LocalDateTime.now();
        PasswordResetToken resetToken = passwordResetTokenMapper.findActiveByHashForUpdate(sha256(request.token), now);
        if (resetToken == null) {
            throw BusinessException.badRequest("重置凭证无效或已过期");
        }
        SysUser user = userMapper.selectById(resetToken.userId);
        if (user == null || !Constants.ACTIVE.equals(user.status)
            || userMapper.resetPassword(user.id, passwordEncoder.encode(request.password), now, userVersion(user)) == 0
            || passwordResetTokenMapper.consume(resetToken.id, now) == 0) {
            throw BusinessException.conflict("重置凭证已失效，请重新申请");
        }
    }

    @Transactional
    public void register(RegisterRequest request) {
        if (userMapper.findByUsername(request.username) != null) {
            throw BusinessException.conflict("用户名已存在");
        }
        SysRole userRole = roleMapper.selectOne(new QueryWrapper<SysRole>().eq("role_key", Constants.ROLE_USER).last("limit 1"));
        if (userRole == null) {
            throw BusinessException.notFound("默认角色未初始化");
        }
        SysUser user = new SysUser();
        user.username = request.username;
        user.password = passwordEncoder.encode(request.password);
        user.nickname = request.nickname == null ? request.username : request.nickname;
        user.phone = request.phone;
        user.email = request.email;
        user.status = Constants.ACTIVE;
        user.roleId = userRole.id;
        user.createTime = LocalDateTime.now();
        user.updateTime = user.createTime;
        userMapper.insert(user);

        SysUserRole relation = new SysUserRole();
        relation.userId = user.id;
        relation.roleId = userRole.id;
        userRoleMapper.insert(relation);
    }

    private void writeLoginLog(Long userId, String username, HttpServletRequest request, String status, String message) {
        SysLoginLog log = new SysLoginLog();
        log.userId = userId;
        log.username = truncateToCodePoints(username, LOGIN_LOG_USERNAME_MAX_LENGTH);
        // 复用 RateLimitFilter 的可信代理 IP 解析，避免登录日志全部记成反向代理内网地址（A26-P2-26）。
        log.ip = clientIpResolver.resolveClientIp(request);
        log.userAgent = truncateToCodePoints(request.getHeader("User-Agent"), LOGIN_LOG_USER_AGENT_MAX_LENGTH);
        log.status = status;
        log.message = message;
        log.createTime = LocalDateTime.now();
        loginLogMapper.insert(log);
    }

    private String truncateToCodePoints(String value, int maxCodePoints) {
        if (value == null || value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private long credentialVersion(SysUser user) {
        return user.credentialVersion == null ? 0L : user.credentialVersion;
    }

    private long userVersion(SysUser user) {
        return user.userVersion == null ? 0L : user.userVersion;
    }

    private String newResetToken() {
        byte[] bytes = new byte[PASSWORD_RESET_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
