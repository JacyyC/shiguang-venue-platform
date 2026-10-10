package com.example.venue.provider;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.BusinessException;
import com.example.venue.provider.dto.ProviderQuery;
import com.example.venue.provider.dto.VenueObservationLocationRequest;
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
import com.example.venue.provider.service.ProviderAdminService;
import com.example.venue.provider.service.ProviderObservabilityService;
import com.example.venue.provider.service.RiskObservationCollectionService;
import com.example.venue.provider.service.RuntimeMetricsCollectionService;
import com.example.venue.provider.service.WeatherObservationCollectionService;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.mapper.VenueMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProviderAdminQueryTest {
    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 12, 11, 0);
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-07-12T03:00:00Z"), APPLICATION_ZONE);

    @Test
    void activeObservationLocationMapperUsesExplicitActiveNonDeletedAscendingIdQuery() throws Exception {
        Select select = VenueObservationLocationMapper.class
            .getMethod("selectActiveOrdered")
            .getAnnotation(Select.class);

        assertThat(select).isNotNull();
        String sql = String.join(" ", select.value()).toLowerCase(Locale.ROOT);
        assertThat(sql).contains("status = 'active'", "deleted = 0", "order by id asc");
    }

    @Test
    void saveLocationRejectsMissingVenueBeforeReadingOrWritingLocations() {
        VenueMapper venueMapper = mock(VenueMapper.class);
        VenueObservationLocationMapper locationMapper = mock(VenueObservationLocationMapper.class);
        ProviderAdminService service = locationService(locationMapper, venueMapper);
        when(venueMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.saveLocation(404L, validLocationRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场馆不存在");

        verify(venueMapper).selectById(404L);
        verifyNoInteractions(locationMapper);
    }

    @Test
    void saveLocationRejectsDeletedVenueBeforeReadingOrWritingLocations() {
        VenueMapper venueMapper = mock(VenueMapper.class);
        VenueObservationLocationMapper locationMapper = mock(VenueObservationLocationMapper.class);
        ProviderAdminService service = locationService(locationMapper, venueMapper);
        Venue venue = activeVenue(405L);
        venue.deleted = 1;
        when(venueMapper.selectById(405L)).thenReturn(venue);

        assertThatThrownBy(() -> service.saveLocation(405L, validLocationRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场馆不存在");

        verify(venueMapper).selectById(405L);
        verifyNoInteractions(locationMapper);
    }

    @Test
    void saveLocationAllowsActiveVenueInsertAndUpdate() {
        VenueMapper venueMapper = mock(VenueMapper.class);
        VenueObservationLocationMapper locationMapper = mock(VenueObservationLocationMapper.class);
        ProviderAdminService service = locationService(locationMapper, venueMapper);
        VenueObservationLocation existing = new VenueObservationLocation();
        existing.id = 90L;
        existing.venueId = 20L;
        existing.deleted = 0;
        when(venueMapper.selectById(20L)).thenReturn(activeVenue(20L));
        when(locationMapper.selectOne(any())).thenReturn(null, existing);

        assertThat(service.saveLocation(20L, validLocationRequest()).status).isEqualTo("ACTIVE");
        assertThat(service.saveLocation(20L, validLocationRequest()).id).isEqualTo(90L);

        verify(locationMapper).insert(any(VenueObservationLocation.class));
        verify(locationMapper).updateById(existing);
    }

    @Test
    void futureProviderDomainIsNormalizedAndAppliedToCallLogQuery() throws Exception {
        ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        ProviderCallLogMapper callLogMapper = mock(ProviderCallLogMapper.class);
        VenueObservationLocationMapper locationMapper = mock(VenueObservationLocationMapper.class);
        WeatherObservationSnapshotMapper weatherSnapshotMapper = mock(WeatherObservationSnapshotMapper.class);
        RiskAssessmentSnapshotMapper riskSnapshotMapper = mock(RiskAssessmentSnapshotMapper.class);
        OpsMetricSnapshotMapper metricSnapshotMapper = mock(OpsMetricSnapshotMapper.class);
        ProviderObservabilityService observabilityService = mock(ProviderObservabilityService.class);
        when(callLogMapper.selectPage(any(), any())).thenReturn(new Page<>());
        ProviderAdminService service = providerAdminService(
            registryMapper, callLogMapper, locationMapper, weatherSnapshotMapper, riskSnapshotMapper,
            metricSnapshotMapper, observabilityService);
        ProviderQuery query = new ProviderQuery();
        setQueryField(query, "providerDomain", " future_domain ");

        service.callLogs(query);

        ArgumentCaptor<QueryWrapper<ProviderCallLog>> callLogWrapper =
            ArgumentCaptor.forClass(QueryWrapper.class);
        verify(callLogMapper).selectPage(any(), callLogWrapper.capture());
        assertThat(query.normalizedProviderDomain()).isEqualTo("FUTURE_DOMAIN");
        assertThat(callLogWrapper.getValue().getSqlSegment()).contains("provider_domain");
        assertThat(callLogWrapper.getValue().getParamNameValuePairs()).containsValue("FUTURE_DOMAIN");
    }

    @ParameterizedTest
    @CsvSource({"' manual ',MANUAL", "' scheduled ',SCHEDULED", "' system ',SYSTEM"})
    void supportedSourceTypeIsNormalizedAndAppliedToGenericCallLogQuery(String sourceType, String expected) throws Exception {
        ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        ProviderCallLogMapper callLogMapper = mock(ProviderCallLogMapper.class);
        VenueObservationLocationMapper locationMapper = mock(VenueObservationLocationMapper.class);
        WeatherObservationSnapshotMapper weatherSnapshotMapper = mock(WeatherObservationSnapshotMapper.class);
        RiskAssessmentSnapshotMapper riskSnapshotMapper = mock(RiskAssessmentSnapshotMapper.class);
        OpsMetricSnapshotMapper metricSnapshotMapper = mock(OpsMetricSnapshotMapper.class);
        ProviderObservabilityService observabilityService = mock(ProviderObservabilityService.class);
        when(callLogMapper.selectPage(any(), any())).thenReturn(new Page<>());
        ProviderAdminService service = providerAdminService(
            registryMapper, callLogMapper, locationMapper, weatherSnapshotMapper, riskSnapshotMapper,
            metricSnapshotMapper, observabilityService);
        ProviderQuery query = new ProviderQuery();
        setQueryField(query, "sourceType", sourceType);

        service.callLogs(query);

        ArgumentCaptor<QueryWrapper<ProviderCallLog>> callLogWrapper =
            ArgumentCaptor.forClass(QueryWrapper.class);
        verify(callLogMapper).selectPage(any(), callLogWrapper.capture());
        assertThat(query.normalizedSourceType()).isEqualTo(expected);
        assertThat(callLogWrapper.getValue().getSqlSegment()).contains("source_type");
        assertThat(callLogWrapper.getValue().getParamNameValuePairs()).containsValue(expected);
    }

    @Test
    void unsupportedSourceTypeIsRejectedBeforeQueryingCallLogs() throws Exception {
        ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        ProviderCallLogMapper callLogMapper = mock(ProviderCallLogMapper.class);
        VenueObservationLocationMapper locationMapper = mock(VenueObservationLocationMapper.class);
        WeatherObservationSnapshotMapper weatherSnapshotMapper = mock(WeatherObservationSnapshotMapper.class);
        RiskAssessmentSnapshotMapper riskSnapshotMapper = mock(RiskAssessmentSnapshotMapper.class);
        OpsMetricSnapshotMapper metricSnapshotMapper = mock(OpsMetricSnapshotMapper.class);
        ProviderObservabilityService observabilityService = mock(ProviderObservabilityService.class);
        when(callLogMapper.selectPage(any(), any())).thenReturn(new Page<>());
        ProviderAdminService service = providerAdminService(
            registryMapper, callLogMapper, locationMapper, weatherSnapshotMapper, riskSnapshotMapper,
            metricSnapshotMapper, observabilityService);
        ProviderQuery query = new ProviderQuery();
        setQueryField(query, "sourceType", " batch ");

        assertThatThrownBy(() -> service.callLogs(query))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("sourceType");

        verifyNoInteractions(callLogMapper);
    }

    @Test
    void venueIdAndMetricKeyFiltersApplyToRelevantSnapshotQueries() throws Exception {
        ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        ProviderCallLogMapper callLogMapper = mock(ProviderCallLogMapper.class);
        VenueObservationLocationMapper locationMapper = mock(VenueObservationLocationMapper.class);
        WeatherObservationSnapshotMapper weatherSnapshotMapper = mock(WeatherObservationSnapshotMapper.class);
        RiskAssessmentSnapshotMapper riskSnapshotMapper = mock(RiskAssessmentSnapshotMapper.class);
        OpsMetricSnapshotMapper metricSnapshotMapper = mock(OpsMetricSnapshotMapper.class);
        ProviderObservabilityService observabilityService = mock(ProviderObservabilityService.class);
        when(weatherSnapshotMapper.selectPage(any(), any())).thenReturn(new Page<>());
        when(riskSnapshotMapper.selectPage(any(), any())).thenReturn(new Page<>());
        when(metricSnapshotMapper.selectPage(any(), any())).thenReturn(new Page<>());
        ProviderAdminService service = providerAdminService(
            registryMapper, callLogMapper, locationMapper, weatherSnapshotMapper, riskSnapshotMapper,
            metricSnapshotMapper, observabilityService);
        ProviderQuery query = new ProviderQuery();
        setQueryField(query, "venueId", 7L);
        setQueryField(query, "metricKey", "redis_hit_rate");

        service.weatherSnapshots(query);
        service.riskSnapshots(query);
        service.metricSnapshots(query);

        ArgumentCaptor<QueryWrapper<WeatherObservationSnapshot>> weatherWrapper =
            ArgumentCaptor.forClass(QueryWrapper.class);
        ArgumentCaptor<QueryWrapper<RiskAssessmentSnapshot>> riskWrapper =
            ArgumentCaptor.forClass(QueryWrapper.class);
        ArgumentCaptor<QueryWrapper<OpsMetricSnapshot>> metricWrapper =
            ArgumentCaptor.forClass(QueryWrapper.class);
        verify(weatherSnapshotMapper).selectPage(any(), weatherWrapper.capture());
        verify(riskSnapshotMapper).selectPage(any(), riskWrapper.capture());
        verify(metricSnapshotMapper).selectPage(any(), metricWrapper.capture());

        assertThat(weatherWrapper.getValue().getSqlSegment()).contains("venue_id");
        assertThat(weatherWrapper.getValue().getParamNameValuePairs()).containsValue(7L);
        assertThat(riskWrapper.getValue().getSqlSegment()).contains("scope_type", "scope_id");
        assertThat(riskWrapper.getValue().getParamNameValuePairs()).containsValue("VENUE").containsValue(7L);
        assertThat(metricWrapper.getValue().getSqlSegment()).contains("metric_key");
        assertThat(metricWrapper.getValue().getParamNameValuePairs()).containsValue("redis_hit_rate");
    }

    @Test
    void overviewUsesOneFixedTimestampForFreshnessDeadlineAndEvidenceAge() {
        ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        ProviderCallLogMapper callLogMapper = mock(ProviderCallLogMapper.class);
        VenueObservationLocationMapper locationMapper = mock(VenueObservationLocationMapper.class);
        WeatherObservationSnapshotMapper weatherSnapshotMapper = mock(WeatherObservationSnapshotMapper.class);
        RiskAssessmentSnapshotMapper riskSnapshotMapper = mock(RiskAssessmentSnapshotMapper.class);
        OpsMetricSnapshotMapper metricSnapshotMapper = mock(OpsMetricSnapshotMapper.class);
        ProviderObservabilityService observabilityService = mock(ProviderObservabilityService.class);
        when(registryMapper.selectList(any())).thenReturn(List.of(
            registryEntry("provider.disabled", 0, NOW.minusHours(1), 60),
            registryEntry("provider.unknown", 1, null, 60),
            registryEntry("provider.fresh", 1, NOW.minusSeconds(1_799), 1_800),
            registryEntry("provider.stale", 1, NOW.minusSeconds(1_800), 1_800),
            registryEntry("provider.zero-freshness", 1, NOW, 0)));
        Clock overviewClock = mock(Clock.class);
        when(overviewClock.instant()).thenReturn(FIXED_CLOCK.instant());
        when(overviewClock.getZone()).thenReturn(APPLICATION_ZONE);
        ProviderAdminService service = providerAdminService(
            registryMapper, callLogMapper, locationMapper, weatherSnapshotMapper, riskSnapshotMapper,
            metricSnapshotMapper, observabilityService, overviewClock);

        var overview = service.overview();

        verify(overviewClock).instant();
        verify(overviewClock).getZone();

        var disabled = overview.providers.get(0);
        assertThat(disabled.enabled).isFalse();
        assertThat(disabled.configVersion).isEqualTo("PROVIDER_DISABLED_V1");
        assertThat(disabled.freshnessSeconds).isEqualTo(60);
        assertThat(disabled.freshnessDeadlineAt).isEqualTo(NOW.minusMinutes(59));
        assertThat(disabled.evidenceAgeSeconds).isEqualTo(3_600L);
        assertThat(disabled.status).isEqualTo("ACTIVE");
        assertThat(disabled.freshnessStatus).isEqualTo("DISABLED");

        var unknown = overview.providers.get(1);
        assertThat(unknown.enabled).isTrue();
        assertThat(unknown.freshnessDeadlineAt).isNull();
        assertThat(unknown.evidenceAgeSeconds).isNull();
        assertThat(unknown.freshnessStatus).isEqualTo("UNKNOWN");

        var fresh = overview.providers.get(2);
        assertThat(fresh.freshnessDeadlineAt).isEqualTo(NOW.plusSeconds(1));
        assertThat(fresh.evidenceAgeSeconds).isEqualTo(1_799L);
        assertThat(fresh.freshnessStatus).isEqualTo("FRESH");

        var stale = overview.providers.get(3);
        assertThat(stale.enabled).isTrue();
        assertThat(stale.configVersion).isEqualTo("PROVIDER_STALE_V1");
        assertThat(stale.freshnessSeconds).isEqualTo(1_800);
        assertThat(stale.freshnessDeadlineAt).isEqualTo(NOW);
        assertThat(stale.evidenceAgeSeconds).isEqualTo(1_800L);
        assertThat(stale.status).isEqualTo("ACTIVE");
        assertThat(stale.freshnessStatus).isEqualTo("STALE");

        var zeroFreshness = overview.providers.get(4);
        assertThat(zeroFreshness.freshnessDeadlineAt).isEqualTo(NOW);
        assertThat(zeroFreshness.evidenceAgeSeconds).isZero();
        assertThat(zeroFreshness.freshnessStatus).isEqualTo("STALE");
    }

    @Test
    void overviewTreatsNullAndZeroEnabledFlagsAsDisabled() {
        ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        when(registryMapper.selectList(any())).thenReturn(List.of(
            registryEntry("provider.null-enabled", null, null, 60),
            registryEntry("provider.zero-enabled", 0, null, 60)));
        ProviderAdminService service = providerAdminService(
            registryMapper,
            mock(ProviderCallLogMapper.class),
            mock(VenueObservationLocationMapper.class),
            mock(WeatherObservationSnapshotMapper.class),
            mock(RiskAssessmentSnapshotMapper.class),
            mock(OpsMetricSnapshotMapper.class),
            mock(ProviderObservabilityService.class));

        var overview = service.overview();

        assertThat(overview.providers).allSatisfy(status -> {
            assertThat(status.enabled).isFalse();
            assertThat(status.freshnessStatus).isEqualTo("DISABLED");
        });
    }

    @Test
    void snapshotResponsesDoNotExposePersistedRawSourcePayloads() {
        ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        ProviderCallLogMapper callLogMapper = mock(ProviderCallLogMapper.class);
        VenueObservationLocationMapper locationMapper = mock(VenueObservationLocationMapper.class);
        WeatherObservationSnapshotMapper weatherSnapshotMapper = mock(WeatherObservationSnapshotMapper.class);
        RiskAssessmentSnapshotMapper riskSnapshotMapper = mock(RiskAssessmentSnapshotMapper.class);
        OpsMetricSnapshotMapper metricSnapshotMapper = mock(OpsMetricSnapshotMapper.class);
        ProviderObservabilityService observabilityService = mock(ProviderObservabilityService.class);
        WeatherObservationSnapshot weather = new WeatherObservationSnapshot();
        weather.rawSummaryJson = "{\"persistedRawWeather\":\"NOT_FOR_RESPONSE\"}";
        RiskAssessmentSnapshot risk = new RiskAssessmentSnapshot();
        risk.ruleSummaryJson = "{\"persistedRawRisk\":\"NOT_FOR_RESPONSE\"}";
        OpsMetricSnapshot metric = new OpsMetricSnapshot();
        metric.sourceSummaryJson = "{\"persistedRawMetrics\":\"NOT_FOR_RESPONSE\"}";
        Page<WeatherObservationSnapshot> weatherPage = new Page<>();
        Page<RiskAssessmentSnapshot> riskPage = new Page<>();
        Page<OpsMetricSnapshot> metricPage = new Page<>();
        weatherPage.setRecords(List.of(weather));
        riskPage.setRecords(List.of(risk));
        metricPage.setRecords(List.of(metric));
        when(weatherSnapshotMapper.selectPage(any(), any())).thenReturn(weatherPage);
        when(riskSnapshotMapper.selectPage(any(), any())).thenReturn(riskPage);
        when(metricSnapshotMapper.selectPage(any(), any())).thenReturn(metricPage);
        ProviderAdminService service = providerAdminService(
            registryMapper, callLogMapper, locationMapper, weatherSnapshotMapper, riskSnapshotMapper,
            metricSnapshotMapper, observabilityService);

        var weatherResponse = service.weatherSnapshots(new ProviderQuery()).getRecords().get(0);
        var riskResponse = service.riskSnapshots(new ProviderQuery()).getRecords().get(0);
        var metricResponse = service.metricSnapshots(new ProviderQuery()).getRecords().get(0);

        assertThat(weatherResponse.rawSummaryJson).isNull();
        assertThat(riskResponse.ruleSummaryJson).isNull();
        assertThat(metricResponse.sourceSummaryJson).isNull();
    }

    private void setQueryField(ProviderQuery query, String fieldName, Object value) throws Exception {
        ProviderQuery.class.getField(fieldName).set(query, value);
    }

    private ProviderRegistryEntry registryEntry(String providerKey,
                                                Integer enabled,
                                                LocalDateTime lastSuccessAt,
                                                Integer freshnessSeconds) {
        ProviderRegistryEntry entry = new ProviderRegistryEntry();
        entry.providerKey = providerKey;
        entry.providerDomain = "FUTURE_DOMAIN";
        entry.providerEnv = "DEV";
        entry.providerName = providerKey.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        entry.displayName = providerKey;
        entry.configVersion = entry.providerName + "_V1";
        entry.enabled = enabled;
        entry.status = "ACTIVE";
        entry.freshnessSeconds = freshnessSeconds;
        entry.lastSuccessAt = lastSuccessAt;
        entry.consecutiveFailures = 0;
        entry.deleted = 0;
        return entry;
    }

    private ProviderAdminService providerAdminService(ProviderRegistryEntryMapper registryMapper,
                                                       ProviderCallLogMapper callLogMapper,
                                                       VenueObservationLocationMapper locationMapper,
                                                       WeatherObservationSnapshotMapper weatherSnapshotMapper,
                                                       RiskAssessmentSnapshotMapper riskSnapshotMapper,
                                                       OpsMetricSnapshotMapper metricSnapshotMapper,
                                                       ProviderObservabilityService observabilityService) {
        return providerAdminService(
            registryMapper,
            callLogMapper,
            locationMapper,
            weatherSnapshotMapper,
            riskSnapshotMapper,
            metricSnapshotMapper,
            observabilityService,
            FIXED_CLOCK);
    }

    private ProviderAdminService providerAdminService(ProviderRegistryEntryMapper registryMapper,
                                                       ProviderCallLogMapper callLogMapper,
                                                       VenueObservationLocationMapper locationMapper,
                                                       WeatherObservationSnapshotMapper weatherSnapshotMapper,
                                                       RiskAssessmentSnapshotMapper riskSnapshotMapper,
                                                       OpsMetricSnapshotMapper metricSnapshotMapper,
                                                       ProviderObservabilityService observabilityService,
                                                       Clock clock) {
        return providerAdminService(
            registryMapper,
            callLogMapper,
            locationMapper,
            weatherSnapshotMapper,
            riskSnapshotMapper,
            metricSnapshotMapper,
            observabilityService,
            mock(VenueMapper.class),
            clock);
    }

    private ProviderAdminService providerAdminService(ProviderRegistryEntryMapper registryMapper,
                                                       ProviderCallLogMapper callLogMapper,
                                                       VenueObservationLocationMapper locationMapper,
                                                       WeatherObservationSnapshotMapper weatherSnapshotMapper,
                                                       RiskAssessmentSnapshotMapper riskSnapshotMapper,
                                                       OpsMetricSnapshotMapper metricSnapshotMapper,
                                                       ProviderObservabilityService observabilityService,
                                                       VenueMapper venueMapper,
                                                       Clock clock) {
        return new ProviderAdminService(
            registryMapper,
            callLogMapper,
            locationMapper,
            venueMapper,
            weatherSnapshotMapper,
            riskSnapshotMapper,
            metricSnapshotMapper,
            observabilityService,
            mock(WeatherObservationCollectionService.class),
            mock(RiskObservationCollectionService.class),
            mock(RuntimeMetricsCollectionService.class),
            clock);
    }

    private ProviderAdminService locationService(VenueObservationLocationMapper locationMapper, VenueMapper venueMapper) {
        return providerAdminService(
            mock(ProviderRegistryEntryMapper.class),
            mock(ProviderCallLogMapper.class),
            locationMapper,
            mock(WeatherObservationSnapshotMapper.class),
            mock(RiskAssessmentSnapshotMapper.class),
            mock(OpsMetricSnapshotMapper.class),
            mock(ProviderObservabilityService.class),
            venueMapper,
            FIXED_CLOCK);
    }

    private VenueObservationLocationRequest validLocationRequest() {
        VenueObservationLocationRequest request = new VenueObservationLocationRequest();
        request.latitude = new java.math.BigDecimal("31.2304");
        request.longitude = new java.math.BigDecimal("121.4737");
        request.confidence = new java.math.BigDecimal("0.9000");
        request.locationLabel = "室外观测点";
        return request;
    }

    private Venue activeVenue(Long venueId) {
        Venue venue = new Venue();
        venue.id = venueId;
        venue.deleted = 0;
        return venue;
    }
}
