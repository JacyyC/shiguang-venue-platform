package com.example.venue.provider.service;

import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRefreshResponse;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.entity.OpsMetricSnapshot;
import com.example.venue.provider.metrics.LocalRuntimeMetricsProvider;
import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Service
public class RuntimeMetricsCollectionService {
    private static final Logger logger = LoggerFactory.getLogger(RuntimeMetricsCollectionService.class);
    private static final String RUNNING_GAP = "采集器正在运行，本轮未执行";
    private static final String PARTIAL_UNAVAILABLE_GAP = "本地运行指标存在不可用项";
    private static final String METRICS_FAILURE_GAP = "本地运行指标采集失败，请检查Provider状态。";
    private static final String METRICS_REQUEST_SUMMARY =
        "{\"metricKeys\":[\"db_ping_ms\",\"redis_ping_ms\",\"redis_hit_rate\",\"jvm_memory_used_mb\"]}";

    private final LocalRuntimeMetricsProvider metricsProvider;
    private final ProviderEvidenceWriter evidenceWriter;
    private final InProcessProviderRunGuard guard;

    public RuntimeMetricsCollectionService(LocalRuntimeMetricsProvider metricsProvider,
                                           ProviderEvidenceWriter evidenceWriter,
                                           InProcessProviderRunGuard guard) {
        this.metricsProvider = metricsProvider;
        this.evidenceWriter = evidenceWriter;
        this.guard = guard;
    }

    public ProviderRefreshResponse collect(ProviderRunContext context) {
        ProviderRunContext runContext = Objects.requireNonNull(context, "context");
        long started = System.nanoTime();
        if (!guard.tryAcquire(InProcessProviderRunGuard.Lane.METRICS)) {
            evidenceWriter.writeCallOnly(overlapRecord(runContext, elapsedMs(started)));
            return response("SKIPPED", null, List.of(RUNNING_GAP));
        }
        try {
            List<OpsMetricSnapshot> snapshots;
            ProviderCallRecord outcome;
            try {
                snapshots = Objects.requireNonNull(metricsProvider.collect(), "metrics snapshots");
                snapshots.forEach(snapshot -> snapshot.sourceSummaryJson = null);
                outcome = outcomeRecord(runContext, snapshots, elapsedMs(started));
            } catch (RuntimeException ex) {
                // A26-P3-36：与天气侧对齐——落库脱敏后的真实异常 message（由 ProviderObservabilityService
                // 经 SensitiveDataMasker 脱敏后写入 ProviderCallLog.failure_message 列），并打 warn 日志
                // 记录根因，避免连续失败时运维无任何线索。原实现完全吞掉真实异常，failureMessage 恒为固定 GAP。
                logger.warn("runtime metrics collection failed: {}", ex.getMessage(), ex);
                evidenceWriter.writeRegistryOutcome(failureRecord(runContext, ex, elapsedMs(started)));
                return response(
                    "FAILED",
                    null,
                    List.of(LocalRuntimeMetricsProvider.SLOW_QUERY_GAP, METRICS_FAILURE_GAP));
            }

            evidenceWriter.writeMetricsSuccess(snapshots, outcome);
            ProviderRefreshResponse response = response(outcome.status(), firstSnapshotId(snapshots), safeList(metricsProvider.dataGaps()));
            return response;
        } finally {
            guard.release(InProcessProviderRunGuard.Lane.METRICS);
        }
    }

    private ProviderCallRecord outcomeRecord(ProviderRunContext context,
                                             List<OpsMetricSnapshot> snapshots,
                                             long durationMs) {
        boolean degraded = snapshots.stream().anyMatch(snapshot -> !"OK".equals(snapshot.status));
        return ProviderCallRecord.of(
            context,
            LocalRuntimeMetricsProvider.PROVIDER_KEY,
            LocalRuntimeMetricsProvider.PROVIDER_DOMAIN,
            LocalRuntimeMetricsProvider.PROVIDER_ENV,
            LocalRuntimeMetricsProvider.PROVIDER_NAME,
            null,
            LocalRuntimeMetricsProvider.OPERATION,
            degraded ? "DEGRADED" : "SUCCESS",
            LocalRuntimeMetricsProvider.CONFIG_VERSION,
            METRICS_REQUEST_SUMMARY,
            metricsResponseSummary(snapshots),
            degraded ? "METRICS_PARTIAL_UNAVAILABLE" : null,
            degraded ? PARTIAL_UNAVAILABLE_GAP : null,
            durationMs,
            null,
            LocalRuntimeMetricsProvider.SNAPSHOT_TYPE,
            null,
            lowestConfidence(snapshots),
            true);
    }

    private ProviderCallRecord failureRecord(ProviderRunContext context, RuntimeException failure, long durationMs) {
        return ProviderCallRecord.of(
            context,
            LocalRuntimeMetricsProvider.PROVIDER_KEY,
            LocalRuntimeMetricsProvider.PROVIDER_DOMAIN,
            LocalRuntimeMetricsProvider.PROVIDER_ENV,
            LocalRuntimeMetricsProvider.PROVIDER_NAME,
            null,
            LocalRuntimeMetricsProvider.OPERATION,
            "FAILED",
            LocalRuntimeMetricsProvider.CONFIG_VERSION,
            METRICS_REQUEST_SUMMARY,
            null,
            "METRICS_PROVIDER_FAILED",
            failure.getMessage(),
            durationMs,
            null,
            LocalRuntimeMetricsProvider.SNAPSHOT_TYPE,
            null,
            null,
            true);
    }

    private ProviderCallRecord overlapRecord(ProviderRunContext context, long durationMs) {
        return ProviderCallRecord.of(
            context,
            LocalRuntimeMetricsProvider.PROVIDER_KEY,
            LocalRuntimeMetricsProvider.PROVIDER_DOMAIN,
            LocalRuntimeMetricsProvider.PROVIDER_ENV,
            LocalRuntimeMetricsProvider.PROVIDER_NAME,
            null,
            LocalRuntimeMetricsProvider.OPERATION,
            "SKIPPED",
            LocalRuntimeMetricsProvider.CONFIG_VERSION,
            METRICS_REQUEST_SUMMARY,
            null,
            "COLLECTION_ALREADY_RUNNING",
            RUNNING_GAP,
            durationMs,
            null,
            LocalRuntimeMetricsProvider.SNAPSHOT_TYPE,
            null,
            null,
            false);
    }

    private ProviderRefreshResponse response(String status, Long snapshotId, List<String> dataGaps) {
        ProviderRefreshResponse response = new ProviderRefreshResponse();
        response.status = status;
        response.providerKey = LocalRuntimeMetricsProvider.PROVIDER_KEY;
        response.snapshotId = snapshotId;
        response.dataGaps.addAll(dataGaps);
        return response;
    }

    private Long firstSnapshotId(List<OpsMetricSnapshot> snapshots) {
        return snapshots.isEmpty() ? null : snapshots.get(0).id;
    }

    private String metricsResponseSummary(List<OpsMetricSnapshot> snapshots) {
        StringBuilder builder = new StringBuilder("{\"metrics\":[");
        boolean first = true;
        for (OpsMetricSnapshot snapshot : snapshots) {
            if (!first) {
                builder.append(',');
            }
            builder.append("{\"metricKey\":").append(quote(snapshot.metricKey))
                .append(",\"status\":").append(quote(snapshot.status))
                .append(",\"metricValue\":").append(jsonValue(snapshot.metricValue))
                .append('}');
            first = false;
        }
        return builder.append("],\"dataGaps\":[")
            .append(quote(LocalRuntimeMetricsProvider.SLOW_QUERY_GAP))
            .append("]}")
            .toString();
    }

    private BigDecimal lowestConfidence(List<OpsMetricSnapshot> snapshots) {
        return snapshots.stream()
            .map(snapshot -> snapshot.confidence)
            .filter(Objects::nonNull)
            .min(BigDecimal::compareTo)
            .orElse(null);
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String jsonValue(BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }

    private String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
