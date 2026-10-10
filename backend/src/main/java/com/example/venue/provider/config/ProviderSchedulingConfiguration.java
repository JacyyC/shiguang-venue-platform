package com.example.venue.provider.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderObservabilityProperties.class)
public class ProviderSchedulingConfiguration {
    @Bean(name = ScheduledAnnotationBeanPostProcessor.DEFAULT_TASK_SCHEDULER_BEAN_NAME)
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean(name = "providerTaskScheduler")
    public ThreadPoolTaskScheduler providerTaskScheduler(ProviderObservabilityProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getSchedulerPoolSize());
        scheduler.setThreadNamePrefix("provider-observability-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        return scheduler;
    }

    @Bean(name = "providerObservabilityClock")
    public Clock providerObservabilityClock() {
        return Clock.systemDefaultZone();
    }
}
