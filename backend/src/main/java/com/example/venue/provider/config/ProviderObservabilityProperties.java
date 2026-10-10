package com.example.venue.provider.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.provider.observability")
public class ProviderObservabilityProperties {
    private boolean scheduledEnabled = true;

    @Min(1)
    @Max(16)
    private int schedulerPoolSize = 4;

    @Valid
    private CollectorProperties weather = new CollectorProperties(60_000L, 900_000L);

    @Valid
    private CollectorProperties risk = new CollectorProperties(30_000L, 300_000L);

    @Valid
    private CollectorProperties metrics = new CollectorProperties(15_000L, 60_000L);

    @Valid
    private RetentionProperties retention = new RetentionProperties();

    public boolean isScheduledEnabled() {
        return scheduledEnabled;
    }

    public void setScheduledEnabled(boolean scheduledEnabled) {
        this.scheduledEnabled = scheduledEnabled;
    }

    public int getSchedulerPoolSize() {
        return schedulerPoolSize;
    }

    public void setSchedulerPoolSize(int schedulerPoolSize) {
        this.schedulerPoolSize = schedulerPoolSize;
    }

    public CollectorProperties getWeather() {
        return weather;
    }

    public void setWeather(CollectorProperties weather) {
        this.weather = weather;
    }

    public CollectorProperties getRisk() {
        return risk;
    }

    public void setRisk(CollectorProperties risk) {
        this.risk = risk;
    }

    public CollectorProperties getMetrics() {
        return metrics;
    }

    public void setMetrics(CollectorProperties metrics) {
        this.metrics = metrics;
    }

    public RetentionProperties getRetention() {
        return retention;
    }

    public void setRetention(RetentionProperties retention) {
        this.retention = retention;
    }

    public static class CollectorProperties {
        private boolean enabled = true;

        @Positive
        private long initialDelayMs;

        @Positive
        private long fixedDelayMs;

        public CollectorProperties() {
        }

        protected CollectorProperties(long initialDelayMs, long fixedDelayMs) {
            this.initialDelayMs = initialDelayMs;
            this.fixedDelayMs = fixedDelayMs;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }
    }

    public static class RetentionProperties extends CollectorProperties {
        @Min(1)
        @Max(3650)
        private int snapshotDays = 30;

        @Min(1)
        @Max(3650)
        private int callLogDays = 30;

        @Min(1)
        @Max(5000)
        private int batchSize = 500;

        @Min(1)
        @Max(100)
        private int maxBatchesPerRun = 20;

        public RetentionProperties() {
            super(300_000L, 86_400_000L);
        }

        public int getSnapshotDays() {
            return snapshotDays;
        }

        public void setSnapshotDays(int snapshotDays) {
            this.snapshotDays = snapshotDays;
        }

        public int getCallLogDays() {
            return callLogDays;
        }

        public void setCallLogDays(int callLogDays) {
            this.callLogDays = callLogDays;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxBatchesPerRun() {
            return maxBatchesPerRun;
        }

        public void setMaxBatchesPerRun(int maxBatchesPerRun) {
            this.maxBatchesPerRun = maxBatchesPerRun;
        }
    }
}
