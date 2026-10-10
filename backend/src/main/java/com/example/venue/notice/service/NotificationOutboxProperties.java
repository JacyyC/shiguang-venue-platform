package com.example.venue.notice.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.notice.outbox")
public class NotificationOutboxProperties {
    private long pollDelayMs = 5000;
    private int batchSize = 50;
    private long leaseSeconds = 60;
    private int maxAttempts = 3;
    private long retryBaseSeconds = 30;

    public long getPollDelayMs() {
        return pollDelayMs;
    }

    public void setPollDelayMs(long pollDelayMs) {
        this.pollDelayMs = pollDelayMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getRetryBaseSeconds() {
        return retryBaseSeconds;
    }

    public void setRetryBaseSeconds(long retryBaseSeconds) {
        this.retryBaseSeconds = retryBaseSeconds;
    }
}
