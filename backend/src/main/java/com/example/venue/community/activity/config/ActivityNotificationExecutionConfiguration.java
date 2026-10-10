package com.example.venue.community.activity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class ActivityNotificationExecutionConfiguration {
    @Bean(name = "activityNotificationTaskExecutor")
    public ThreadPoolTaskExecutor activityNotificationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("activity-notification-");
        return executor;
    }
}
