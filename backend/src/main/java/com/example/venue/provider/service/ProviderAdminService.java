package com.example.venue.provider.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.BusinessException;
import com.example.venue.common.PageResult;
import com.example.venue.provider.dto.OpsMetricSnapshotResponse;
import com.example.venue.provider.dto.ProviderOverviewResponse;
import com.example.venue.provider.dto.ProviderQuery;
import com.example.venue.provider.dto.ProviderRefreshResponse;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.dto.ProviderStatusResponse;
import com.example.venue.provider.dto.RiskSnapshotResponse;
import com.example.venue.provider.dto.VenueObservationLocationRequest;
import com.example.venue.provider.dto.VenueObservationLocationResponse;
import com.example.venue.provider.dto.WeatherSnapshotResponse;
import com.example.venue.provider.entity.OpsMetricSnapshot;
import com.example.venue.provider.entity.ProviderCallLog;
import com.example.venue.provider.entity.ProviderRegistryEntry;
import com.example.venue.provider.entity.RiskAssessmentSnapshot;
import com.example.venue.provider.entity.VenueObservationLocation;
import com.example.venue.provider.entity.WeatherObservationSnapshot;
import com.example.venue.provider.mapper.OpsMetricSnapshotMapper;
import com.example.venue.provider.mapper.ProviderCallLogMapper;
import com.example.venue.provider.mapper.ProviderRegistryEntryMapper;
import com.example.venue.provider.mapper.RiskAssessmentSnapshotMapper;
import com.example.venue.provider.mapper.VenueObservationLocationMapper;
import com.example.venue.provider.mapper.WeatherObservationSnapshotMapper;
import com.example.venue.security.SecurityContext;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.mapper.VenueMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProviderAdminService {
    private static final List<String> DATA_GAPS = List.of(
        "生产天气 SLA 未接入。",
        "商业风控、设备指纹和地理 IP provider 未接入。",
        "Prometheus/APM、完整慢查询和分布式追踪未接入。",
        "真实外部消息渠道和生产支付 provider 未接入。"
    );

    private final ProviderRegistryEntryMapper registryMapper;
    private final ProviderCallLogMapper callLogMapper;
    private final VenueObservationLocationMapper locationMapper;
    private final VenueMapper venueMapper;
    private final WeatherObservationSnapshotMapper weatherSnapshotMapper;
    private final RiskAssessmentSnapshotMapper riskSnapshotMapper;
    private final OpsMetricSnapshotMapper metricSnapshotMapper;
    private final ProviderObservabilityService observabilityService;
    private final WeatherObservationCollectionService weatherCollectionService;
    private final RiskObservationCollectionService riskCollectionService;
    private final RuntimeMetricsCollectionService metricsCollectionService;
    private final Clock clock;

    @Autowired
    public ProviderAdminService(ProviderRegistryEntryMapper registryMapper,
                                ProviderCallLogMapper callLogMapper,
                                VenueObservationLocationMapper locationMapper,
                                VenueMapper venueMapper,
                                WeatherObservationSnapshotMapper weatherSnapshotMapper,
                                RiskAssessmentSnapshotMapper riskSnapshotMapper,
                                OpsMetricSnapshotMapper metricSnapshotMapper,
                                ProviderObservabilityService observabilityService,
                                WeatherObservationCollectionService weatherCollectionService,
                                RiskObservationCollectionService riskCollectionService,
                                RuntimeMetricsCollectionService metricsCollectionService,
                                Clock clock) {
        this.registryMapper = registryMapper;
        this.callLogMapper = callLogMapper;
        this.locationMapper = locationMapper;
        this.venueMapper = venueMapper;
        this.weatherSnapshotMapper = weatherSnapshotMapper;
        this.riskSnapshotMapper = riskSnapshotMapper;
        this.metricSnapshotMapper = metricSnapshotMapper;
        this.observabilityService = observabilityService;
        this.weatherCollectionService = weatherCollectionService;
        this.riskCollectionService = riskCollectionService;
        this.metricsCollectionService = metricsCollectionService;
        this.clock = clock;
    }

    public ProviderOverviewResponse overview() {
        Instant now = clock.instant();
        LocalDateTime applicationNow = LocalDateTime.ofInstant(now, clock.getZone());
        ProviderOverviewResponse response = new ProviderOverviewResponse();
        List<ProviderRegistryEntry> entries = registryMapper.selectList(
            new QueryWrapper<ProviderRegistryEntry>().eq("deleted", 0).orderByAsc("id"));
        response.providers.addAll(safeList(entries).stream()
            .map(entry -> toStatusResponse(entry, applicationNow))
            .toList());
        response.dataGaps.addAll(DATA_GAPS);
        return response;
    }

    public PageResult<ProviderCallLog> callLogs(ProviderQuery query) {
        ProviderQuery safeQuery = safeQuery(query);
        Page<ProviderCallLog> page = callLogMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            callLogWrapper(safeQuery).orderByDesc("occurred_at").orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    public ProviderRefreshResponse refreshWeather(Long venueId) {
        return weatherCollectionService.collectVenue(
            venueId, ProviderRunContext.manual(SecurityContext.currentUserId()));
    }

    public ProviderRefreshResponse refreshRisk(Long venueId) {
        return riskCollectionService.collect(venueId, ProviderRunContext.manual(SecurityContext.currentUserId()));
    }

    public ProviderRefreshResponse refreshMetrics() {
        return metricsCollectionService.collect(ProviderRunContext.manual(SecurityContext.currentUserId()));
    }

    public PageResult<WeatherSnapshotResponse> weatherSnapshots(ProviderQuery query) {
        ProviderQuery safeQuery = safeQuery(query);
        Page<WeatherObservationSnapshot> page = weatherSnapshotMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            weatherSnapshotWrapper(safeQuery).orderByDesc("observed_at").orderByDesc("id"));
        return PageResult.of(
            safeList(page.getRecords()).stream().map(this::toWeatherSnapshotResponse).toList(),
            page.getTotal(),
            safeQuery.getPageNum(),
            safeQuery.getPageSize());
    }

    public PageResult<RiskSnapshotResponse> riskSnapshots(ProviderQuery query) {
        ProviderQuery safeQuery = safeQuery(query);
        Page<RiskAssessmentSnapshot> page = riskSnapshotMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            riskSnapshotWrapper(safeQuery).orderByDesc("window_end").orderByDesc("id"));
        return PageResult.of(
            safeList(page.getRecords()).stream().map(this::toRiskSnapshotResponse).toList(),
            page.getTotal(),
            safeQuery.getPageNum(),
            safeQuery.getPageSize());
    }

    public PageResult<OpsMetricSnapshotResponse> metricSnapshots(ProviderQuery query) {
        ProviderQuery safeQuery = safeQuery(query);
        Page<OpsMetricSnapshot> page = metricSnapshotMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            metricSnapshotWrapper(safeQuery).orderByDesc("observed_at").orderByDesc("id"));
        return PageResult.of(
            safeList(page.getRecords()).stream().map(this::toMetricSnapshotResponse).toList(),
            page.getTotal(),
            safeQuery.getPageNum(),
            safeQuery.getPageSize());
    }

    public List<VenueObservationLocationResponse> locations() {
        return safeList(locationMapper.selectList(new QueryWrapper<VenueObservationLocation>()
            .eq("deleted", 0)
            .orderByAsc("venue_id")))
            .stream()
            .map(this::toLocationResponse)
            .toList();
    }

    public VenueObservationLocationResponse saveLocation(Long venueId, VenueObservationLocationRequest request) {
        validateLocationRequest(venueId, request);
        requireActiveVenue(venueId);
        LocalDateTime now = LocalDateTime.now();
        VenueObservationLocation location = locationMapper.selectOne(new QueryWrapper<VenueObservationLocation>()
            .eq("venue_id", venueId)
            .eq("deleted", 0)
            .last("limit 1"));
        boolean insert = location == null;
        if (insert) {
            location = new VenueObservationLocation();
            location.venueId = venueId;
            location.createTime = now;
            location.deleted = 0;
        }
        location.venueId = venueId;
        location.areaCode = request.areaCode;
        location.latitude = request.latitude;
        location.longitude = request.longitude;
        location.locationLabel = blankToDefault(request.locationLabel, "场馆" + venueId + "观测点");
        location.timezone = blankToDefault(request.timezone, "Asia/Shanghai");
        location.status = "ACTIVE";
        location.sourceType = "MANUAL";
        location.confidence = request.confidence;
        location.metadataJson = request.metadataJson;
        location.updateTime = now;
        if (insert) {
            locationMapper.insert(location);
        } else {
            locationMapper.updateById(location);
        }
        return toLocationResponse(location);
    }

    private ProviderStatusResponse toStatusResponse(ProviderRegistryEntry entry, LocalDateTime applicationNow) {
        ProviderStatusResponse response = new ProviderStatusResponse();
        response.providerKey = entry.providerKey;
        response.providerDomain = entry.providerDomain;
        response.providerEnv = entry.providerEnv;
        response.providerName = entry.providerName;
        response.displayName = entry.displayName;
        response.enabled = isEnabled(entry);
        response.configVersion = entry.configVersion;
        response.status = entry.status;
        response.freshnessSeconds = entry.freshnessSeconds;
        response.freshnessDeadlineAt = freshnessDeadlineAt(entry);
        response.evidenceAgeSeconds = evidenceAgeSeconds(entry, applicationNow);
        response.freshnessStatus = freshnessStatus(entry, response.freshnessDeadlineAt, applicationNow);
        response.lastSuccessAt = entry.lastSuccessAt;
        response.lastFailureAt = entry.lastFailureAt;
        response.consecutiveFailures = entry.consecutiveFailures;
        response.lastFailureCode = entry.lastFailureCode;
        response.lastFailureMessage = entry.lastFailureMessage;
        return response;
    }

    private boolean isEnabled(ProviderRegistryEntry entry) {
        return entry.enabled != null && entry.enabled != 0;
    }

    private LocalDateTime freshnessDeadlineAt(ProviderRegistryEntry entry) {
        if (entry.lastSuccessAt == null || entry.freshnessSeconds == null) {
            return null;
        }
        return entry.lastSuccessAt.plusSeconds(entry.freshnessSeconds);
    }

    private Long evidenceAgeSeconds(ProviderRegistryEntry entry, LocalDateTime applicationNow) {
        if (entry.lastSuccessAt == null) {
            return null;
        }
        return Math.max(0L, Duration.between(entry.lastSuccessAt, applicationNow).getSeconds());
    }

    private String freshnessStatus(ProviderRegistryEntry entry,
                                   LocalDateTime freshnessDeadlineAt,
                                   LocalDateTime applicationNow) {
        if (!isEnabled(entry)) {
            return "DISABLED";
        }
        if (entry.lastSuccessAt == null || freshnessDeadlineAt == null) {
            return "UNKNOWN";
        }
        return applicationNow.isBefore(freshnessDeadlineAt) ? "FRESH" : "STALE";
    }

    private QueryWrapper<ProviderCallLog> callLogWrapper(ProviderQuery query) {
        QueryWrapper<ProviderCallLog> wrapper = new QueryWrapper<>();
        if (query.providerKey() != null) {
            wrapper.eq("provider_key", query.providerKey());
        }
        if (query.normalizedProviderDomain() != null) {
            wrapper.eq("provider_domain", query.normalizedProviderDomain());
        }
        if (query.normalizedProviderEnv() != null) {
            wrapper.eq("provider_env", query.normalizedProviderEnv());
        }
        if (query.normalizedProviderName() != null) {
            wrapper.eq("provider_name", query.normalizedProviderName());
        }
        if (query.normalizedOperationType() != null) {
            wrapper.eq("operation_type", query.normalizedOperationType());
        }
        if (query.normalizedStatus() != null) {
            wrapper.eq("status", query.normalizedStatus());
        }
        if (query.normalizedSnapshotType() != null) {
            wrapper.eq("snapshot_type", query.normalizedSnapshotType());
        }
        if (query.normalizedSourceType() != null) {
            wrapper.eq("source_type", query.normalizedSourceType());
        }
        if (query.snapshotId != null) {
            wrapper.eq("snapshot_id", query.snapshotId);
        }
        if (query.startTime != null) {
            wrapper.ge("occurred_at", query.startTime);
        }
        if (query.endTime != null) {
            wrapper.lt("occurred_at", query.endTime);
        }
        return wrapper;
    }

    private QueryWrapper<WeatherObservationSnapshot> weatherSnapshotWrapper(ProviderQuery query) {
        QueryWrapper<WeatherObservationSnapshot> wrapper = new QueryWrapper<WeatherObservationSnapshot>()
            .eq("deleted", 0);
        if (query.providerKey() != null) {
            wrapper.eq("provider_key", query.providerKey());
        }
        if (query.normalizedProviderEnv() != null) {
            wrapper.eq("provider_env", query.normalizedProviderEnv());
        }
        if (query.normalizedProviderName() != null) {
            wrapper.eq("provider_name", query.normalizedProviderName());
        }
        if (query.venueId() != null) {
            wrapper.eq("venue_id", query.venueId());
        }
        if (query.snapshotId != null) {
            wrapper.eq("id", query.snapshotId);
        }
        if (query.startTime != null) {
            wrapper.ge("observed_at", query.startTime);
        }
        if (query.endTime != null) {
            wrapper.lt("observed_at", query.endTime);
        }
        return wrapper;
    }

    private QueryWrapper<RiskAssessmentSnapshot> riskSnapshotWrapper(ProviderQuery query) {
        QueryWrapper<RiskAssessmentSnapshot> wrapper = new QueryWrapper<RiskAssessmentSnapshot>()
            .eq("deleted", 0);
        if (query.providerKey() != null) {
            wrapper.eq("provider_key", query.providerKey());
        }
        if (query.normalizedProviderEnv() != null) {
            wrapper.eq("provider_env", query.normalizedProviderEnv());
        }
        if (query.normalizedProviderName() != null) {
            wrapper.eq("provider_name", query.normalizedProviderName());
        }
        if (query.venueId() != null) {
            wrapper.eq("scope_type", "VENUE")
                .eq("scope_id", query.venueId());
        }
        if (query.snapshotId != null) {
            wrapper.eq("id", query.snapshotId);
        }
        if (query.startTime != null) {
            wrapper.ge("window_end", query.startTime);
        }
        if (query.endTime != null) {
            wrapper.lt("window_end", query.endTime);
        }
        return wrapper;
    }

    private QueryWrapper<OpsMetricSnapshot> metricSnapshotWrapper(ProviderQuery query) {
        QueryWrapper<OpsMetricSnapshot> wrapper = new QueryWrapper<OpsMetricSnapshot>()
            .eq("deleted", 0);
        if (query.providerKey() != null) {
            wrapper.eq("provider_key", query.providerKey());
        }
        if (query.normalizedProviderEnv() != null) {
            wrapper.eq("provider_env", query.normalizedProviderEnv());
        }
        if (query.normalizedProviderName() != null) {
            wrapper.eq("provider_name", query.normalizedProviderName());
        }
        if (query.metricKey() != null) {
            wrapper.eq("metric_key", query.metricKey());
        }
        if (query.snapshotId != null) {
            wrapper.eq("id", query.snapshotId);
        }
        if (query.startTime != null) {
            wrapper.ge("observed_at", query.startTime);
        }
        if (query.endTime != null) {
            wrapper.lt("observed_at", query.endTime);
        }
        return wrapper;
    }

    private WeatherSnapshotResponse toWeatherSnapshotResponse(WeatherObservationSnapshot snapshot) {
        WeatherSnapshotResponse response = new WeatherSnapshotResponse();
        response.id = snapshot.id;
        response.snapshotNo = snapshot.snapshotNo;
        response.venueId = snapshot.venueId;
        response.locationId = snapshot.locationId;
        response.providerKey = snapshot.providerKey;
        response.providerEnv = snapshot.providerEnv;
        response.providerName = snapshot.providerName;
        response.configVersion = snapshot.configVersion;
        response.observedAt = snapshot.observedAt;
        response.expiresAt = snapshot.expiresAt;
        response.temperatureC = snapshot.temperatureC;
        response.precipitationMm = snapshot.precipitationMm;
        response.windSpeed = snapshot.windSpeed;
        response.weatherCode = snapshot.weatherCode;
        response.alertLevel = snapshot.alertLevel;
        response.summaryText = snapshot.summaryText;
        response.confidence = snapshot.confidence;
        response.createTime = snapshot.createTime;
        return response;
    }

    private RiskSnapshotResponse toRiskSnapshotResponse(RiskAssessmentSnapshot snapshot) {
        RiskSnapshotResponse response = new RiskSnapshotResponse();
        response.id = snapshot.id;
        response.snapshotNo = snapshot.snapshotNo;
        response.scopeType = snapshot.scopeType;
        response.scopeId = snapshot.scopeId;
        response.providerKey = snapshot.providerKey;
        response.providerEnv = snapshot.providerEnv;
        response.providerName = snapshot.providerName;
        response.configVersion = snapshot.configVersion;
        response.riskScore = snapshot.riskScore;
        response.riskLevel = snapshot.riskLevel;
        response.windowStart = snapshot.windowStart;
        response.windowEnd = snapshot.windowEnd;
        response.failedLoginCount = snapshot.failedLoginCount;
        response.paymentFailureCount = snapshot.paymentFailureCount;
        response.noticeFailureCount = snapshot.noticeFailureCount;
        response.openComplaintCount = snapshot.openComplaintCount;
        response.severeComplaintCount = snapshot.severeComplaintCount;
        response.confidence = snapshot.confidence;
        response.createTime = snapshot.createTime;
        return response;
    }

    private OpsMetricSnapshotResponse toMetricSnapshotResponse(OpsMetricSnapshot snapshot) {
        OpsMetricSnapshotResponse response = new OpsMetricSnapshotResponse();
        response.id = snapshot.id;
        response.snapshotNo = snapshot.snapshotNo;
        response.providerKey = snapshot.providerKey;
        response.providerEnv = snapshot.providerEnv;
        response.providerName = snapshot.providerName;
        response.configVersion = snapshot.configVersion;
        response.metricKey = snapshot.metricKey;
        response.metricValue = snapshot.metricValue;
        response.metricUnit = snapshot.metricUnit;
        response.status = snapshot.status;
        response.windowStart = snapshot.windowStart;
        response.windowEnd = snapshot.windowEnd;
        response.confidence = snapshot.confidence;
        response.observedAt = snapshot.observedAt;
        response.expiresAt = snapshot.expiresAt;
        response.createTime = snapshot.createTime;
        return response;
    }

    private VenueObservationLocationResponse toLocationResponse(VenueObservationLocation location) {
        VenueObservationLocationResponse response = new VenueObservationLocationResponse();
        response.id = location.id;
        response.venueId = location.venueId;
        response.areaCode = location.areaCode;
        response.latitude = location.latitude;
        response.longitude = location.longitude;
        response.locationLabel = location.locationLabel;
        response.timezone = location.timezone;
        response.status = location.status;
        response.sourceType = location.sourceType;
        response.confidence = location.confidence;
        response.metadataJson = location.metadataJson;
        response.createTime = location.createTime;
        response.updateTime = location.updateTime;
        return response;
    }

    private void validateLocationRequest(Long venueId, VenueObservationLocationRequest request) {
        if (venueId == null) {
            throw BusinessException.badRequest("场馆ID不能为空");
        }
        if (request == null) {
            throw BusinessException.badRequest("观测位置不能为空");
        }
        validateRange(request.latitude, "-90", "90", "纬度必须在 -90 到 90 之间");
        validateRange(request.longitude, "-180", "180", "经度必须在 -180 到 180 之间");
    }

    private void requireActiveVenue(Long venueId) {
        Venue venue = venueMapper.selectById(venueId);
        if (venue == null || Integer.valueOf(1).equals(venue.deleted)) {
            throw BusinessException.notFound("场馆不存在");
        }
    }

    private void validateRange(BigDecimal value, String min, String max, String message) {
        if (value == null || value.compareTo(new BigDecimal(min)) < 0 || value.compareTo(new BigDecimal(max)) > 0) {
            throw BusinessException.badRequest(message);
        }
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private ProviderQuery safeQuery(ProviderQuery query) {
        return query == null ? new ProviderQuery() : query;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
