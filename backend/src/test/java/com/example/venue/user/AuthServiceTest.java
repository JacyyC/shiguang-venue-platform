package com.example.venue.user;

import com.example.venue.common.Constants;
import com.example.venue.common.CurrentUser;
import com.example.venue.security.ClientIpResolver;
import com.example.venue.security.JwtTokenService;
import com.example.venue.security.SecurityUserService;
import com.example.venue.system.entity.SysLoginLog;
import com.example.venue.system.mapper.SysLoginLogMapper;
import com.example.venue.system.mapper.SysRoleMapper;
import com.example.venue.system.mapper.SysUserRoleMapper;
import com.example.venue.user.dto.AuthResponse;
import com.example.venue.user.dto.ForgotPasswordRequest;
import com.example.venue.user.dto.LoginRequest;
import com.example.venue.user.dto.PasswordResetRequestResponse;
import com.example.venue.user.dto.RegisterRequest;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import com.example.venue.user.mapper.PasswordResetTokenMapper;
import com.example.venue.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.example.venue.user.dto.ResetPasswordRequest;
import com.example.venue.user.entity.PasswordResetToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private SysLoginLogMapper loginLogMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenService jwtTokenService;
    @Mock
    private SecurityUserService securityUserService;
    @Mock
    private PasswordResetTokenMapper passwordResetTokenMapper;
    // 使用真实 ClientIpResolver 实例（而非 @Mock），使 P2-26 测试能验证可信代理判定与
    // X-Real-IP / X-Forwarded-For 头解析的真实行为；现有测试因 request.getRemoteAddr()
    // 默认返回 null，resolveClientIp 返回 null，log.ip 行为与修复前一致。
    private final ClientIpResolver clientIpResolver = new ClientIpResolver("127.0.0.1,::1");
    @Mock
    private HttpServletRequest request;

    @Test
    void loginTouchesOnlyTheLoginColumnsAndIssuesTheCurrentCredentialVersion() {
        SysUser user = user();
        CurrentUser currentUser = new CurrentUser(7L, "alice", Set.of(Constants.ROLE_USER), Set.of("user:view"));
        when(userMapper.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("password", "password-hash")).thenReturn(true);
        when(userMapper.updateLastLoginTime(eq(7L), any(), any())).thenReturn(1);
        when(securityUserService.toCurrentUser(user)).thenReturn(currentUser);
        when(jwtTokenService.createToken(currentUser, 5L)).thenReturn("new-token");
        LoginRequest login = new LoginRequest();
        login.username = "alice";
        login.password = "password";

        AuthResponse response = service().login(login, request);

        assertThat(response.token).isEqualTo("new-token");
        verify(userMapper).updateLastLoginTime(eq(7L), any(), any());
        verify(jwtTokenService).createToken(currentUser, 5L);
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void loginStopsWhenAccountIsDisabledAfterPasswordVerification() {
        SysUser user = user();
        when(userMapper.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("password", "password-hash")).thenReturn(true);
        when(userMapper.updateLastLoginTime(eq(7L), any(), any())).thenReturn(0);
        LoginRequest login = new LoginRequest();
        login.username = "alice";
        login.password = "password";

        assertThatThrownBy(() -> service().login(login, request))
            .isInstanceOf(DisabledException.class)
            .hasMessage("用户已被禁用");

        verify(jwtTokenService, never()).createToken(any(CurrentUser.class), anyLong());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void loginUsesBcryptForMissingAccountsToo() {
        LoginRequest login = new LoginRequest();
        login.username = "missing";
        login.password = "password";

        assertThatThrownBy(() -> service().login(login, request)).hasMessage("用户名或密码错误");

        verify(passwordEncoder).matches(eq("password"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void loginLogSafelyTruncatesAnOversizedUserAgent() {
        SysUser user = user();
        CurrentUser currentUser = new CurrentUser(7L, "alice", Set.of(Constants.ROLE_USER), Set.of());
        String userAgent = "A".repeat(511) + "\uD83D\uDE80" + "B".repeat(20);
        when(userMapper.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("password", "password-hash")).thenReturn(true);
        when(userMapper.updateLastLoginTime(eq(7L), any(), any())).thenReturn(1);
        when(securityUserService.toCurrentUser(user)).thenReturn(currentUser);
        when(jwtTokenService.createToken(currentUser, 5L)).thenReturn("new-token");
        when(request.getHeader("User-Agent")).thenReturn(userAgent);
        LoginRequest login = new LoginRequest();
        login.username = "alice";
        login.password = "password";

        AuthResponse response = service().login(login, request);

        ArgumentCaptor<SysLoginLog> logCaptor = ArgumentCaptor.forClass(SysLoginLog.class);
        verify(loginLogMapper).insert(logCaptor.capture());
        assertThat(response.token).isEqualTo("new-token");
        assertThat(logCaptor.getValue().userAgent).hasSize(513);
        assertThat(logCaptor.getValue().userAgent.codePointCount(0, logCaptor.getValue().userAgent.length())).isEqualTo(512);
        assertThat(logCaptor.getValue().userAgent).endsWith("\uD83D\uDE80");
    }

    @Test
    void loginLogRecordsClientIpFromResolverInsteadOfRemoteAddr() {
        // A26-P2-26：标准部署下 nginx 始终在后端前面，request.getRemoteAddr() 永远是 nginx 内网地址。
        // 修复后 writeLoginLog 必须调 clientIpResolver.resolveClientIp 取真实客户端 IP，
        // 而不是 request.getRemoteAddr()。
        // 用独立 mock resolver 验证 AuthService 确实调用了 resolver（真实实例无法 stub）。
        ClientIpResolver mockResolver = org.mockito.Mockito.mock(ClientIpResolver.class);
        when(mockResolver.resolveClientIp(request)).thenReturn("203.0.113.9");
        SysUser user = user();
        CurrentUser currentUser = new CurrentUser(7L, "alice", Set.of(Constants.ROLE_USER), Set.of());
        when(userMapper.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("password", "password-hash")).thenReturn(true);
        when(userMapper.updateLastLoginTime(eq(7L), any(), any())).thenReturn(1);
        when(securityUserService.toCurrentUser(user)).thenReturn(currentUser);
        when(jwtTokenService.createToken(currentUser, 5L)).thenReturn("new-token");
        LoginRequest login = new LoginRequest();
        login.username = "alice";
        login.password = "password";

        AuthService authService = new AuthService(userMapper, roleMapper, userRoleMapper, loginLogMapper,
            passwordEncoder, jwtTokenService, securityUserService, passwordResetTokenMapper, mockResolver, false);
        authService.login(login, request);

        ArgumentCaptor<SysLoginLog> logCaptor = ArgumentCaptor.forClass(SysLoginLog.class);
        verify(loginLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().ip).isEqualTo("203.0.113.9");
        // 防御性断言：登录日志不应直接落 request.getRemoteAddr() 的反向代理地址。
        assertThat(logCaptor.getValue().ip).isNotEqualTo("172.18.0.2");
        verify(mockResolver).resolveClientIp(request);
    }

    @Test
    void identityInputDtosRejectValuesBeyondTheirPersistedColumns() {
        RegisterRequest register = new RegisterRequest();
        register.username = "u".repeat(65);
        register.password = "password";
        register.nickname = "n".repeat(65);
        register.phone = "1".repeat(33);
        register.email = "a".repeat(120) + "@example.test";
        LoginRequest login = new LoginRequest();
        login.username = "u".repeat(65);
        login.password = "password";
        ForgotPasswordRequest forgot = new ForgotPasswordRequest();
        forgot.username = "u".repeat(65);

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        try {
            assertThat(factory.getValidator().validate(register).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet()))
                .contains("username", "nickname", "phone", "email");
            assertThat(factory.getValidator().validate(login).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet()))
                .contains("username");
            assertThat(factory.getValidator().validate(forgot).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet()))
                .contains("username");
        } finally {
            factory.close();
        }
    }

    @Test
    void resetPasswordConsumesOnlyActiveTokenAndUpdatesCredentialVersion() throws Exception {
        String rawToken = "reset-token";
        PasswordResetToken token = new PasswordResetToken();
        token.id = 4L;
        token.userId = 7L;
        when(passwordResetTokenMapper.findActiveByHashForUpdate(eq(sha256(rawToken)), any())).thenReturn(token);
        when(userMapper.selectById(7L)).thenReturn(user());
        when(passwordEncoder.encode("new-password")).thenReturn("new-password-hash");
        when(userMapper.resetPassword(eq(7L), eq("new-password-hash"), any(), eq(3L))).thenReturn(1);
        when(passwordResetTokenMapper.consume(eq(4L), any())).thenReturn(1);
        ResetPasswordRequest reset = new ResetPasswordRequest();
        reset.token = rawToken;
        reset.password = "new-password";

        service().resetPassword(reset);

        verify(userMapper).resetPassword(eq(7L), eq("new-password-hash"), any(), eq(3L));
        verify(passwordResetTokenMapper).consume(eq(4L), any());
    }

    @Test
    void requestPasswordResetReturnsRawTokenWhenDevDeliveryEnabled() throws Exception {
        SysUser user = user();
        when(userMapper.findByUsername("alice")).thenReturn(user);
        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);

        PasswordResetRequestResponse response = serviceWithDevDelivery().requestPasswordReset(forgot(), request);

        verify(passwordResetTokenMapper).insert(tokenCaptor.capture());
        assertThat(response.token).isNotNull();
        assertThat(sha256(response.token)).isEqualTo(tokenCaptor.getValue().tokenHash);
        verify(loginLogMapper).insert(any(SysLoginLog.class));
    }

    @Test
    void requestPasswordResetOmitsTokenWhenDevDeliveryDisabled() {
        SysUser user = user();
        when(userMapper.findByUsername("alice")).thenReturn(user);

        PasswordResetRequestResponse response = service().requestPasswordReset(forgot(), request);

        assertThat(response.token).isNull();
        verify(passwordResetTokenMapper).insert(any(PasswordResetToken.class));
    }

    @Test
    void requestPasswordResetDoesNotLeakExistenceForUnknownUser() {
        when(userMapper.findByUsername("ghost")).thenReturn(null);
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.username = "ghost";

        PasswordResetRequestResponse response = serviceWithDevDelivery().requestPasswordReset(req, request);

        assertThat(response.token).isNull();
        verify(passwordResetTokenMapper, never()).insert(any(PasswordResetToken.class));
    }

    @Test
    void loginLogRecordsClientIpFromXRealIpWhenBehindTrustedProxy() {
        // A26-P2-26：标准部署下 nginx 在后端前，request.getRemoteAddr() 返回 nginx 内网地址。
        // 登录日志原直接记 getRemoteAddr()，导致暴力破解来源全部记成同一个内网地址。
        // 修复后复用 ClientIpResolver.resolveClientIp：直连可信代理时信任 X-Real-IP 头。
        SysUser user = user();
        CurrentUser currentUser = new CurrentUser(7L, "alice", Set.of(Constants.ROLE_USER), Set.of());
        when(userMapper.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("password", "password-hash")).thenReturn(true);
        when(userMapper.updateLastLoginTime(eq(7L), any(), any())).thenReturn(1);
        when(securityUserService.toCurrentUser(user)).thenReturn(currentUser);
        when(jwtTokenService.createToken(currentUser, 5L)).thenReturn("new-token");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1"); // nginx 直连地址，在可信代理列表中
        when(request.getHeader("X-Forwarded-For")).thenReturn(null); // ClientIpResolver 先查 X-Forwarded-For，未设头时回落 X-Real-IP
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.5"); // nginx 写入的真实客户端 IP
        LoginRequest login = new LoginRequest();
        login.username = "alice";
        login.password = "password";

        service().login(login, request);

        ArgumentCaptor<SysLoginLog> logCaptor = ArgumentCaptor.forClass(SysLoginLog.class);
        verify(loginLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().ip).isEqualTo("203.0.113.5");
    }

    @Test
    void loginLogRecordsRemoteAddrWhenNotBehindTrustedProxy() {
        // A26-P2-26：直连地址不在可信代理列表时，直接用 remoteAddr，防止攻击者伪造 X-Real-IP。
        // 此测试构造一个可信代理列表为空的 resolver，任意 remoteAddr 都不被信任。
        AuthService authService = new AuthService(userMapper, roleMapper, userRoleMapper, loginLogMapper,
            passwordEncoder, jwtTokenService, securityUserService, passwordResetTokenMapper,
            new ClientIpResolver(""), false);
        when(request.getRemoteAddr()).thenReturn("198.51.100.7"); // 直连客户端，非可信代理
        // ClientIpResolver("") 的可信代理列表为空，isTrustedProxy 恒 false，不查任何头，直接返回 remoteAddr。
        // 用 lenient 标注此 stub：测试意图是显示攻击者伪造了 X-Real-IP 头，但 resolver 不会查它。
        lenient().when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.5");
        LoginRequest login = new LoginRequest();
        login.username = "missing";
        login.password = "password";

        assertThatThrownBy(() -> authService.login(login, request)).hasMessage("用户名或密码错误");

        ArgumentCaptor<SysLoginLog> logCaptor = ArgumentCaptor.forClass(SysLoginLog.class);
        verify(loginLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().ip).isEqualTo("198.51.100.7");
    }

    private AuthService service() {
        return new AuthService(userMapper, roleMapper, userRoleMapper, loginLogMapper, passwordEncoder,
            jwtTokenService, securityUserService, passwordResetTokenMapper, clientIpResolver, false);
    }

    private AuthService serviceWithDevDelivery() {
        return new AuthService(userMapper, roleMapper, userRoleMapper, loginLogMapper, passwordEncoder,
            jwtTokenService, securityUserService, passwordResetTokenMapper, clientIpResolver, true);
    }

    private ForgotPasswordRequest forgot() {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.username = "alice";
        return req;
    }

    private SysUser user() {
        SysUser user = new SysUser();
        user.id = 7L;
        user.username = "alice";
        user.password = "password-hash";
        user.status = Constants.ACTIVE;
        user.deleted = 0;
        user.credentialVersion = 5L;
        user.userVersion = 3L;
        return user;
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
