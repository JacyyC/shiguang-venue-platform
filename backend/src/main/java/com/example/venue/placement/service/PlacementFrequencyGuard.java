package com.example.venue.placement.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 频控 Redis 提示。仅作加速：决策时提前读取热点计数，若提示已达上限仍以 MySQL guard 为准；
 * 事件接受后递增提示。Redis 不可用或数据丢失时完整走 MySQL 事件计数，不影响正确性。
 * MySQL 复核失败则决策失败关闭，首页静默降级，不得绕过上限。
 */
@Component
public class PlacementFrequencyGuard {

    private static final String KEY_PREFIX = "placement:freq:";

    private final StringRedisTemplate redisTemplate;

    public PlacementFrequencyGuard(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    /**
     * 读取 Redis 频控提示计数。Redis 不可用或异常时返回 null，调用方走 MySQL 权威计数。
     */
    public Long tryReadHint(Long userId, Long slotId, Long candidateVersionId, int windowHours) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            String value = redisTemplate.opsForValue().get(key(userId, slotId, candidateVersionId));
            if (value == null) {
                return 0L;
            }
            return Long.parseLong(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 事件接受后递增 Redis 提示。失败静默降级，MySQL 仍是权威。
     */
    public void recordAccepted(Long userId, Long slotId, Long candidateVersionId, int windowHours) {
        if (redisTemplate == null) {
            return;
        }
        try {
            String key = key(userId, slotId, candidateVersionId);
            Long incremented = redisTemplate.opsForValue().increment(key);
            if (incremented != null && incremented == 1L) {
                redisTemplate.expire(key, Duration.ofHours(Math.max(1, windowHours)));
            }
        } catch (RuntimeException ignored) {
            // Redis 故障不影响 MySQL 权威计数。
        }
    }

    /**
     * 测试与运维入口：清空 Redis 提示以验证降级路径。失败静默。
     */
    public void clearHint(Long userId, Long slotId, Long candidateVersionId) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(key(userId, slotId, candidateVersionId));
        } catch (RuntimeException ignored) {
            // 降级路径由 MySQL 兜底。
        }
    }

    public boolean isRedisAvailable() {
        return redisTemplate != null;
    }

    private String key(Long userId, Long slotId, Long candidateVersionId) {
        return KEY_PREFIX + userId + ":" + slotId + ":" + candidateVersionId;
    }
}
