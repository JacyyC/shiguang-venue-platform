package com.example.venue.community.service;

import com.example.venue.common.BusinessException;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class CommunityTextPolicy {
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern CONTACT_ACCOUNT = Pattern.compile(
        "(?i)(?:微信|wechat|wx|qq)(?:号|账号)?\\s*[:：]?\\s*[A-Za-z0-9_-]{5,}");
    private static final Pattern PRIVATE_LOCATION = Pattern.compile(
        "(?:住址|家庭地址|门牌号|宿舍号)\\s*[:：]");

    public String required(String value, int maxLength, String fieldName) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw BusinessException.badRequest(fieldName + "不能为空");
        }
        if (normalized.length() > maxLength) {
            throw BusinessException.badRequest(fieldName + "长度不能超过" + maxLength + "个字符");
        }
        rejectPrivateInformation(normalized);
        return normalized;
    }

    public String optional(String value, int maxLength, String fieldName) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw BusinessException.badRequest(fieldName + "长度不能超过" + maxLength + "个字符");
        }
        rejectPrivateInformation(normalized);
        return normalized;
    }

    public String idempotencyKey(String value) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty() || normalized.length() > 128) {
            throw BusinessException.badRequest("Idempotency-Key 必须为 1 到 128 个字符");
        }
        return normalized;
    }

    public String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (values != null) {
                for (Object value : values) {
                    byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                    digest.update((byte) (bytes.length >>> 24));
                    digest.update((byte) (bytes.length >>> 16));
                    digest.update((byte) (bytes.length >>> 8));
                    digest.update((byte) bytes.length);
                    digest.update(bytes);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public String scopedIdempotencyKey(String scope, String rawKey) {
        String key = idempotencyKey(rawKey);
        String normalizedScope = scope == null ? "COMMUNITY" : scope.trim().toUpperCase(Locale.ROOT);
        if (normalizedScope.isEmpty()) {
            normalizedScope = "COMMUNITY";
        }
        if (normalizedScope.length() > 48) {
            normalizedScope = fingerprint(normalizedScope).substring(0, 48);
        }
        return normalizedScope + ":" + fingerprint(normalizedScope, key);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String plainText = Jsoup.clean(value.trim(), Safelist.none()).trim();
        return plainText.isEmpty() ? null : plainText;
    }

    private void rejectPrivateInformation(String value) {
        if (PHONE.matcher(value).find() || EMAIL.matcher(value).find()
            || CONTACT_ACCOUNT.matcher(value).find() || PRIVATE_LOCATION.matcher(value).find()) {
            throw BusinessException.badRequest("社区内容不能包含联系方式或私人精确位置");
        }
    }
}
