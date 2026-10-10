package com.example.venue.community.schedule;

import com.example.venue.community.config.CommunityRetentionProperties;
import com.example.venue.community.service.CommunityRetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CommunityRetentionSchedulerTest {
    @Test
    void disabledSchedulerDoesNotInvokeRetention() {
        CommunityRetentionProperties properties = new CommunityRetentionProperties();
        properties.setEnabled(false);
        CommunityRetentionService service = mock(CommunityRetentionService.class);

        new CommunityRetentionScheduler(properties, service).runRetention();

        verifyNoInteractions(service);
    }

    @Test
    void enabledSchedulerInvokesRetentionAndContainsFailures() {
        CommunityRetentionProperties properties = new CommunityRetentionProperties();
        CommunityRetentionService service = mock(CommunityRetentionService.class);
        CommunityRetentionScheduler scheduler = new CommunityRetentionScheduler(properties, service);

        scheduler.runRetention();
        verify(service).runOnce();

        doThrow(new IllegalStateException("injected failure")).when(service).runOnce();
        assertThatCode(scheduler::runRetention).doesNotThrowAnyException();
    }

    @Test
    void scheduleUsesConfigurableDailyDelay() throws Exception {
        Method method = CommunityRetentionScheduler.class.getMethod("runRetention");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.initialDelayString())
            .isEqualTo("${app.community.retention.initial-delay-ms:300000}");
        assertThat(scheduled.fixedDelayString())
            .isEqualTo("${app.community.retention.fixed-delay-ms:86400000}");
        assertThat(scheduled.cron()).isBlank();
        assertThat(scheduled.fixedRateString()).isBlank();
    }
}
