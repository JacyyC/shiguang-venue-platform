package com.example.venue.advertising.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 只保存商业频控提示，不参与最终放行。MySQL 计数和唯一约束失败关闭，Redis 故障静默降级。
 */
@Component
public class AdvertisingFrequencyHint {
    private static final String PREFIX = "advertising:freq:";

    private final StringRedisTemplate redisTemplate;

    public AdvertisingFrequencyHint(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    public Long tryReadUserSlot(Long userId, int windowHours) {
        return read(key("USER_SLOT", userId, null), windowHours);
    }

    public Long tryReadUserCreative(Long userId, Long creativeVersionId, int windowHours) {
        return read(key("USER_CREATIVE", userId, creativeVersionId), windowHours);
    }

    public void recordAccepted(Long userId, Long creativeVersionId, int windowHours) {
        if (redisTemplate == null) {
            return;
        }
        increment(key("USER_SLOT", userId, null), windowHours);
        increment(key("USER_CREATIVE", userId, creativeVersionId), windowHours);
    }

    public boolean isRedisAvailable() {
        return redisTemplate != null;
    }

    private Long read(String key, int windowHours) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            String value = redisTemplate.opsForValue().get(key);
            return value == null ? 0L : Long.parseLong(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void increment(String key, int windowHours) {
        try {
            Long value = redisTemplate.opsForValue().increment(key);
            if (value != null && value == 1L) {
                redisTemplate.expire(key, Duration.ofHours(Math.max(1, windowHours)));
            }
        } catch (RuntimeException ignored) {
            // MySQL remains authoritative.
        }
    }

    private String key(String kind, Long userId, Long creativeVersionId) {
        return PREFIX + kind + ":" + userId + ":" + (creativeVersionId == null ? "SLOT" : creativeVersionId);
    }
}
