package com.example.venue.provider.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRefreshResponse;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.dto.WeatherBatchOutcome;
import com.example.venue.provider.entity.VenueObservationLocation;
import com.example.venue.provider.entity.WeatherObservationSnapshot;
import com.example.venue.provider.mapper.VenueObservationLocationMapper;
import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import com.example.venue.provider.weather.WeatherProvider;
import com.example.venue.provider.weather.WeatherProviderRequest;
import com.example.venue.provider.weather.WeatherProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class WeatherObservationCollectionService {
    private static final Logger logger = LoggerFactory.getLogger(WeatherObservationCollectionService.class);
    private static final String WEATHER_PROVIDER_DOMAIN = "WEATHER";
    private static final String WEATHER_OPERATION = "FETCH_WEATHER";
    private static final String WEATHER_SNAPSHOT_TYPE = "WEATHER";
    private static final String MISSING_LOCATION_GAP = "场馆缺少经纬度观测位置，无法拉取天气快照。";
    private static final String RUNNING_GAP = "采集器正在运行，本轮未执行";
    private static final String NO_ACTIVE_LOCATION_GAP = "没有可用的场馆观测位置，本轮未执行。";
    private static final DateTimeFormatter SNAPSHOT_NO_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final VenueObservationLocationMapper locationMapper;
    private final WeatherProvider weatherProvider;
    private final ProviderEvidenceWriter evidenceWriter;
    private final InProcessProviderRunGuard guard;

    public WeatherObservationCollectionService(VenueObservationLocationMapper locationMapper,
                                               WeatherProvider weatherProvider,
                                               ProviderEvidenceWriter evidenceWriter,
                                               InProcessProviderRunGuard guard) {
        this.locationMapper = locationMapper;
        this.weatherProvider = weatherProvider;
        this.evidenceWriter = evidenceWriter;
        this.guard = guard;
    }

    public ProviderRefreshResponse collectVenue(long venueId, ProviderRunContext context) {
        ProviderRunContext runContext = Objects.requireNonNull(context, "context");
        long started = System.nanoTime();
        if (!guard.tryAcquire(InProcessProviderRunGuard.Lane.WEATHER)) {
            evidenceWriter.writeCallOnly(overlapRecord(runContext, venueId, WEATHER_OPERATION, elapsedMs(started)));
            return skippedResponse(RUNNING_GAP);
        }
        try {
            VenueObservationLocation location = activeLocation(venueId);
            if (location == null) {
                evidenceWriter.writeCallOnly(missingLocationRecord(runContext, venueId, elapsedMs(started)));
                return skippedResponse(MISSING_LOCATION_GAP);
            }

            WeatherProviderResult result;
            try {
                result = weatherProvider.fetch(request(venueId, location));
            } catch (RuntimeException ex) {
                evidenceWriter.writeRegistryOutcome(failureRecord(
                    runContext, venueId, location, ex, elapsedMs(started), true));
                return failedResponse(ex.getMessage());
            }
            WeatherObservationSnapshot snapshot = toSnapshot(venueId, location, result);
            return evidenceWriter.writeWeatherSuccess(
                snapshot, successRecord(runContext, venueId, location, snapshot, elapsedMs(started), true));
        } finally {
            guard.release(InProcessProviderRunGuard.Lane.WEATHER);
        }
    }

    public WeatherBatchOutcome collectActiveLocations(ProviderRunContext context) {
        ProviderRunContext runContext = Objects.requireNonNull(context, "context");
        long started = System.nanoTime();
        if (!guard.tryAcquire(InProcessProviderRunGuard.Lane.WEATHER)) {
            evidenceWriter.writeCallOnly(overlapRecord(runContext, null, "FETCH_WEATHER_BATCH", elapsedMs(started)));
            return WeatherBatchOutcome.skipped();
        }
        try {
            List<VenueObservationLocation> locations = locationMapper.selectActiveOrdered();
            if (locations == null || locations.isEmpty()) {
                evidenceWriter.writeCallOnly(noActiveLocationRecord(runContext, elapsedMs(started)));
                return WeatherBatchOutcome.noActiveLocation();
            }

            int successes = 0;
            int failures = 0;
            for (VenueObservationLocation location : locations) {
                // A26-P3-34：per-location duration_ms 改为单次调用耗时，在每次迭代开始取时间戳，
                // 不再复用批次开始时间戳（started）导致后续场馆累计耗时越记越大。
                long locationStarted = System.nanoTime();
                WeatherObservationSnapshot snapshot;
                try {
                    WeatherProviderResult result = weatherProvider.fetch(request(location.venueId, location));
                    snapshot = toSnapshot(location.venueId, location, result);
                } catch (RuntimeException ex) {
                    failures++;
                    evidenceWriter.writeCallOnly(failureRecord(
                        runContext, location.venueId, location, ex, elapsedMs(locationStarted), false));
                    continue;
                }
                try {
                    // A26-P3-33：单场馆 DB 写入异常用 try-catch 包裹，记录失败但不中断整批，
                    // 剩余场馆继续采集，聚合登记正常生成。原实现直接传播 writeWeatherDetail 异常
                    // 导致剩余场馆漏采一轮且聚合登记丢失。
                    evidenceWriter.writeWeatherDetail(
                        snapshot, successRecord(runContext, location.venueId, location, snapshot,
                            elapsedMs(locationStarted), false));
                } catch (RuntimeException writeEx) {
                    failures++;
                    logger.warn("weather evidence write failed for venueId={}; skipping this location in batch",
                        location.venueId, writeEx);
                    continue;
                }
                successes++;
            }

            WeatherBatchOutcome outcome = WeatherBatchOutcome.completed(
                weatherProvider.providerKey(), weatherProvider.providerEnv(), weatherProvider.providerName(),
                weatherProvider.configVersion(), successes, failures, elapsedMs(started));
            outcome.aggregateRecord(runContext).ifPresent(evidenceWriter::writeRegistryOutcome);
            return outcome;
        } finally {
            guard.release(InProcessProviderRunGuard.Lane.WEATHER);
        }
    }

    private VenueObservationLocation activeLocation(long venueId) {
        return locationMapper.selectOne(new QueryWrapper<VenueObservationLocation>()
            .eq("venue_id", venueId)
            .eq("status", "ACTIVE")
            .eq("deleted", 0)
            .last("limit 1"));
    }

    private WeatherProviderRequest request(Long venueId, VenueObservationLocation location) {
        return new WeatherProviderRequest(venueId, location.id, location.latitude, location.longitude);
    }

    private WeatherObservationSnapshot toSnapshot(Long venueId,
                                                   VenueObservationLocation location,
                                                   WeatherProviderResult result) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime observedAt = result.observedAt() == null ? now : result.observedAt();
        WeatherObservationSnapshot snapshot = new WeatherObservationSnapshot();
        snapshot.snapshotNo = weatherSnapshotNo(now);
        snapshot.venueId = venueId;
        snapshot.locationId = location.id;
        snapshot.providerKey = weatherProvider.providerKey();
        snapshot.providerEnv = weatherProvider.providerEnv();
        snapshot.providerName = weatherProvider.providerName();
        snapshot.configVersion = weatherProvider.configVersion();
        snapshot.observedAt = observedAt;
        snapshot.expiresAt = observedAt.plusMinutes(30);
        snapshot.temperatureC = result.temperatureC();
        snapshot.precipitationMm = result.precipitationMm();
        snapshot.windSpeed = result.windSpeed();
        snapshot.weatherCode = result.weatherCode();
        snapshot.alertLevel = blankToDefault(result.alertLevel(), "NONE");
        snapshot.summaryText = result.summaryText();
        snapshot.confidence = result.confidence();
        snapshot.createTime = now;
        snapshot.deleted = 0;
        return snapshot;
    }

    private ProviderCallRecord successRecord(ProviderRunContext context,
                                             Long venueId,
                                             VenueObservationLocation location,
                                             WeatherObservationSnapshot snapshot,
                                             long durationMs,
                                             boolean updateRegistry) {
        return ProviderCallRecord.of(
            context, weatherProvider.providerKey(), WEATHER_PROVIDER_DOMAIN, weatherProvider.providerEnv(),
            weatherProvider.providerName(), null, WEATHER_OPERATION, "SUCCESS", weatherProvider.configVersion(),
            requestSummary(venueId, location), responseSummary(snapshot), null, null, durationMs, snapshot.snapshotNo,
            WEATHER_SNAPSHOT_TYPE, null, snapshot.confidence, updateRegistry);
    }

    private ProviderCallRecord failureRecord(ProviderRunContext context,
                                             Long venueId,
                                             VenueObservationLocation location,
                                             RuntimeException failure,
                                             long durationMs,
                                             boolean updateRegistry) {
        return ProviderCallRecord.of(
            context, weatherProvider.providerKey(), WEATHER_PROVIDER_DOMAIN, weatherProvider.providerEnv(),
            weatherProvider.providerName(), null, WEATHER_OPERATION, "FAILED", weatherProvider.configVersion(),
            requestSummary(venueId, location), null, "WEATHER_PROVIDER_FAILED", failure.getMessage(), durationMs,
            null, WEATHER_SNAPSHOT_TYPE, null, null, updateRegistry);
    }

    private ProviderCallRecord missingLocationRecord(ProviderRunContext context, long venueId, long durationMs) {
        return ProviderCallRecord.of(
            context, weatherProvider.providerKey(), WEATHER_PROVIDER_DOMAIN, weatherProvider.providerEnv(),
            weatherProvider.providerName(), null, WEATHER_OPERATION, "SKIPPED", weatherProvider.configVersion(),
            requestSummary(venueId, null), null, "MISSING_LOCATION", MISSING_LOCATION_GAP, durationMs, null,
            WEATHER_SNAPSHOT_TYPE, null, null, false);
    }

    private ProviderCallRecord noActiveLocationRecord(ProviderRunContext context, long durationMs) {
        return ProviderCallRecord.of(
            context, weatherProvider.providerKey(), WEATHER_PROVIDER_DOMAIN, weatherProvider.providerEnv(),
            weatherProvider.providerName(), null, "FETCH_WEATHER_BATCH", "SKIPPED", weatherProvider.configVersion(),
            "{\"activeLocationCount\":0}", null, "NO_ACTIVE_OBSERVATION_LOCATION", NO_ACTIVE_LOCATION_GAP,
            durationMs, null, WEATHER_SNAPSHOT_TYPE, null, null, false);
    }

    private ProviderCallRecord overlapRecord(ProviderRunContext context,
                                             Long venueId,
                                             String operationType,
                                             long durationMs) {
        return ProviderCallRecord.of(
            context, weatherProvider.providerKey(), WEATHER_PROVIDER_DOMAIN, weatherProvider.providerEnv(),
            weatherProvider.providerName(), null, operationType, "SKIPPED", weatherProvider.configVersion(),
            requestSummary(venueId, null), null, "COLLECTION_ALREADY_RUNNING", RUNNING_GAP, durationMs, null,
            WEATHER_SNAPSHOT_TYPE, null, null, false);
    }

    private ProviderRefreshResponse skippedResponse(String dataGap) {
        ProviderRefreshResponse response = response("SKIPPED", null);
        response.dataGaps.add(dataGap);
        return response;
    }

    private ProviderRefreshResponse failedResponse(String dataGap) {
        ProviderRefreshResponse response = response("FAILED", null);
        response.dataGaps.add(dataGap);
        return response;
    }

    private ProviderRefreshResponse response(String status, Long snapshotId) {
        ProviderRefreshResponse response = new ProviderRefreshResponse();
        response.status = status;
        response.providerKey = weatherProvider.providerKey();
        response.snapshotId = snapshotId;
        return response;
    }

    private String requestSummary(Long venueId, VenueObservationLocation location) {
        if (location == null) {
            return venueId == null ? "{\"batch\":true}" : "{\"venueId\":" + venueId + "}";
        }
        return "{\"venueId\":" + venueId
            + ",\"locationId\":" + location.id
            + ",\"latitude\":" + location.latitude
            + ",\"longitude\":" + location.longitude
            + "}";
    }

    private String responseSummary(WeatherObservationSnapshot snapshot) {
        return "{\"temperatureC\":" + jsonValue(snapshot.temperatureC)
            + ",\"precipitationMm\":" + jsonValue(snapshot.precipitationMm)
            + ",\"windSpeed\":" + jsonValue(snapshot.windSpeed)
            + ",\"weatherCode\":" + quote(snapshot.weatherCode)
            + ",\"alertLevel\":" + quote(snapshot.alertLevel)
            + "}";
    }

    private String weatherSnapshotNo(LocalDateTime now) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        return "WX" + now.format(SNAPSHOT_NO_TIME_FORMAT) + suffix;
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String jsonValue(BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }

    private String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
