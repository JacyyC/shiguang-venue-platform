package com.example.venue.placement.service;

import com.example.venue.placement.source.PlacementSourceSnapshot;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 宣传决策令牌服务。生成 32 字节密码学随机不透明令牌，只返回原值，持久化 SHA-256。
 * 令牌过期取签发后 10 分钟、候选结束、来源结束中的最早值。
 */
@Component
public class PlacementTokenService {

    private static final int TOKEN_BYTES = 32;
    private static final java.time.Duration TOKEN_TTL = java.time.Duration.ofMinutes(10);

    private final SecureRandom random = new SecureRandom();

    public GeneratedToken generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new GeneratedToken(raw, hash(raw));
    }

    public String hash(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 取签发后 10 分钟、候选结束、来源结束中的最早值。任一上界为 null 时忽略。
     */
    public LocalDateTime earliestExpiry(LocalDateTime issuedAt,
                                        LocalDateTime candidateEffectiveUntil,
                                        PlacementSourceSnapshot source) {
        LocalDateTime tokenExpiry = issuedAt.plus(TOKEN_TTL);
        LocalDateTime latest = tokenExpiry;
        if (candidateEffectiveUntil != null && candidateEffectiveUntil.isBefore(latest)) {
            latest = candidateEffectiveUntil;
        }
        LocalDateTime sourceUntil = source == null ? null : source.sourceEffectiveUntil();
        if (sourceUntil != null && sourceUntil.isBefore(latest)) {
            latest = sourceUntil;
        }
        return latest;
    }

    public record GeneratedToken(String raw, String hash) {
    }
}
