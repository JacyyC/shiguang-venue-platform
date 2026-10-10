package com.example.venue.advertising.service;

import com.example.venue.advertising.config.AdvertisingRetentionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AdvertisingRetentionSchedulerTest {
    @Test
    void disabledSchedulerDoesNotInvokeRetention() {
        AdvertisingRetentionProperties properties = new AdvertisingRetentionProperties();
        properties.setEnabled(false);
        AdvertisingRetentionService service = mock(AdvertisingRetentionService.class);

        new AdvertisingRetentionScheduler(properties, service).runRetention();

        verifyNoInteractions(service);
    }

    @Test
    void enabledSchedulerInvokesRetentionAndContainsFailures() {
        AdvertisingRetentionProperties properties = new AdvertisingRetentionProperties();
        AdvertisingRetentionService service = mock(AdvertisingRetentionService.class);
        AdvertisingRetentionScheduler scheduler = new AdvertisingRetentionScheduler(properties, service);

        scheduler.runRetention();
        verify(service).runOnce();

        doThrow(new IllegalStateException("injected failure")).when(service).runOnce();
        assertThatCode(scheduler::runRetention).doesNotThrowAnyException();
    }

    @Test
    void scheduleUsesConfigurableDailyDelay() throws Exception {
        Method method = AdvertisingRetentionScheduler.class.getMethod("runRetention");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        org.assertj.core.api.Assertions.assertThat(scheduled).isNotNull();
        org.assertj.core.api.Assertions.assertThat(scheduled.initialDelayString())
            .isEqualTo("${app.advertising.retention.initial-delay-ms:300000}");
        org.assertj.core.api.Assertions.assertThat(scheduled.fixedDelayString())
            .isEqualTo("${app.advertising.retention.fixed-delay-ms:86400000}");
        org.assertj.core.api.Assertions.assertThat(scheduled.cron()).isBlank();
        org.assertj.core.api.Assertions.assertThat(scheduled.fixedRateString()).isBlank();
    }
}
