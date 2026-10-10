package com.example.venue.provider;

import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InProcessProviderRunGuardTest {
    @Test
    void sameLaneAllowsOnlyOneCallerUntilTheOwnerReleasesIt() throws Exception {
        InProcessProviderRunGuard guard = new InProcessProviderRunGuard();
        CountDownLatch weatherAcquired = new CountDownLatch(1);
        CountDownLatch releaseWeather = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> owner = pool.submit(() -> {
                boolean acquired = guard.tryAcquire(InProcessProviderRunGuard.Lane.WEATHER);
                weatherAcquired.countDown();
                if (!releaseWeather.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("weather owner was not released");
                }
                guard.release(InProcessProviderRunGuard.Lane.WEATHER);
                return acquired;
            });

            assertThat(weatherAcquired.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> contender = pool.submit(() ->
                guard.tryAcquire(InProcessProviderRunGuard.Lane.WEATHER));

            assertThat(contender.get(5, TimeUnit.SECONDS)).isFalse();
            releaseWeather.countDown();
            assertThat(owner.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(guard.tryAcquire(InProcessProviderRunGuard.Lane.WEATHER)).isTrue();
            guard.release(InProcessProviderRunGuard.Lane.WEATHER);
        } finally {
            releaseWeather.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void differentLanesCanRunWhileWeatherIsHeld() throws Exception {
        InProcessProviderRunGuard guard = new InProcessProviderRunGuard();
        CountDownLatch weatherAcquired = new CountDownLatch(1);
        CountDownLatch releaseWeather = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> weather = pool.submit(() -> {
                if (!guard.tryAcquire(InProcessProviderRunGuard.Lane.WEATHER)) {
                    return false;
                }
                weatherAcquired.countDown();
                try {
                    if (!releaseWeather.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("weather lane was not released");
                    }
                    return true;
                } finally {
                    guard.release(InProcessProviderRunGuard.Lane.WEATHER);
                }
            });

            assertThat(weatherAcquired.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> risk = pool.submit(() -> {
                boolean acquired = guard.tryAcquire(InProcessProviderRunGuard.Lane.RISK);
                if (acquired) {
                    guard.release(InProcessProviderRunGuard.Lane.RISK);
                }
                return acquired;
            });

            assertThat(risk.get(5, TimeUnit.SECONDS)).isTrue();
            releaseWeather.countDown();
            assertThat(weather.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseWeather.countDown();
            pool.shutdownNow();
        }
    }
}
