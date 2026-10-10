package com.example.venue.community.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityRateLimitTest {
    private static final long TEN_MINUTES_MILLIS = 600_000L;
    private static final long ONE_DAY_MILLIS = 86_400_000L;

    private StringRedisTemplate redisTemplate;
    private CommunityRateLimitService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        service = new CommunityRateLimitService(redisTemplate);
    }

    @Test
    void postUsesFivePerTenMinutesAndTwentyPerDay() {
        assertTwoWindowPolicy(CommunityRateLimitService.Action.POST, "post", 5, 20);
    }

    @Test
    void commentUsesThirtyPerTenMinutesAndTwoHundredPerDay() {
        assertTwoWindowPolicy(CommunityRateLimitService.Action.COMMENT, "comment", 30, 200);
    }

    @Test
    void reportUsesTenPerDayWithoutCreatingAnExtraTenMinuteBucket() {
        assertDailyPolicy(CommunityRateLimitService.Action.REPORT, "report", 10);
    }

    @Test
    void joinUsesTwentyPerDayWithoutCreatingAnExtraTenMinuteBucket() {
        assertDailyPolicy(CommunityRateLimitService.Action.JOIN, "join", 20);
    }

    @Test
    void applyUsesTwentyPerDayWithoutCreatingAnExtraTenMinuteBucket() {
        assertDailyPolicy(CommunityRateLimitService.Action.APPLY, "apply", 20);
    }

    @Test
    void applyAndJoinKeepSeparateBucketsForTheSameUser() {
        assertThat(dailyKeyOf(CommunityRateLimitService.Action.APPLY))
            .isEqualTo("community:rate:apply:7:day")
            .isNotEqualTo(dailyKeyOf(CommunityRateLimitService.Action.JOIN));
    }

    @Test
    void applyRejectionExplainsTheParticipationApplication() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        assertThatThrownBy(() -> service.check(7L, CommunityRateLimitService.Action.APPLY))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("参与申请过于频繁")
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.TOO_MANY_REQUESTS);
    }

    @Test
    void aRejectedWindowReturnsTooManyRequests() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        assertThatThrownBy(() -> service.check(7L, CommunityRateLimitService.Action.POST))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("发帖过于频繁")
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.TOO_MANY_REQUESTS);
    }

    @Test
    void redisExceptionOrNullResultFailsClosed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("redis unavailable"));

        assertUnavailable(() -> service.check(7L, CommunityRateLimitService.Action.POST));

        reset(redisTemplate);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

        assertUnavailable(() -> service.check(7L, CommunityRateLimitService.Action.REPORT));

        reset(redisTemplate);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("redis unavailable"));

        assertUnavailable(() -> service.check(7L, CommunityRateLimitService.Action.APPLY));
    }

    private String dailyKeyOf(CommunityRateLimitService.Action action) {
        reset(redisTemplate);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        service.check(7L, action);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> keys = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        return keys.getValue().get(keys.getValue().size() - 1);
    }

    private void assertTwoWindowPolicy(CommunityRateLimitService.Action action, String actionKey,
                                       int tenMinuteLimit, int dailyLimit) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        service.check(7L, action);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> keys = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.ArgumentCaptor<Object[]> arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), arguments.capture());
        assertThat(keys.getValue()).containsExactly(
            "community:rate:" + actionKey + ":7:10m",
            "community:rate:" + actionKey + ":7:day");
        assertThat(arguments.getValue()).hasSize(6);
        assertThat(arguments.getValue()[1]).asString().contains(":");
        assertThat(arguments.getValue()[2]).hasToString(String.valueOf(tenMinuteLimit));
        assertThat(arguments.getValue()[3]).hasToString(String.valueOf(TEN_MINUTES_MILLIS));
        assertThat(arguments.getValue()[4]).hasToString(String.valueOf(dailyLimit));
        assertThat(arguments.getValue()[5]).hasToString(String.valueOf(ONE_DAY_MILLIS));
    }

    private void assertDailyPolicy(CommunityRateLimitService.Action action, String actionKey, int dailyLimit) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        service.check(7L, action);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> keys = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.ArgumentCaptor<Object[]> arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), arguments.capture());
        assertThat(keys.getValue()).containsExactly("community:rate:" + actionKey + ":7:day");
        assertThat(arguments.getValue()).hasSize(4);
        assertThat(arguments.getValue()[1]).asString().contains(":");
        assertThat(arguments.getValue()[2]).hasToString(String.valueOf(dailyLimit));
        assertThat(arguments.getValue()[3]).hasToString(String.valueOf(ONE_DAY_MILLIS));
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("限流服务暂不可用")
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.SYSTEM_ERROR);
    }
}
