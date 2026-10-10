package com.example.venue.advertising.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 商业广告独立令牌服务。广告令牌不复用 R6 placement 令牌，数据库只保存 SHA-256 摘要。
 */
@Component
public class AdvertisingTokenService {
    private static final int TOKEN_BYTES = 32;
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

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

    public LocalDateTime earliestExpiry(LocalDateTime issuedAt, LocalDateTime campaignUntil) {
        LocalDateTime tokenExpiry = issuedAt.plus(TOKEN_TTL);
        if (campaignUntil != null && campaignUntil.isBefore(tokenExpiry)) {
            return campaignUntil;
        }
        return tokenExpiry;
    }

    public record GeneratedToken(String raw, String hash) {
    }
}
