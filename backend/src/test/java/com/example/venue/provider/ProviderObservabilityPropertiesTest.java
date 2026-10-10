package com.example.venue.provider;

import com.example.venue.provider.config.ProviderObservabilityProperties;
import com.example.venue.provider.config.ProviderSchedulingConfiguration;
import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderObservabilityPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(SchedulingTestConfiguration.class, ProviderSchedulingConfiguration.class);

    @Test
    void defaultsBindToApprovedSingleInstanceValues() {
        contextRunner.run(context -> {
            ProviderObservabilityProperties properties = context.getBean(ProviderObservabilityProperties.class);
            ThreadPoolTaskScheduler scheduler = context.getBean("providerTaskScheduler", ThreadPoolTaskScheduler.class);
            Clock clock = context.getBean("providerObservabilityClock", Clock.class);

            assertThat(properties.isScheduledEnabled()).isTrue();
            assertThat(properties.getSchedulerPoolSize()).isEqualTo(4);
            assertThat(properties.getWeather().isEnabled()).isTrue();
            assertThat(properties.getWeather().getInitialDelayMs()).isEqualTo(60_000L);
            assertThat(properties.getWeather().getFixedDelayMs()).isEqualTo(900_000L);
            assertThat(properties.getRisk().getInitialDelayMs()).isEqualTo(30_000L);
            assertThat(properties.getMetrics().getInitialDelayMs()).isEqualTo(15_000L);
            assertThat(properties.getRetention().getInitialDelayMs()).isEqualTo(300_000L);
            assertThat(properties.getRetention().getFixedDelayMs()).isEqualTo(86_400_000L);
            assertThat(properties.getRetention().getSnapshotDays()).isEqualTo(30);
            assertThat(properties.getRetention().getCallLogDays()).isEqualTo(30);
            assertThat(properties.getRetention().getBatchSize()).isEqualTo(500);
            assertThat(properties.getRetention().getMaxBatchesPerRun()).isEqualTo(20);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
            assertThat(clock.getZone()).isEqualTo(ZoneId.systemDefault());
        });
    }

    @Test
    void disabledScheduleFlagsBindIndependently() {
        contextRunner.withPropertyValues(
            "app.provider.observability.scheduled-enabled=false",
            "app.provider.observability.weather.enabled=false",
            "app.provider.observability.risk.enabled=false",
            "app.provider.observability.metrics.enabled=false",
            "app.provider.observability.retention.enabled=false"
        ).run(context -> {
            ProviderObservabilityProperties properties = context.getBean(ProviderObservabilityProperties.class);

            assertThat(properties.isScheduledEnabled()).isFalse();
            assertThat(properties.getWeather().isEnabled()).isFalse();
            assertThat(properties.getRisk().isEnabled()).isFalse();
            assertThat(properties.getMetrics().isEnabled()).isFalse();
            assertThat(properties.getRetention().isEnabled()).isFalse();
        });
    }

    @Test
    void defaultAndProviderSchedulersRemainIsolated() {
        contextRunner.withPropertyValues("spring.task.scheduling.pool.size=2").run(context -> {
            assertThat(context.containsBean(ScheduledAnnotationBeanPostProcessor.DEFAULT_TASK_SCHEDULER_BEAN_NAME))
                .isTrue();

            ThreadPoolTaskScheduler defaultScheduler = context.getBean(
                ScheduledAnnotationBeanPostProcessor.DEFAULT_TASK_SCHEDULER_BEAN_NAME,
                ThreadPoolTaskScheduler.class
            );
            ThreadPoolTaskScheduler providerScheduler = context.getBean("providerTaskScheduler", ThreadPoolTaskScheduler.class);

            assertThat(defaultScheduler).isNotSameAs(providerScheduler);
            assertThat(defaultScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            assertThat(providerScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
            assertThat(defaultScheduler.getThreadNamePrefix()).isEqualTo("scheduling-");
            assertThat(providerScheduler.getThreadNamePrefix()).isEqualTo("provider-observability-");
        });
    }

    @Test
    void invalidPoolDelayAndRetentionValuesFailBinding() {
        assertThatThrownBy(() -> bind("app.provider.observability.scheduler-pool-size=0"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.scheduler-pool-size=17"))
            .isInstanceOf(BindValidationException.class);

        assertThatThrownBy(() -> bind("app.provider.observability.weather.initial-delay-ms=0"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.weather.fixed-delay-ms=0"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.risk.initial-delay-ms=0"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.risk.fixed-delay-ms=0"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.metrics.initial-delay-ms=0"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.metrics.fixed-delay-ms=0"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.retention.initial-delay-ms=0"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.retention.fixed-delay-ms=0"))
            .isInstanceOf(BindValidationException.class);

        assertThatThrownBy(() -> bind("app.provider.observability.retention.snapshot-days=0"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.retention.snapshot-days=3651"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.retention.call-log-days=0"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.retention.call-log-days=3651"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.retention.batch-size=0"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.retention.batch-size=5001"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.retention.max-batches-per-run=0"))
            .isInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> bind("app.provider.observability.retention.max-batches-per-run=101"))
            .isInstanceOf(BindValidationException.class);
    }

    private void bind(String property) {
        contextRunner.withPropertyValues(property).run(context -> {
            Throwable startupFailure = context.getStartupFailure();
            BindValidationException validationFailure = findCause(startupFailure, BindValidationException.class);
            if (validationFailure == null) {
                throw new AssertionError("Expected configuration binding validation failure", startupFailure);
            }
            throw validationFailure;
        });
    }

    private <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingTestConfiguration {
    }
}
