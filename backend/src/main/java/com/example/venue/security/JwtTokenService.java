package com.example.venue.security;

import com.example.venue.common.CurrentUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class JwtTokenService {
    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);
    static final String DEVELOPMENT_DEFAULT_SECRET = "change-this-development-secret-change-this-development-secret";
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");
    // 项目约定的开发/测试 profile：dev（本机开发）、test（CI/集成测试）。
    // 历史 development/local 别名未在代码、脚本或 application.yml 中使用，保留会掩盖未启用 dev/test 的非开发环境；
    // validateSecret 的错误消息也要求显式启用 dev/test，此处与之一致。prod/production 双别名保留作对称。
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "test");
    private static final String REVOKED_TOKEN_PREFIX = "jwt:revoked:";
    private static final String CREDENTIAL_VERSION_CLAIM = "credentialVersion";

    private final JwtProperties properties;
    private final SecretKey secretKey;
    private final StringRedisTemplate redisTemplate;

    public JwtTokenService(JwtProperties properties, Environment environment, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        validateSecret(environment);
        this.secretKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createToken(CurrentUser user) {
        return createToken(user, 0L);
    }

    public String createToken(CurrentUser user, long credentialVersion) {
        if (credentialVersion < 0) {
            throw new IllegalArgumentException("credentialVersion must not be negative");
        }
        Instant now = Instant.now();
        return Jwts.builder()
            .issuer(properties.getIssuer())
            .subject(String.valueOf(user.id()))
            .id(UUID.randomUUID().toString())
            .claim("username", user.username())
            .claim(CREDENTIAL_VERSION_CLAIM, credentialVersion)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(properties.getAccessTokenMinutes() * 60)))
            .signWith(secretKey)
            .compact();
    }

    public Long parseUserId(String token) {
        return parseToken(token).userId();
    }

    public TokenPayload parseToken(String token) {
        if (isRevoked(token)) {
            throw new IllegalStateException("Token has been revoked");
        }
        Claims claims = parseClaims(token);
        Object credentialVersion = claims.get(CREDENTIAL_VERSION_CLAIM);
        if (!(credentialVersion instanceof Number number) || number.longValue() < 0) {
            throw new IllegalStateException("JWT 凭据版本缺失或不合法");
        }
        return new TokenPayload(Long.valueOf(claims.getSubject()), number.longValue());
    }

    public void revokeToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        Claims claims = parseClaims(token);
        Date expiration = claims.getExpiration();
        if (expiration == null) {
            return;
        }
        long ttlSeconds = Duration.between(Instant.now(), expiration.toInstant()).getSeconds();
        if (ttlSeconds > 0) {
            redisTemplate.opsForValue().set(revokedKey(token), "1", Duration.ofSeconds(ttlSeconds));
        }
    }

    private Claims parseClaims(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
        return claims;
    }

    public long getAccessTokenSeconds() {
        return properties.getAccessTokenMinutes() * 60;
    }

    private boolean isRevoked(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(revokedKey(token)));
    }

    private String revokedKey(String token) {
        return REVOKED_TOKEN_PREFIX + sha256(token);
    }

    private String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private void validateSecret(Environment environment) {
        String secret = properties.getSecret();
        boolean productionProfile = hasProfile(environment, PRODUCTION_PROFILES);
        boolean developmentProfile = hasProfile(environment, DEVELOPMENT_PROFILES);
        if (secret == null || secret.isBlank()) {
            if (developmentProfile) {
                log.warn("JWT secret is blank; configure JWT_SECRET before deployment");
                // A26-P3-38：原放行分支仅 warn 后 return，但构造器紧接着执行
                // Keys.hmacShaKeyFor(properties.getSecret().getBytes(...))，空密钥不足 256 位
                // 会抛 WeakKeyException，导致"仅告警放行"语义实际不可达。fallback 到
                // DEVELOPMENT_DEFAULT_SECRET（长度足以通过 HS256 校验）以保证 dev/test 启动可达，
                // warn 已提示部署前必须配置真实密钥；生产与无 profile 路径仍抛 IllegalStateException。
                properties.setSecret(DEVELOPMENT_DEFAULT_SECRET);
                return;
            }
            throw new IllegalStateException(productionProfile
                ? "生产环境必须配置 JWT_SECRET"
                : "JWT_SECRET 未配置；开发/测试请显式启用 dev/test profile，生产必须配置 JWT_SECRET");
        }
        if (DEVELOPMENT_DEFAULT_SECRET.equals(secret) || containsPlaceholder(secret)) {
            if (developmentProfile) {
                log.warn("Using development/placeholder JWT_SECRET; replace it before deployment");
                return;
            }
            throw new IllegalStateException(productionProfile
                ? "生产环境不能使用默认或占位 JWT_SECRET"
                : "禁止使用默认或占位 JWT_SECRET；开发/测试请显式启用 dev/test profile，生产必须配置高强度密钥");
        }
    }

    private boolean hasProfile(Environment environment, Set<String> profiles) {
        return Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profiles.contains(profile == null ? "" : profile.toLowerCase(Locale.ROOT)));
    }

    private boolean containsPlaceholder(String secret) {
        String normalized = secret.toLowerCase();
        return normalized.contains("change-this") || normalized.contains("replace-with");
    }

    public record TokenPayload(Long userId, long credentialVersion) {
    }
}
