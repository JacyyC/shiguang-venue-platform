package com.example.venue.advertising.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class AdvertisingConversionExecutionConfiguration {
    @Bean(name = "advertisingConversionTaskExecutor")
    public ThreadPoolTaskExecutor advertisingConversionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("advertising-conversion-");
        return executor;
    }
}
