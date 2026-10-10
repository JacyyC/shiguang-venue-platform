package com.example.venue.placement.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlacementFrequencyGuardTest {

    @Mock private ObjectProvider<StringRedisTemplate> provider;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    @Test
    void returnsNullHintWhenRedisUnavailable() {
        when(provider.getIfAvailable()).thenReturn(null);
        PlacementFrequencyGuard guard = new PlacementFrequencyGuard(provider);

        assertThat(guard.isRedisAvailable()).isFalse();
        assertThat(guard.tryReadHint(1L, 1L, 1L, 24)).isNull();
        guard.recordAccepted(1L, 1L, 1L, 24);
        guard.clearHint(1L, 1L, 1L);
    }

    @Test
    void returnsZeroWhenKeyMissingAndCachesAcceptedCount() {
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("placement:freq:1:2:3")).thenReturn(null);
        PlacementFrequencyGuard guard = new PlacementFrequencyGuard(provider);

        assertThat(guard.isRedisAvailable()).isTrue();
        assertThat(guard.tryReadHint(1L, 2L, 3L, 24)).isZero();
    }

    @Test
    void incrementsAndSetsExpiryOnFirstAcceptedEvent() {
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("placement:freq:7:8:9")).thenReturn(1L);
        PlacementFrequencyGuard guard = new PlacementFrequencyGuard(provider);

        guard.recordAccepted(7L, 8L, 9L, 24);

        verify(valueOps).increment("placement:freq:7:8:9");
        verify(redisTemplate).expire(eq("placement:freq:7:8:9"), eq(Duration.ofHours(24)));
    }

    @Test
    void doesNotSetExpiryOnSubsequentIncrements() {
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(2L);
        PlacementFrequencyGuard guard = new PlacementFrequencyGuard(provider);

        guard.recordAccepted(7L, 8L, 9L, 24);

        verify(valueOps).increment("placement:freq:7:8:9");
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void swallowsRedisExceptionsAndReturnsNullHint() {
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        PlacementFrequencyGuard guard = new PlacementFrequencyGuard(provider);

        assertThat(guard.tryReadHint(1L, 2L, 3L, 24)).isNull();
    }

    @Test
    void recordAcceptedSwallowsRedisFailureSilently() {
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        PlacementFrequencyGuard guard = new PlacementFrequencyGuard(provider);

        guard.recordAccepted(1L, 2L, 3L, 24);
        // should not throw — MySQL remains authoritative
    }
}
