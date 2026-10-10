package com.example.venue.advertising.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdvertisingFrequencyHintTest {
    @Mock private ObjectProvider<StringRedisTemplate> provider;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @Test
    void missingRedisReturnsNullAndKeepsMySqlPathAvailable() {
        when(provider.getIfAvailable()).thenReturn(null);
        AdvertisingFrequencyHint hint = new AdvertisingFrequencyHint(provider);

        assertThat(hint.isRedisAvailable()).isFalse();
        assertThat(hint.tryReadUserSlot(1L, 24)).isNull();
        hint.recordAccepted(1L, 2L, 24);
    }

    @Test
    void redisIsOnlyAReadHintAndAcceptedEventsSetExpiringCounters() {
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("advertising:freq:USER_SLOT:1:SLOT")).thenReturn("2");
        when(valueOperations.increment("advertising:freq:USER_SLOT:1:SLOT")).thenReturn(1L);
        when(valueOperations.increment("advertising:freq:USER_CREATIVE:1:2")).thenReturn(1L);

        AdvertisingFrequencyHint hint = new AdvertisingFrequencyHint(provider);

        assertThat(hint.tryReadUserSlot(1L, 24)).isEqualTo(2L);
        hint.recordAccepted(1L, 2L, 24);

        verify(redisTemplate).expire(eq("advertising:freq:USER_SLOT:1:SLOT"), eq(Duration.ofHours(24)));
        verify(redisTemplate).expire(eq("advertising:freq:USER_CREATIVE:1:2"), eq(Duration.ofHours(24)));
    }

    @Test
    void redisExceptionReturnsNullAndDoesNotBecomeAFalseFrequencyDecision() {
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis unavailable"));
        AdvertisingFrequencyHint hint = new AdvertisingFrequencyHint(provider);

        assertThat(hint.tryReadUserCreative(1L, 2L, 24)).isNull();
        hint.recordAccepted(1L, 2L, 24);
        verify(redisTemplate, never()).expire(anyString(), eq(Duration.ofHours(24)));
    }
}
