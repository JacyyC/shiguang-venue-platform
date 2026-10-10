package com.example.venue.community.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.ResultCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class CommunityRateLimitRedisIntegrationTest {
    private static final long ELEVEN_MINUTES_MILLIS = 11 * 60 * 1000L;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("m.daocloud.io/docker.io/library/redis:7-alpine"))
        .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private CommunityRateLimitService service;

    @BeforeAll
    static void connectToRedis() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void closeRedisConnection() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void resetRedis() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        service = new CommunityRateLimitService(redisTemplate);
    }

    @Test
    void postAllowsFiveInTenMinutesAndRejectsTheSixth() {
        assertShortWindowBoundary(101L, CommunityRateLimitService.Action.POST, "post", 5);
    }

    @Test
    void postAllowsTwentyPerDayAndRejectsTheTwentyFirst() {
        assertDailyBoundaryWithOlderEntries(102L, CommunityRateLimitService.Action.POST, "post", 20);
    }

    @Test
    void commentAllowsThirtyInTenMinutesAndRejectsTheThirtyFirst() {
        assertShortWindowBoundary(201L, CommunityRateLimitService.Action.COMMENT, "comment", 30);
    }

    @Test
    void commentAllowsTwoHundredPerDayAndRejectsTheTwoHundredAndFirst() {
        assertDailyBoundaryWithOlderEntries(202L, CommunityRateLimitService.Action.COMMENT, "comment", 200);
    }

    @Test
    void reportAllowsTenPerDayAndRejectsTheEleventh() {
        assertDailyOnlyBoundary(301L, CommunityRateLimitService.Action.REPORT, "report", 10);
    }

    @Test
    void joinAllowsTwentyPerDayAndRejectsTheTwentyFirst() {
        assertDailyOnlyBoundary(401L, CommunityRateLimitService.Action.JOIN, "join", 20);
    }

    @Test
    void applyAllowsTwentyPerDayAndRejectsTheTwentyFirst() {
        assertDailyOnlyBoundary(501L, CommunityRateLimitService.Action.APPLY, "apply", 20);
    }

    @Test
    void exhaustedJoinQuotaStillLeavesTheApplyQuotaUntouched() {
        Long userId = 601L;
        for (int request = 0; request < 20; request++) {
            service.check(userId, CommunityRateLimitService.Action.JOIN);
        }
        assertTooManyRequests(() -> service.check(userId, CommunityRateLimitService.Action.JOIN));

        service.check(userId, CommunityRateLimitService.Action.APPLY);

        assertThat(redisTemplate.opsForZSet().zCard(baseKey("apply", userId) + ":day")).isEqualTo(1);
        assertThat(redisTemplate.opsForZSet().zCard(baseKey("join", userId) + ":day")).isEqualTo(20);
    }

    @Test
    void exhaustedApplyQuotaStillLeavesTheJoinQuotaUntouched() {
        Long userId = 602L;
        for (int request = 0; request < 20; request++) {
            service.check(userId, CommunityRateLimitService.Action.APPLY);
        }
        assertTooManyRequests(() -> service.check(userId, CommunityRateLimitService.Action.APPLY));

        service.check(userId, CommunityRateLimitService.Action.JOIN);

        assertThat(redisTemplate.opsForZSet().zCard(baseKey("join", userId) + ":day")).isEqualTo(1);
        assertThat(redisTemplate.opsForZSet().zCard(baseKey("apply", userId) + ":day")).isEqualTo(20);
    }

    private void assertShortWindowBoundary(Long userId, CommunityRateLimitService.Action action,
                                           String actionKey, int limit) {
        for (int request = 0; request < limit; request++) {
            service.check(userId, action);
        }

        String baseKey = baseKey(actionKey, userId);
        assertThat(redisTemplate.opsForZSet().zCard(baseKey + ":10m")).isEqualTo(limit);
        assertThat(redisTemplate.opsForZSet().zCard(baseKey + ":day")).isEqualTo(limit);

        assertTooManyRequests(() -> service.check(userId, action));

        assertThat(redisTemplate.opsForZSet().zCard(baseKey + ":10m")).isEqualTo(limit);
        assertThat(redisTemplate.opsForZSet().zCard(baseKey + ":day")).isEqualTo(limit);
    }

    private void assertDailyBoundaryWithOlderEntries(Long userId, CommunityRateLimitService.Action action,
                                                      String actionKey, int limit) {
        String baseKey = baseKey(actionKey, userId);
        seedDailyEntries(baseKey + ":day", limit - 1);

        service.check(userId, action);

        assertThat(redisTemplate.opsForZSet().zCard(baseKey + ":10m")).isOne();
        assertThat(redisTemplate.opsForZSet().zCard(baseKey + ":day")).isEqualTo(limit);

        assertTooManyRequests(() -> service.check(userId, action));

        assertThat(redisTemplate.opsForZSet().zCard(baseKey + ":10m")).isOne();
        assertThat(redisTemplate.opsForZSet().zCard(baseKey + ":day")).isEqualTo(limit);
    }

    private void assertDailyOnlyBoundary(Long userId, CommunityRateLimitService.Action action,
                                         String actionKey, int limit) {
        for (int request = 0; request < limit; request++) {
            service.check(userId, action);
        }

        String baseKey = baseKey(actionKey, userId);
        assertThat(redisTemplate.hasKey(baseKey + ":10m")).isFalse();
        assertThat(redisTemplate.opsForZSet().zCard(baseKey + ":day")).isEqualTo(limit);

        assertTooManyRequests(() -> service.check(userId, action));

        assertThat(redisTemplate.opsForZSet().zCard(baseKey + ":day")).isEqualTo(limit);
    }

    private void seedDailyEntries(String key, int count) {
        double score = Instant.now().minusMillis(ELEVEN_MINUTES_MILLIS).toEpochMilli();
        for (int index = 0; index < count; index++) {
            redisTemplate.opsForZSet().add(key, "older-request-" + index, score);
        }
    }

    private void assertTooManyRequests(Runnable request) {
        assertThatThrownBy(request::run)
            .isInstanceOf(BusinessException.class)
            .extracting(exception -> ((BusinessException) exception).getCode())
            .isEqualTo(ResultCode.TOO_MANY_REQUESTS);
    }

    private String baseKey(String actionKey, Long userId) {
        return "community:rate:" + actionKey + ":" + userId;
    }
}
