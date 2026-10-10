package com.example.venue.provider;

import com.example.venue.provider.dto.ProviderOverviewResponse;
import com.example.venue.provider.dto.ProviderRefreshResponse;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.common.CurrentUser;
import com.example.venue.provider.entity.OpsMetricSnapshot;
import com.example.venue.provider.mapper.OpsMetricSnapshotMapper;
import com.example.venue.provider.mapper.ProviderCallLogMapper;
import com.example.venue.provider.mapper.ProviderRegistryEntryMapper;
import com.example.venue.provider.mapper.RiskAssessmentSnapshotMapper;
import com.example.venue.provider.mapper.VenueObservationLocationMapper;
import com.example.venue.provider.mapper.WeatherObservationSnapshotMapper;
import com.example.venue.provider.metrics.LocalRuntimeMetricsProvider;
import com.example.venue.provider.service.ProviderAdminService;
import com.example.venue.provider.service.ProviderObservabilityService;
import com.example.venue.provider.service.RiskObservationCollectionService;
import com.example.venue.provider.service.RuntimeMetricsCollectionService;
import com.example.venue.provider.service.WeatherObservationCollectionService;
import com.example.venue.venue.mapper.VenueMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.time.Clock;
import java.sql.Connection;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MetricsProviderServiceTest {
    private static final String SLOW_QUERY_GAP = "完整慢查询数据未接入。";
    private static final String TASK_2_OVERVIEW_GAP = "Prometheus/APM、完整慢查询和分布式追踪未接入。";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedisConnectionFactory redisConnectionFactory = mock(RedisConnectionFactory.class);
    private final RedisConnection redisConnection = mock(RedisConnection.class);
    private final RedisServerCommands redisServerCommands = mock(RedisServerCommands.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection dbConnection = mock(Connection.class);

    @BeforeEach
    void authenticateManualProviderAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(7L, "admin", Set.of("SYSTEM_ADMIN"), Set.of("provider:refresh")), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void collectWhenRedisAvailableReturnsRedisDbAndJvmMetricsWithoutSlowQueryMetric() throws Exception {
        availableRedis(80, 20);
        availableDb();
        LocalRuntimeMetricsProvider provider = new LocalRuntimeMetricsProvider(redisTemplate, dataSource);

        List<OpsMetricSnapshot> snapshots = provider.collect();

        Set<String> keys = metricKeys(snapshots);
        assertThat(keys).contains("redis_ping_ms", "redis_hit_rate", "db_ping_ms", "jvm_memory_used_mb");
        assertThat(keys).doesNotContain("slow_query");
        OpsMetricSnapshot hitRate = metric(snapshots, "redis_hit_rate");
        assertThat(hitRate.status).isEqualTo("OK");
        assertThat(hitRate.metricValue).isEqualByComparingTo("0.8000");
        assertThat(hitRate.metricUnit).isEqualTo("ratio");
        assertThat(hitRate.confidence).isEqualByComparingTo("0.9000");
    }

    @Test
    void refreshMetricsDelegatesTheManualUserContextAndPreservesTheCollectorResponse() {
        ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        ProviderCallLogMapper callLogMapper = mock(ProviderCallLogMapper.class);
        VenueObservationLocationMapper locationMapper = mock(VenueObservationLocationMapper.class);
        WeatherObservationSnapshotMapper weatherSnapshotMapper = mock(WeatherObservationSnapshotMapper.class);
        RiskAssessmentSnapshotMapper riskSnapshotMapper = mock(RiskAssessmentSnapshotMapper.class);
        OpsMetricSnapshotMapper metricSnapshotMapper = mock(OpsMetricSnapshotMapper.class);
        ProviderObservabilityService providerObservabilityService = mock(ProviderObservabilityService.class);
        WeatherObservationCollectionService weatherCollectionService = mock(WeatherObservationCollectionService.class);
        RiskObservationCollectionService riskCollectionService = mock(RiskObservationCollectionService.class);
        RuntimeMetricsCollectionService metricsCollectionService = mock(RuntimeMetricsCollectionService.class);
        ProviderAdminService service = new ProviderAdminService(
            registryMapper,
            callLogMapper,
            locationMapper,
            mock(VenueMapper.class),
            weatherSnapshotMapper,
            riskSnapshotMapper,
            metricSnapshotMapper,
            providerObservabilityService,
            weatherCollectionService,
            riskCollectionService,
            metricsCollectionService,
            Clock.systemUTC());
        ProviderRefreshResponse expected = new ProviderRefreshResponse();
        expected.status = "DEGRADED";
        expected.providerKey = "metrics.local_runtime.dev";
        when(metricsCollectionService.collect(any(ProviderRunContext.class))).thenReturn(expected);

        ProviderRefreshResponse actual = service.refreshMetrics();

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<ProviderRunContext> contextCaptor = ArgumentCaptor.forClass(ProviderRunContext.class);
        verify(metricsCollectionService).collect(contextCaptor.capture());
        assertThat(contextCaptor.getValue()).isEqualTo(ProviderRunContext.manual(7L));
        verifyNoInteractions(weatherCollectionService, riskCollectionService);
    }

    @Test
    void overviewKeepsTask2ProviderGapWhileMetricsRefreshOwnsTask4SlowQueryGap() {
        ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        ProviderCallLogMapper callLogMapper = mock(ProviderCallLogMapper.class);
        VenueObservationLocationMapper locationMapper = mock(VenueObservationLocationMapper.class);
        WeatherObservationSnapshotMapper weatherSnapshotMapper = mock(WeatherObservationSnapshotMapper.class);
        RiskAssessmentSnapshotMapper riskSnapshotMapper = mock(RiskAssessmentSnapshotMapper.class);
        OpsMetricSnapshotMapper metricSnapshotMapper = mock(OpsMetricSnapshotMapper.class);
        ProviderObservabilityService providerObservabilityService = mock(ProviderObservabilityService.class);
        when(registryMapper.selectList(any())).thenReturn(List.of());
        ProviderAdminService service = providerAdminService(
            registryMapper,
            callLogMapper,
            locationMapper,
            weatherSnapshotMapper,
            riskSnapshotMapper,
            metricSnapshotMapper,
            providerObservabilityService);

        ProviderOverviewResponse response = service.overview();

        assertThat(response.dataGaps).contains(TASK_2_OVERVIEW_GAP);
        assertThat(response.dataGaps).doesNotContain("Prometheus/APM 和分布式追踪未接入。");
        assertThat(response.dataGaps).doesNotContain(SLOW_QUERY_GAP);
    }

    private void availableRedis(long hits, long misses) {
        Properties stats = new Properties();
        stats.setProperty("keyspace_hits", Long.toString(hits));
        stats.setProperty("keyspace_misses", Long.toString(misses));
        when(redisTemplate.getConnectionFactory()).thenReturn(redisConnectionFactory);
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");
        when(redisConnection.serverCommands()).thenReturn(redisServerCommands);
        when(redisServerCommands.info("stats")).thenReturn(stats);
    }

    private void availableDb() throws Exception {
        when(dataSource.getConnection()).thenReturn(dbConnection);
        when(dbConnection.isValid(2)).thenReturn(true);
    }

    private Set<String> metricKeys(List<OpsMetricSnapshot> snapshots) {
        return snapshots.stream().map(snapshot -> snapshot.metricKey).collect(java.util.stream.Collectors.toSet());
    }

    private OpsMetricSnapshot metric(List<OpsMetricSnapshot> snapshots, String key) {
        return snapshots.stream()
            .filter(snapshot -> key.equals(snapshot.metricKey))
            .findFirst()
            .orElseThrow();
    }

    private ProviderAdminService providerAdminService(ProviderRegistryEntryMapper registryMapper,
                                                       ProviderCallLogMapper callLogMapper,
                                                       VenueObservationLocationMapper locationMapper,
                                                       WeatherObservationSnapshotMapper weatherSnapshotMapper,
                                                       RiskAssessmentSnapshotMapper riskSnapshotMapper,
                                                       OpsMetricSnapshotMapper metricSnapshotMapper,
                                                       ProviderObservabilityService observabilityService) {
        return new ProviderAdminService(
            registryMapper,
            callLogMapper,
            locationMapper,
            mock(VenueMapper.class),
            weatherSnapshotMapper,
            riskSnapshotMapper,
            metricSnapshotMapper,
            observabilityService,
            mock(WeatherObservationCollectionService.class),
            mock(RiskObservationCollectionService.class),
            mock(RuntimeMetricsCollectionService.class),
            Clock.systemUTC());
    }
}
