package com.example.venue.security;

import com.example.venue.common.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtTokenServiceTest {
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void productionProfileRejectsDefaultJwtSecret() {
        JwtProperties properties = propertiesWithDefaultSecret();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new JwtTokenService(properties, environment, redisTemplate))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("生产环境不能使用默认或占位 JWT_SECRET");
    }

    @Test
    void productionProfileRejectsPlaceholderJwtSecret() {
        JwtProperties properties = propertiesWithSecret("replace-with-production-secret-replace-with-production-secret");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new JwtTokenService(properties, environment, redisTemplate))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("生产环境不能使用默认或占位 JWT_SECRET");
    }

    @Test
    void productionProfileRejectsBlankJwtSecret() {
        JwtProperties properties = propertiesWithSecret(" ");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new JwtTokenService(properties, environment, redisTemplate))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("生产环境必须配置 JWT_SECRET");
    }

    @Test
    void noProfileRejectsDefaultJwtSecret() {
        // 默认 docker / 裸机启动无 profile，仓库内默认密钥必须被拒绝，闭合 AUD-P0-01。
        JwtProperties properties = propertiesWithDefaultSecret();
        MockEnvironment environment = new MockEnvironment();

        assertThatThrownBy(() -> new JwtTokenService(properties, environment, redisTemplate))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("禁止使用默认或占位 JWT_SECRET；开发/测试请显式启用 dev/test profile，生产必须配置高强度密钥");
    }

    @Test
    void noProfileRejectsBlankJwtSecret() {
        JwtProperties properties = propertiesWithSecret("");
        MockEnvironment environment = new MockEnvironment();

        assertThatThrownBy(() -> new JwtTokenService(properties, environment, redisTemplate))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("JWT_SECRET 未配置；开发/测试请显式启用 dev/test profile，生产必须配置 JWT_SECRET");
    }

    @Test
    void noProfileRejectsPlaceholderJwtSecret() {
        JwtProperties properties = propertiesWithSecret("replace-with-a-long-random-secret-at-least-32-characters");
        MockEnvironment environment = new MockEnvironment();

        assertThatThrownBy(() -> new JwtTokenService(properties, environment, redisTemplate))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("禁止使用默认或占位 JWT_SECRET；开发/测试请显式启用 dev/test profile，生产必须配置高强度密钥");
    }

    @Test
    void developmentProfileAllowsDefaultJwtSecretForTestsAndLocalDev() {
        JwtProperties properties = propertiesWithDefaultSecret();
        MockEnvironment environment = devEnvironment();

        assertThatCode(() -> new JwtTokenService(properties, environment, redisTemplate)).doesNotThrowAnyException();
    }

    @Test
    void developmentProfileAllowsPlaceholderJwtSecretWithWarning() {
        JwtProperties properties = propertiesWithSecret("replace-with-a-long-random-secret-at-least-32-characters");
        MockEnvironment environment = devEnvironment();

        assertThatCode(() -> new JwtTokenService(properties, environment, redisTemplate)).doesNotThrowAnyException();
    }

    // A26-P3-38：dev/test profile 下空 JWT_SECRET 仅告警放行的分支此前不可达：
    // validateSecret warn 后 return，构造器紧接着执行 Keys.hmacShaKeyFor(空字节)
    // 会抛 WeakKeyException（不足 256 位）。修复后 fallback 到 DEVELOPMENT_DEFAULT_SECRET
    // 保证启动可达，并保留 warn 提示部署前必须配置真实密钥。
    @Test
    void developmentProfileAllowsBlankJwtSecretWithoutWeakKeyException() {
        JwtProperties properties = propertiesWithSecret("");
        MockEnvironment environment = devEnvironment();

        assertThatCode(() -> new JwtTokenService(properties, environment, redisTemplate))
            .as("dev profile 下空 JWT_SECRET 应仅告警放行，不应抛 WeakKeyException")
            .doesNotThrowAnyException();
    }

    @Test
    void testProfileAllowsBlankJwtSecretWithoutWeakKeyException() {
        JwtProperties properties = propertiesWithSecret(null);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        assertThatCode(() -> new JwtTokenService(properties, environment, redisTemplate))
            .as("test profile 下 null JWT_SECRET 应仅告警放行，不应抛 WeakKeyException")
            .doesNotThrowAnyException();
    }

    @Test
    void developmentProfileBlankJwtSecretFallbackIssuesValidTokens() {
        JwtProperties properties = propertiesWithSecret("");
        JwtTokenService tokenService = new JwtTokenService(properties, devEnvironment(), redisTemplate);

        // fallback 到 DEVELOPMENT_DEFAULT_SECRET 后，createToken/parseToken 闭环应正常工作，
        // 证明 secretKey 已用足够长度的密钥构造。
        String token = tokenService.createToken(new CurrentUser(7L, "fallback", Set.of("USER"), Set.of()));
        JwtTokenService.TokenPayload payload = tokenService.parseToken(token);

        assertThat(payload.userId()).isEqualTo(7L);
        assertThat(payload.credentialVersion()).isZero();
    }

    @Test
    void revokedTokenCannotBeParsedAgain() {
        JwtTokenService tokenService = new JwtTokenService(propertiesWithDefaultSecret(), devEnvironment(), redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String token = tokenService.createToken(new CurrentUser(9L, "tester", Set.of("USER"), Set.of("user:view")));

        tokenService.revokeToken(token);
        verify(valueOperations).set(startsWith("jwt:revoked:"), any(), any(java.time.Duration.class));

        when(redisTemplate.hasKey(startsWith("jwt:revoked:"))).thenReturn(true);
        assertThatThrownBy(() -> tokenService.parseUserId(token))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Token has been revoked");
    }

    @Test
    void tokenCarriesTheCredentialVersionUsedForImmediateInvalidationChecks() {
        JwtTokenService tokenService = new JwtTokenService(propertiesWithDefaultSecret(), devEnvironment(), redisTemplate);
        String token = tokenService.createToken(new CurrentUser(9L, "tester", Set.of("USER"), Set.of("user:view")), 6L);

        JwtTokenService.TokenPayload payload = tokenService.parseToken(token);

        assertThat(payload.userId()).isEqualTo(9L);
        assertThat(payload.credentialVersion()).isEqualTo(6L);
    }

    private MockEnvironment devEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        return environment;
    }

    private JwtProperties propertiesWithDefaultSecret() {
        return propertiesWithSecret(JwtTokenService.DEVELOPMENT_DEFAULT_SECRET);
    }

    private JwtProperties propertiesWithSecret(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setIssuer("venue-reservation-system");
        properties.setSecret(secret);
        properties.setAccessTokenMinutes(120);
        return properties;
    }
}
