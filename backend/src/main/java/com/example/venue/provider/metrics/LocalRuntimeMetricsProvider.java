package com.example.venue.provider.metrics;

import com.example.venue.provider.entity.OpsMetricSnapshot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

@Component
public class LocalRuntimeMetricsProvider {
    public static final String PROVIDER_KEY = "metrics.local_runtime.dev";
    public static final String PROVIDER_DOMAIN = "METRICS";
    public static final String PROVIDER_ENV = "DEV";
    public static final String PROVIDER_NAME = "LOCAL_RUNTIME";
    public static final String CONFIG_VERSION = "LOCAL_RUNTIME_V1";
    public static final String OPERATION = "COLLECT_METRICS";
    public static final String SNAPSHOT_TYPE = "METRIC";
    public static final String SLOW_QUERY_GAP = "完整慢查询数据未接入。";

    private static final DateTimeFormatter SNAPSHOT_NO_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final BigDecimal OK_CONFIDENCE = new BigDecimal("0.9000");
    private static final BigDecimal LOCAL_CONFIDENCE = new BigDecimal("0.9500");
    private static final BigDecimal UNAVAILABLE_CONFIDENCE = new BigDecimal("0.4000");

    private final StringRedisTemplate redisTemplate;
    private final DataSource dataSource;

    @Autowired
    public LocalRuntimeMetricsProvider(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                       DataSource dataSource) {
        this(redisTemplateProvider.getIfAvailable(), dataSource);
    }

    public LocalRuntimeMetricsProvider(StringRedisTemplate redisTemplate, DataSource dataSource) {
        this.redisTemplate = redisTemplate;
        this.dataSource = dataSource;
    }

    public List<OpsMetricSnapshot> collect() {
        LocalDateTime observedAt = LocalDateTime.now();
        List<OpsMetricSnapshot> snapshots = new ArrayList<>();
        addRedisMetrics(snapshots, observedAt);
        snapshots.add(dbPingMetric(observedAt));
        snapshots.add(jvmMemoryMetric(observedAt));
        return snapshots;
    }

    public List<String> dataGaps() {
        return List.of(SLOW_QUERY_GAP);
    }

    private void addRedisMetrics(List<OpsMetricSnapshot> snapshots, LocalDateTime observedAt) {
        if (redisTemplate == null) {
            addUnavailableRedisMetrics(snapshots, observedAt, "StringRedisTemplate unavailable");
            return;
        }
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            addUnavailableRedisMetrics(snapshots, observedAt, "RedisConnectionFactory unavailable");
            return;
        }
        try (RedisConnection connection = factory.getConnection()) {
            long pingStarted = System.nanoTime();
            String pong = connection.ping();
            snapshots.add(metric(
                "redis_ping_ms",
                BigDecimal.valueOf(elapsedMs(pingStarted)).setScale(4, RoundingMode.HALF_UP),
                "ms",
                "OK",
                "{\"pong\":\"" + escape(pong) + "\"}",
                OK_CONFIDENCE,
                observedAt));
            try {
                RedisServerCommands commands = connection.serverCommands();
                Properties stats = commands.info("stats");
                snapshots.add(redisHitRateMetric(stats, observedAt));
            } catch (RuntimeException ex) {
                snapshots.add(unavailableMetric("redis_hit_rate", "ratio", observedAt, ex.getMessage()));
            }
        } catch (RuntimeException ex) {
            addUnavailableRedisMetrics(snapshots, observedAt, ex.getMessage());
        }
    }

    private void addUnavailableRedisMetrics(List<OpsMetricSnapshot> snapshots, LocalDateTime observedAt,
                                            String message) {
        snapshots.add(unavailableMetric("redis_ping_ms", "ms", observedAt, message));
        snapshots.add(unavailableMetric("redis_hit_rate", "ratio", observedAt, message));
    }

    private OpsMetricSnapshot redisHitRateMetric(Properties stats, LocalDateTime observedAt) {
        long hits = longProperty(stats, "keyspace_hits");
        long misses = longProperty(stats, "keyspace_misses");
        long total = hits + misses;
        BigDecimal value = total == 0
            ? BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP)
            : BigDecimal.valueOf(hits).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        return metric(
            "redis_hit_rate",
            value,
            "ratio",
            "OK",
            "{\"keyspaceHits\":" + hits + ",\"keyspaceMisses\":" + misses + "}",
            OK_CONFIDENCE,
            observedAt);
    }

    private OpsMetricSnapshot dbPingMetric(LocalDateTime observedAt) {
        long started = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(2)) {
                return unavailableMetric("db_ping_ms", "ms", observedAt, "DataSource validation failed");
            }
            return metric(
                "db_ping_ms",
                BigDecimal.valueOf(elapsedMs(started)).setScale(4, RoundingMode.HALF_UP),
                "ms",
                "OK",
                "{\"validation\":\"isValid(2)\"}",
                OK_CONFIDENCE,
                observedAt);
        } catch (Exception ex) {
            return unavailableMetric("db_ping_ms", "ms", observedAt, ex.getMessage());
        }
    }

    private OpsMetricSnapshot jvmMemoryMetric(LocalDateTime observedAt) {
        Runtime runtime = Runtime.getRuntime();
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();
        BigDecimal usedMb = BigDecimal.valueOf(usedBytes)
            .divide(BigDecimal.valueOf(1024L * 1024L), 4, RoundingMode.HALF_UP);
        return metric(
            "jvm_memory_used_mb",
            usedMb,
            "MB",
            "OK",
            "{\"runtime\":\"java.lang.Runtime\"}",
            LOCAL_CONFIDENCE,
            observedAt);
    }

    private OpsMetricSnapshot unavailableMetric(String key, String unit, LocalDateTime observedAt, String message) {
        return metric(
            key,
            null,
            unit,
            "UNAVAILABLE",
            "{\"error\":\"" + escape(message) + "\"}",
            UNAVAILABLE_CONFIDENCE,
            observedAt);
    }

    private OpsMetricSnapshot metric(String key, BigDecimal value, String unit, String status, String sourceSummaryJson,
                                     BigDecimal confidence, LocalDateTime observedAt) {
        OpsMetricSnapshot snapshot = new OpsMetricSnapshot();
        snapshot.snapshotNo = snapshotNo(observedAt);
        snapshot.providerKey = PROVIDER_KEY;
        snapshot.providerEnv = PROVIDER_ENV;
        snapshot.providerName = PROVIDER_NAME;
        snapshot.configVersion = CONFIG_VERSION;
        snapshot.metricKey = key;
        snapshot.metricValue = value;
        snapshot.metricUnit = unit;
        snapshot.status = status;
        snapshot.windowStart = observedAt.minusMinutes(5);
        snapshot.windowEnd = observedAt;
        snapshot.sourceSummaryJson = sourceSummaryJson;
        snapshot.confidence = confidence;
        snapshot.observedAt = observedAt;
        snapshot.expiresAt = observedAt.plusMinutes(5);
        snapshot.createTime = observedAt;
        snapshot.deleted = 0;
        return snapshot;
    }

    private long longProperty(Properties properties, String key) {
        if (properties == null) {
            return 0;
        }
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Long.parseLong(value);
    }

    private String snapshotNo(LocalDateTime now) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        return "MT" + now.format(SNAPSHOT_NO_TIME_FORMAT) + suffix;
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
