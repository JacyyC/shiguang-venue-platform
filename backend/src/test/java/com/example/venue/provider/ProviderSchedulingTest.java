package com.example.venue.provider;

import com.example.venue.provider.config.ProviderObservabilityProperties;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.risk.InternalRiskRulesProvider;
import com.example.venue.provider.schedule.ProviderRetentionScheduler;
import com.example.venue.provider.schedule.RiskObservationScheduler;
import com.example.venue.provider.schedule.RuntimeMetricsScheduler;
import com.example.venue.provider.schedule.WeatherObservationScheduler;
import com.example.venue.provider.service.ProviderAdminService;
import com.example.venue.provider.service.ProviderEvidenceRetentionService;
import com.example.venue.provider.service.RiskObservationCollectionService;
import com.example.venue.provider.service.RuntimeMetricsCollectionService;
import com.example.venue.provider.service.WeatherObservationCollectionService;
import com.example.venue.provider.metrics.LocalRuntimeMetricsProvider;
import com.example.venue.provider.weather.WeatherProvider;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProviderSchedulingTest {
    @Test
    void schedulersUseDedicatedProviderFixedDelayConfiguration() throws Exception {
        assertScheduledMethod(
            WeatherObservationScheduler.class,
            "collectActiveLocations",
            "${app.provider.observability.weather.initial-delay-ms}",
            "${app.provider.observability.weather.fixed-delay-ms}");
        assertScheduledMethod(
            RiskObservationScheduler.class,
            "collectSystemRisk",
            "${app.provider.observability.risk.initial-delay-ms}",
            "${app.provider.observability.risk.fixed-delay-ms}");
        assertScheduledMethod(
            RuntimeMetricsScheduler.class,
            "collectRuntimeMetrics",
            "${app.provider.observability.metrics.initial-delay-ms}",
            "${app.provider.observability.metrics.fixed-delay-ms}");
        assertScheduledMethod(
            ProviderRetentionScheduler.class,
            "runRetention",
            "${app.provider.observability.retention.initial-delay-ms}",
            "${app.provider.observability.retention.fixed-delay-ms}");
    }

    @Test
    void enabledSchedulersInvokeOnlyTheirOwnLaneWithScheduledContext() {
        ProviderObservabilityProperties properties = new ProviderObservabilityProperties();
        WeatherObservationCollectionService weatherService = mock(WeatherObservationCollectionService.class);
        RiskObservationCollectionService riskService = mock(RiskObservationCollectionService.class);
        RuntimeMetricsCollectionService metricsService = mock(RuntimeMetricsCollectionService.class);
        ProviderEvidenceRetentionService retentionService = mock(ProviderEvidenceRetentionService.class);

        new WeatherObservationScheduler(properties, weatherService).collectActiveLocations();
        new RiskObservationScheduler(properties, riskService).collectSystemRisk();
        new RuntimeMetricsScheduler(properties, metricsService).collectRuntimeMetrics();
        new ProviderRetentionScheduler(properties, retentionService).runRetention();

        verify(weatherService).collectActiveLocations(ProviderRunContext.scheduled());
        verify(riskService).collect(null, ProviderRunContext.scheduled());
        verify(metricsService).collect(ProviderRunContext.scheduled());
        verify(retentionService).runOnce();
    }

    @Test
    void globalDisableSkipsEveryLaneWithoutInvokingCollectors() {
        ProviderObservabilityProperties properties = new ProviderObservabilityProperties();
        properties.setScheduledEnabled(false);
        WeatherObservationCollectionService weatherService = mock(WeatherObservationCollectionService.class);
        RiskObservationCollectionService riskService = mock(RiskObservationCollectionService.class);
        RuntimeMetricsCollectionService metricsService = mock(RuntimeMetricsCollectionService.class);
        ProviderEvidenceRetentionService retentionService = mock(ProviderEvidenceRetentionService.class);

        new WeatherObservationScheduler(properties, weatherService).collectActiveLocations();
        new RiskObservationScheduler(properties, riskService).collectSystemRisk();
        new RuntimeMetricsScheduler(properties, metricsService).collectRuntimeMetrics();
        new ProviderRetentionScheduler(properties, retentionService).runRetention();

        verifyNoInteractions(weatherService, riskService, metricsService, retentionService);
    }

    @Test
    void eachLaneDisableSkipsOnlyItsOwnCollector() {
        ProviderObservabilityProperties properties = new ProviderObservabilityProperties();
        WeatherObservationCollectionService weatherService = mock(WeatherObservationCollectionService.class);
        RiskObservationCollectionService riskService = mock(RiskObservationCollectionService.class);
        RuntimeMetricsCollectionService metricsService = mock(RuntimeMetricsCollectionService.class);
        ProviderEvidenceRetentionService retentionService = mock(ProviderEvidenceRetentionService.class);
        WeatherObservationScheduler weatherScheduler = new WeatherObservationScheduler(properties, weatherService);
        RiskObservationScheduler riskScheduler = new RiskObservationScheduler(properties, riskService);
        RuntimeMetricsScheduler metricsScheduler = new RuntimeMetricsScheduler(properties, metricsService);
        ProviderRetentionScheduler retentionScheduler = new ProviderRetentionScheduler(properties, retentionService);

        properties.getWeather().setEnabled(false);
        invokeAllSchedulers(weatherScheduler, riskScheduler, metricsScheduler, retentionScheduler);
        verifyNoInteractions(weatherService);
        verify(riskService).collect(null, ProviderRunContext.scheduled());
        verify(metricsService).collect(ProviderRunContext.scheduled());
        verify(retentionService).runOnce();

        clearInvocations(weatherService, riskService, metricsService, retentionService);
        properties.getWeather().setEnabled(true);
        properties.getRisk().setEnabled(false);
        invokeAllSchedulers(weatherScheduler, riskScheduler, metricsScheduler, retentionScheduler);
        verify(weatherService).collectActiveLocations(ProviderRunContext.scheduled());
        verifyNoInteractions(riskService);
        verify(metricsService).collect(ProviderRunContext.scheduled());
        verify(retentionService).runOnce();

        clearInvocations(weatherService, riskService, metricsService, retentionService);
        properties.getRisk().setEnabled(true);
        properties.getMetrics().setEnabled(false);
        invokeAllSchedulers(weatherScheduler, riskScheduler, metricsScheduler, retentionScheduler);
        verify(weatherService).collectActiveLocations(ProviderRunContext.scheduled());
        verify(riskService).collect(null, ProviderRunContext.scheduled());
        verifyNoInteractions(metricsService);
        verify(retentionService).runOnce();

        clearInvocations(weatherService, riskService, metricsService, retentionService);
        properties.getMetrics().setEnabled(true);
        properties.getRetention().setEnabled(false);
        invokeAllSchedulers(weatherScheduler, riskScheduler, metricsScheduler, retentionScheduler);
        verify(weatherService).collectActiveLocations(ProviderRunContext.scheduled());
        verify(riskService).collect(null, ProviderRunContext.scheduled());
        verify(metricsService).collect(ProviderRunContext.scheduled());
        verifyNoInteractions(retentionService);
    }

    private void invokeAllSchedulers(WeatherObservationScheduler weatherScheduler,
                                     RiskObservationScheduler riskScheduler,
                                     RuntimeMetricsScheduler metricsScheduler,
                                     ProviderRetentionScheduler retentionScheduler) {
        weatherScheduler.collectActiveLocations();
        riskScheduler.collectSystemRisk();
        metricsScheduler.collectRuntimeMetrics();
        retentionScheduler.runRetention();
    }

    @Test
    void weatherFailureIsContainedBeforeTheRiskLaneRuns() {
        ProviderObservabilityProperties properties = new ProviderObservabilityProperties();
        WeatherObservationCollectionService weatherService = mock(WeatherObservationCollectionService.class);
        RiskObservationCollectionService riskService = mock(RiskObservationCollectionService.class);
        when(weatherService.collectActiveLocations(any())).thenThrow(new IllegalStateException("weather failure"));

        WeatherObservationScheduler weatherScheduler = new WeatherObservationScheduler(properties, weatherService);
        RiskObservationScheduler riskScheduler = new RiskObservationScheduler(properties, riskService);

        assertThatCode(weatherScheduler::collectActiveLocations).doesNotThrowAnyException();
        riskScheduler.collectSystemRisk();

        verify(weatherService).collectActiveLocations(ProviderRunContext.scheduled());
        verify(riskService).collect(null, ProviderRunContext.scheduled());
    }

    @Test
    void eachLaneContainsItsOwnRuntimeExceptionBoundary() {
        ProviderObservabilityProperties properties = new ProviderObservabilityProperties();
        WeatherObservationCollectionService weatherService = mock(WeatherObservationCollectionService.class);
        RiskObservationCollectionService riskService = mock(RiskObservationCollectionService.class);
        RuntimeMetricsCollectionService metricsService = mock(RuntimeMetricsCollectionService.class);
        ProviderEvidenceRetentionService retentionService = mock(ProviderEvidenceRetentionService.class);
        when(weatherService.collectActiveLocations(any())).thenThrow(new IllegalStateException("weather failure"));
        when(riskService.collect(any(), any())).thenThrow(new IllegalStateException("risk failure"));
        when(metricsService.collect(any())).thenThrow(new IllegalStateException("metrics failure"));
        doThrow(new IllegalStateException("retention failure")).when(retentionService).runOnce();

        assertThatCode(() -> new WeatherObservationScheduler(properties, weatherService).collectActiveLocations())
            .doesNotThrowAnyException();
        assertThatCode(() -> new RiskObservationScheduler(properties, riskService).collectSystemRisk())
            .doesNotThrowAnyException();
        assertThatCode(() -> new RuntimeMetricsScheduler(properties, metricsService).collectRuntimeMetrics())
            .doesNotThrowAnyException();
        assertThatCode(() -> new ProviderRetentionScheduler(properties, retentionService).runRetention())
            .doesNotThrowAnyException();
    }

    @Test
    void manualFacadeDependsOnCollectionServicesWithoutAnOuterTransaction() throws Exception {
        assertThat(Arrays.stream(ProviderAdminService.class.getDeclaredFields())
            .map(field -> field.getType().getName()))
            .doesNotContain(
                WeatherProvider.class.getName(),
                InternalRiskRulesProvider.class.getName(),
                LocalRuntimeMetricsProvider.class.getName());
        assertThat(ProviderAdminService.class.getMethod("refreshWeather", Long.class)
            .getAnnotation(Transactional.class)).isNull();
        assertThat(ProviderAdminService.class.getMethod("refreshRisk", Long.class)
            .getAnnotation(Transactional.class)).isNull();
        assertThat(ProviderAdminService.class.getMethod("refreshMetrics")
            .getAnnotation(Transactional.class)).isNull();
    }

    private void assertScheduledMethod(Class<?> schedulerType,
                                       String methodName,
                                       String initialDelayProperty,
                                       String fixedDelayProperty) throws Exception {
        assertThat(schedulerType.isAnnotationPresent(Component.class)).isTrue();
        Method method = schedulerType.getMethod(methodName);
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo("providerTaskScheduler");
        assertThat(scheduled.initialDelayString()).isEqualTo(initialDelayProperty);
        assertThat(scheduled.fixedDelayString()).isEqualTo(fixedDelayProperty);
        assertThat(scheduled.cron()).isBlank();
        assertThat(scheduled.fixedRateString()).isBlank();
        assertThat(Arrays.stream(schedulerType.getDeclaredMethods())
            .filter(candidate -> candidate.isAnnotationPresent(Scheduled.class)))
            .hasSize(1);
    }
}
