package com.example.venue.provider;

import com.example.venue.provider.config.ProviderObservabilityProperties;
import com.example.venue.provider.mapper.OpsMetricSnapshotMapper;
import com.example.venue.provider.mapper.ProviderCallLogMapper;
import com.example.venue.provider.mapper.RiskAssessmentSnapshotMapper;
import com.example.venue.provider.mapper.WeatherObservationSnapshotMapper;
import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import com.example.venue.provider.service.ProviderEvidenceRetentionService;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProviderEvidenceRetentionServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2000, 1, 31, 12, 0);
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);

    private ProviderObservabilityProperties properties;

    @BeforeEach
    void resetRetentionSettings() {
        properties = new ProviderObservabilityProperties();
        properties.getRetention().setSnapshotDays(30);
        properties.getRetention().setCallLogDays(30);
        properties.getRetention().setBatchSize(100);
        properties.getRetention().setMaxBatchesPerRun(5);
    }

    @Test
    void retentionUsesIdAscendingBatchesAndStopsAfterTheConfiguredMaximumPerTable() {
        properties.getRetention().setBatchSize(2);
        properties.getRetention().setMaxBatchesPerRun(2);
        ProviderCallLogMapper isolatedCallMapper = mock(ProviderCallLogMapper.class);
        WeatherObservationSnapshotMapper isolatedWeatherMapper = mock(WeatherObservationSnapshotMapper.class);
        RiskAssessmentSnapshotMapper isolatedRiskMapper = mock(RiskAssessmentSnapshotMapper.class);
        OpsMetricSnapshotMapper isolatedMetricMapper = mock(OpsMetricSnapshotMapper.class);
        PlatformTransactionManager isolatedTransactionManager = transactionManager();
        ProviderEvidenceRetentionService isolatedService = new ProviderEvidenceRetentionService(
            properties, FIXED_CLOCK, new InProcessProviderRunGuard(), isolatedCallMapper, isolatedWeatherMapper,
            isolatedRiskMapper, isolatedMetricMapper, isolatedTransactionManager);

        when(isolatedWeatherMapper.selectRetentionCandidateIds(NOW.minusDays(30), 2))
            .thenReturn(List.of(11L, 12L), List.of(13L, 14L));

        isolatedService.runOnce();

        verify(isolatedWeatherMapper).physicalDeleteIds(List.of(11L, 12L));
        verify(isolatedWeatherMapper).physicalDeleteIds(List.of(13L, 14L));
        verify(isolatedTransactionManager, org.mockito.Mockito.times(2)).commit(any());
    }

    @Test
    void retentionStopsAfterAnInjectedFirstBatchFailureWithoutTouchingLaterEvidenceTypes() {
        ProviderCallLogMapper failingCallMapper = mock(ProviderCallLogMapper.class);
        WeatherObservationSnapshotMapper untouchedWeatherMapper = mock(WeatherObservationSnapshotMapper.class);
        RiskAssessmentSnapshotMapper untouchedRiskMapper = mock(RiskAssessmentSnapshotMapper.class);
        OpsMetricSnapshotMapper untouchedMetricMapper = mock(OpsMetricSnapshotMapper.class);
        PlatformTransactionManager isolatedTransactionManager = transactionManager();
        ProviderEvidenceRetentionService isolatedService = new ProviderEvidenceRetentionService(
            properties, FIXED_CLOCK, new InProcessProviderRunGuard(), failingCallMapper, untouchedWeatherMapper,
            untouchedRiskMapper, untouchedMetricMapper, isolatedTransactionManager);

        when(failingCallMapper.selectRetentionCandidateIds(any(LocalDateTime.class), eq(100)))
            .thenReturn(List.of(9001L));
        doThrow(new IllegalStateException("injected first batch failure"))
            .when(failingCallMapper).physicalDeleteIds(List.of(9001L));

        isolatedService.runOnce();

        verify(failingCallMapper).physicalDeleteIds(List.of(9001L));
        verify(isolatedTransactionManager).rollback(any());
        verifyNoInteractions(untouchedWeatherMapper, untouchedRiskMapper, untouchedMetricMapper);
    }

    @Test
    void busyRetentionLaneReturnsWithoutSelectingOrCreatingProviderEvidence() {
        ProviderCallLogMapper isolatedCallMapper = mock(ProviderCallLogMapper.class);
        WeatherObservationSnapshotMapper isolatedWeatherMapper = mock(WeatherObservationSnapshotMapper.class);
        RiskAssessmentSnapshotMapper isolatedRiskMapper = mock(RiskAssessmentSnapshotMapper.class);
        OpsMetricSnapshotMapper isolatedMetricMapper = mock(OpsMetricSnapshotMapper.class);
        InProcessProviderRunGuard isolatedGuard = new InProcessProviderRunGuard();
        PlatformTransactionManager isolatedTransactionManager = transactionManager();
        ProviderEvidenceRetentionService isolatedService = new ProviderEvidenceRetentionService(
            properties, FIXED_CLOCK, isolatedGuard, isolatedCallMapper, isolatedWeatherMapper, isolatedRiskMapper,
            isolatedMetricMapper, isolatedTransactionManager);

        assertThat(isolatedGuard.tryAcquire(InProcessProviderRunGuard.Lane.RETENTION)).isTrue();
        try {
            isolatedService.runOnce();
        } finally {
            isolatedGuard.release(InProcessProviderRunGuard.Lane.RETENTION);
        }

        verifyNoInteractions(isolatedCallMapper, isolatedWeatherMapper, isolatedRiskMapper, isolatedMetricMapper);
    }

    @Test
    void retentionMapperSqlKeepsProtectionInTheDatabaseAndUsesBoundedPhysicalDeletes() throws Exception {
        String weatherSql = selectSql(WeatherObservationSnapshotMapper.class);
        assertThat(weatherSql).contains("weather_observation_snapshot candidate", "candidate.provider_key",
            "candidate.venue_id", "candidate.deleted = 1", "protected_weather.deleted = 0",
            "MAX(protected_weather.id)", "ORDER BY candidate.id ASC", "LIMIT #{limit}");

        String riskSql = selectSql(RiskAssessmentSnapshotMapper.class);
        assertThat(riskSql).contains("risk_assessment_snapshot candidate", "COALESCE(candidate.scope_id, 0)",
            "COALESCE(protected_risk.scope_id, 0)", "candidate.deleted = 1", "protected_risk.deleted = 0",
            "MAX(protected_risk.id)", "ORDER BY candidate.id ASC", "LIMIT #{limit}");

        String metricSql = selectSql(OpsMetricSnapshotMapper.class);
        assertThat(metricSql).contains("ops_metric_snapshot candidate", "status IN ('OK', 'DEGRADED')",
            "candidate.deleted = 1", "protected_valid.deleted = 0", "protected_any.deleted = 0", "COALESCE(",
            "MAX(protected_valid.id)", "MAX(protected_any.id)", "ORDER BY candidate.id ASC", "LIMIT #{limit}");

        String callSql = selectSql(ProviderCallLogMapper.class);
        assertThat(callSql).contains("provider_call_log candidate", "status IN ('SUCCESS', 'DEGRADED')",
            "MAX(protected_success.id)", "MAX(protected_any.id)", "snapshot_type = 'WEATHER'",
            "snapshot_type = 'RISK'", "snapshot_type = 'METRIC'", "protected_weather.deleted = 0",
            "protected_risk.deleted = 0", "protected_metric.deleted = 0", "ORDER BY candidate.id ASC", "LIMIT #{limit}");
        assertPhysicalDeleteSql(ProviderCallLogMapper.class, "provider_call_log");
        assertPhysicalDeleteSql(WeatherObservationSnapshotMapper.class, "weather_observation_snapshot");
        assertPhysicalDeleteSql(RiskAssessmentSnapshotMapper.class, "risk_assessment_snapshot");
        assertPhysicalDeleteSql(OpsMetricSnapshotMapper.class, "ops_metric_snapshot");
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        return transactionManager;
    }

    private void assertPhysicalDeleteSql(Class<?> mapperType, String table) throws Exception {
        Method method = mapperType.getMethod("physicalDeleteIds", List.class);
        Delete delete = method.getAnnotation(Delete.class);
        assertThat(delete).isNotNull();
        String sql = String.join(" ", delete.value()).toUpperCase(Locale.ROOT);
        assertThat(sql).contains("DELETE FROM " + table.toUpperCase(Locale.ROOT));
        assertThat(sql).doesNotContain("UPDATE ").doesNotContain("DELETED =");
    }

    private String selectSql(Class<?> mapperType) throws Exception {
        Method method = mapperType.getMethod("selectRetentionCandidateIds", LocalDateTime.class, int.class);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).isNotNull();
        return String.join(" ", select.value());
    }

}
