package com.example.venue.provider;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRefreshResponse;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.dto.WeatherBatchOutcome;
import com.example.venue.provider.entity.VenueObservationLocation;
import com.example.venue.provider.entity.WeatherObservationSnapshot;
import com.example.venue.provider.mapper.VenueObservationLocationMapper;
import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import com.example.venue.provider.service.ProviderEvidenceWriter;
import com.example.venue.provider.service.WeatherObservationCollectionService;
import com.example.venue.provider.weather.WeatherProvider;
import com.example.venue.provider.weather.WeatherProviderRequest;
import com.example.venue.provider.weather.WeatherProviderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeatherObservationCollectionServiceTest {
    private static final String MISSING_LOCATION_GAP = "场馆缺少经纬度观测位置，无法拉取天气快照。";
    private static final String RUNNING_GAP = "采集器正在运行，本轮未执行";

    private final VenueObservationLocationMapper locationMapper = mock(VenueObservationLocationMapper.class);
    private final WeatherProvider weatherProvider = mock(WeatherProvider.class);
    private final ProviderEvidenceWriter evidenceWriter = mock(ProviderEvidenceWriter.class);
    private final InProcessProviderRunGuard guard = new InProcessProviderRunGuard();
    private final WeatherObservationCollectionService service = new WeatherObservationCollectionService(
        locationMapper, weatherProvider, evidenceWriter, guard);

    @BeforeEach
    void providerMetadata() {
        when(weatherProvider.providerKey()).thenReturn("weather.open_meteo.dev");
        when(weatherProvider.providerEnv()).thenReturn("DEV");
        when(weatherProvider.providerName()).thenReturn("OPEN_METEO");
        when(weatherProvider.configVersion()).thenReturn("OPEN_METEO_V1");
    }

    @Test
    void manualMissingLocationWritesEvidenceOnlySkippedRecord() {
        when(locationMapper.selectOne(any())).thenReturn(null);

        ProviderRefreshResponse response = service.collectVenue(99L, ProviderRunContext.manual(7L));

        assertThat(response.status).isEqualTo("SKIPPED");
        assertThat(response.providerKey).isEqualTo("weather.open_meteo.dev");
        assertThat(response.snapshotId).isNull();
        assertThat(response.dataGaps).containsExactly(MISSING_LOCATION_GAP);
        verify(weatherProvider, never()).fetch(any());
        ArgumentCaptor<ProviderCallRecord> recordCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(evidenceWriter).writeCallOnly(recordCaptor.capture());
        assertThat(recordCaptor.getValue().status()).isEqualTo("SKIPPED");
        assertThat(recordCaptor.getValue().failureCode()).isEqualTo("MISSING_LOCATION");
        assertThat(recordCaptor.getValue().sourceType()).isEqualTo("MANUAL");
        assertThat(recordCaptor.getValue().triggeredBy()).isEqualTo(7L);
        assertThat(recordCaptor.getValue().updateRegistry()).isFalse();
        verify(evidenceWriter, never()).writeRegistryOutcome(any());
    }

    @Test
    void manualSuccessUsesOneActiveLocationAndWritesRegistryImpactOutcome() {
        when(locationMapper.selectOne(any())).thenReturn(location(7L, 1L));
        when(weatherProvider.fetch(any())).thenReturn(weatherResult());
        ProviderRefreshResponse expected = response("SUCCESS", 101L);
        when(evidenceWriter.writeWeatherSuccess(any(WeatherObservationSnapshot.class), any())).thenReturn(expected);

        ProviderRefreshResponse response = service.collectVenue(1L, ProviderRunContext.manual(7L));

        assertThat(response).isSameAs(expected);
        ArgumentCaptor<WeatherProviderRequest> requestCaptor = ArgumentCaptor.forClass(WeatherProviderRequest.class);
        verify(weatherProvider).fetch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().venueId()).isEqualTo(1L);
        assertThat(requestCaptor.getValue().locationId()).isEqualTo(7L);
        assertThat(requestCaptor.getValue().latitude()).isEqualByComparingTo("31.2304160");
        assertThat(requestCaptor.getValue().longitude()).isEqualByComparingTo("121.4737010");
        ArgumentCaptor<ProviderCallRecord> recordCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(evidenceWriter).writeWeatherSuccess(any(WeatherObservationSnapshot.class), recordCaptor.capture());
        assertThat(recordCaptor.getValue().status()).isEqualTo("SUCCESS");
        assertThat(recordCaptor.getValue().sourceType()).isEqualTo("MANUAL");
        assertThat(recordCaptor.getValue().triggeredBy()).isEqualTo(7L);
        assertThat(recordCaptor.getValue().updateRegistry()).isTrue();
    }

    @Test
    void weatherSnapshotDoesNotRetainRawProviderBodyBeforeEvidenceWrite() {
        when(locationMapper.selectOne(any())).thenReturn(location(7L, 1L));
        when(weatherProvider.fetch(any())).thenReturn(new WeatherProviderResult(
            BigDecimal.valueOf(28.6), BigDecimal.valueOf(1.2), BigDecimal.valueOf(8.4), "61", "NONE",
            "Light rain", BigDecimal.valueOf(0.91), LocalDateTime.of(2026, 7, 13, 12, 0),
            "{\"rawWeatherBody\":\"NOT_FOR_PERSISTENCE\"}"
        ));

        service.collectVenue(1L, ProviderRunContext.manual(7L));

        ArgumentCaptor<WeatherObservationSnapshot> snapshotCaptor =
            ArgumentCaptor.forClass(WeatherObservationSnapshot.class);
        verify(evidenceWriter).writeWeatherSuccess(snapshotCaptor.capture(), any(ProviderCallRecord.class));
        assertThat(snapshotCaptor.getValue().rawSummaryJson).isNull();
    }

    @Test
    void sameWeatherLaneReturnsEvidenceOnlySkippedForTheOverlappingCaller() throws Exception {
        when(locationMapper.selectOne(any())).thenReturn(location(7L, 1L));
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(weatherProvider.fetch(any())).thenAnswer(invocation -> {
            providerEntered.countDown();
            if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("provider was not released");
            }
            return weatherResult();
        });
        when(evidenceWriter.writeWeatherSuccess(any(WeatherObservationSnapshot.class), any()))
            .thenReturn(response("SUCCESS", 101L));
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            Future<ProviderRefreshResponse> first = pool.submit(
                () -> service.collectVenue(1L, ProviderRunContext.scheduled()));
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            ProviderRefreshResponse skipped = service.collectVenue(1L, ProviderRunContext.manual(7L));

            assertThat(skipped.status).isEqualTo("SKIPPED");
            assertThat(skipped.dataGaps).containsExactly(RUNNING_GAP);
            ArgumentCaptor<ProviderCallRecord> recordCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
            verify(evidenceWriter).writeCallOnly(recordCaptor.capture());
            assertThat(recordCaptor.getValue().status()).isEqualTo("SKIPPED");
            assertThat(recordCaptor.getValue().failureCode()).isEqualTo("COLLECTION_ALREADY_RUNNING");
            assertThat(recordCaptor.getValue().sourceType()).isEqualTo("MANUAL");
            assertThat(recordCaptor.getValue().triggeredBy()).isEqualTo(7L);
            assertThat(recordCaptor.getValue().updateRegistry()).isFalse();

            releaseProvider.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status).isEqualTo("SUCCESS");
        } finally {
            releaseProvider.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void scheduledBatchWithoutActiveLocationsWritesNoAggregate() {
        when(locationMapper.selectActiveOrdered()).thenReturn(List.of());

        WeatherBatchOutcome outcome = service.collectActiveLocations(ProviderRunContext.scheduled());

        assertThat(outcome.status()).isEqualTo("SKIPPED");
        assertThat(outcome.successfulLocations()).isZero();
        assertThat(outcome.failedLocations()).isZero();
        verify(weatherProvider, never()).fetch(any());
        ArgumentCaptor<ProviderCallRecord> recordCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(evidenceWriter).writeCallOnly(recordCaptor.capture());
        assertThat(recordCaptor.getValue().status()).isEqualTo("SKIPPED");
        assertThat(recordCaptor.getValue().failureCode()).isEqualTo("NO_ACTIVE_OBSERVATION_LOCATION");
        assertThat(recordCaptor.getValue().sourceType()).isEqualTo("SCHEDULED");
        assertThat(recordCaptor.getValue().updateRegistry()).isFalse();
        verify(evidenceWriter, never()).writeRegistryOutcome(any());
    }

    @Test
    void scheduledBatchTraversesMapperOrderAndWritesOneSuccessAggregate() {
        when(locationMapper.selectActiveOrdered()).thenReturn(List.of(location(3L, 1L), location(9L, 2L)));
        when(weatherProvider.fetch(any())).thenReturn(weatherResult());

        WeatherBatchOutcome outcome = service.collectActiveLocations(ProviderRunContext.scheduled());

        assertThat(outcome.status()).isEqualTo("SUCCESS");
        assertThat(outcome.successfulLocations()).isEqualTo(2);
        assertThat(outcome.failedLocations()).isZero();
        ArgumentCaptor<WeatherProviderRequest> requestCaptor = ArgumentCaptor.forClass(WeatherProviderRequest.class);
        verify(weatherProvider, times(2)).fetch(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues()).extracting(WeatherProviderRequest::locationId)
            .containsExactly(3L, 9L);
        ArgumentCaptor<ProviderCallRecord> detailCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(evidenceWriter, times(2)).writeWeatherDetail(any(WeatherObservationSnapshot.class), detailCaptor.capture());
        assertThat(detailCaptor.getAllValues()).allSatisfy(record -> {
            assertThat(record.status()).isEqualTo("SUCCESS");
            assertThat(record.sourceType()).isEqualTo("SCHEDULED");
            assertThat(record.triggeredBy()).isNull();
            assertThat(record.updateRegistry()).isFalse();
        });
        assertAggregate("SUCCESS");
    }

    @Test
    void scheduledBatchIsolatesWriterFailureAndContinuesRemainingLocationsWithFailedAggregate() {
        // A26-P3-33：writeWeatherDetail 抛异常时不再中断整批，剩余场馆继续采集，聚合登记正常写出。
        // 本场景两个场馆 writeWeatherDetail 均失败，聚合以 FAILED 写出（successes=0）。
        // 原实现直接传播异常导致第二个场馆漏采一轮且聚合登记丢失。
        when(locationMapper.selectActiveOrdered()).thenReturn(List.of(location(3L, 1L), location(9L, 2L)));
        when(weatherProvider.fetch(any())).thenReturn(weatherResult());
        doThrow(new IllegalStateException("weather evidence write failed"))
            .when(evidenceWriter).writeWeatherDetail(any(WeatherObservationSnapshot.class), any(ProviderCallRecord.class));

        WeatherBatchOutcome outcome = service.collectActiveLocations(ProviderRunContext.scheduled());

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(outcome.successfulLocations()).isZero();
        assertThat(outcome.failedLocations()).isEqualTo(2);
        verify(weatherProvider, times(2)).fetch(any());
        verify(evidenceWriter, times(2))
            .writeWeatherDetail(any(WeatherObservationSnapshot.class), any(ProviderCallRecord.class));
        assertAggregate("FAILED");
    }

    @Test
    void scheduledBatchIsolatesWriterFailureAndContinuesWithRemainingSuccessfulLocations() {
        // A26-P3-33 补充：第一个场馆 writeWeatherDetail 失败，第二个场馆正常落库，聚合仍以 DEGRADED 写出。
        when(locationMapper.selectActiveOrdered()).thenReturn(List.of(location(3L, 1L), location(9L, 2L)));
        when(weatherProvider.fetch(any())).thenReturn(weatherResult());
        doThrow(new IllegalStateException("weather evidence write failed"))
            .doNothing()
            .when(evidenceWriter).writeWeatherDetail(any(WeatherObservationSnapshot.class), any(ProviderCallRecord.class));

        WeatherBatchOutcome outcome = service.collectActiveLocations(ProviderRunContext.scheduled());

        assertThat(outcome.status()).isEqualTo("DEGRADED");
        assertThat(outcome.successfulLocations()).isEqualTo(1);
        assertThat(outcome.failedLocations()).isEqualTo(1);
        verify(weatherProvider, times(2)).fetch(any());
        verify(evidenceWriter, times(2))
            .writeWeatherDetail(any(WeatherObservationSnapshot.class), any(ProviderCallRecord.class));
        assertAggregate("DEGRADED");
    }

    @Test
    void scheduledBatchContinuesAfterOneFailureAndWritesOneDegradedAggregate() {
        when(locationMapper.selectActiveOrdered()).thenReturn(List.of(location(3L, 1L), location(9L, 2L)));
        when(weatherProvider.fetch(any()))
            .thenThrow(new IllegalStateException("Open-Meteo unavailable"))
            .thenReturn(weatherResult());

        WeatherBatchOutcome outcome = service.collectActiveLocations(ProviderRunContext.scheduled());

        assertThat(outcome.status()).isEqualTo("DEGRADED");
        assertThat(outcome.successfulLocations()).isEqualTo(1);
        assertThat(outcome.failedLocations()).isEqualTo(1);
        ArgumentCaptor<WeatherProviderRequest> requestCaptor = ArgumentCaptor.forClass(WeatherProviderRequest.class);
        verify(weatherProvider, times(2)).fetch(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues()).extracting(WeatherProviderRequest::locationId)
            .containsExactly(3L, 9L);
        ArgumentCaptor<ProviderCallRecord> failureCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(evidenceWriter).writeCallOnly(failureCaptor.capture());
        assertThat(failureCaptor.getValue().status()).isEqualTo("FAILED");
        assertThat(failureCaptor.getValue().sourceType()).isEqualTo("SCHEDULED");
        assertThat(failureCaptor.getValue().updateRegistry()).isFalse();
        ArgumentCaptor<ProviderCallRecord> successDetailCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(evidenceWriter).writeWeatherDetail(any(WeatherObservationSnapshot.class), successDetailCaptor.capture());
        assertThat(successDetailCaptor.getValue().sourceType()).isEqualTo("SCHEDULED");
        assertThat(successDetailCaptor.getValue().updateRegistry()).isFalse();
        assertAggregate("DEGRADED");
    }

    @Test
    void scheduledBatchWithOnlyFailuresWritesOneFailedAggregate() {
        when(locationMapper.selectActiveOrdered()).thenReturn(List.of(location(3L, 1L), location(9L, 2L)));
        when(weatherProvider.fetch(any())).thenThrow(new IllegalStateException("Open-Meteo unavailable"));

        WeatherBatchOutcome outcome = service.collectActiveLocations(ProviderRunContext.scheduled());

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(outcome.successfulLocations()).isZero();
        assertThat(outcome.failedLocations()).isEqualTo(2);
        verify(evidenceWriter, times(2)).writeCallOnly(any(ProviderCallRecord.class));
        assertAggregate("FAILED");
    }

    @Test
    void scheduledBatchRecordsPerLocationDurationMsAsSingleCallElapsed() {
        // A26-P3-34：per-location duration_ms 必须是单次调用耗时，不再复用批次开始时间戳。
        // 第一场馆 fetch sleep 120ms，第二场馆 fetch 立即返回。
        // 修复前：第二场馆 durationMs 会包含第一场馆的 120ms 累计（>=120）；
        // 修复后：第二场馆 durationMs 接近 0（<60），明显小于第一场馆。
        when(locationMapper.selectActiveOrdered()).thenReturn(List.of(location(3L, 1L), location(9L, 2L)));
        when(weatherProvider.fetch(any())).thenAnswer(invocation -> {
            Long locationId = ((WeatherProviderRequest) invocation.getArgument(0)).locationId();
            if (locationId == 3L) {
                Thread.sleep(120);
            }
            return weatherResult();
        });

        service.collectActiveLocations(ProviderRunContext.scheduled());

        ArgumentCaptor<ProviderCallRecord> detailCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(evidenceWriter, times(2))
            .writeWeatherDetail(any(WeatherObservationSnapshot.class), detailCaptor.capture());
        long firstDuration = detailCaptor.getAllValues().get(0).durationMs();
        long secondDuration = detailCaptor.getAllValues().get(1).durationMs();
        assertThat(firstDuration).isGreaterThanOrEqualTo(100);
        assertThat(secondDuration).isLessThan(60);
        assertThat(secondDuration).isLessThan(firstDuration);
    }

    private void assertAggregate(String expectedStatus) {
        ArgumentCaptor<ProviderCallRecord> aggregateCaptor = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(evidenceWriter).writeRegistryOutcome(aggregateCaptor.capture());
        assertThat(aggregateCaptor.getValue().status()).isEqualTo(expectedStatus);
        assertThat(aggregateCaptor.getValue().sourceType()).isEqualTo("SCHEDULED");
        assertThat(aggregateCaptor.getValue().triggeredBy()).isNull();
        assertThat(aggregateCaptor.getValue().updateRegistry()).isTrue();
    }

    private VenueObservationLocation location(long locationId, long venueId) {
        VenueObservationLocation location = new VenueObservationLocation();
        location.id = locationId;
        location.venueId = venueId;
        location.latitude = BigDecimal.valueOf(31.2304160);
        location.longitude = BigDecimal.valueOf(121.4737010);
        location.status = "ACTIVE";
        location.deleted = 0;
        return location;
    }

    private WeatherProviderResult weatherResult() {
        return new WeatherProviderResult(
            BigDecimal.valueOf(28.6), BigDecimal.valueOf(1.2), BigDecimal.valueOf(8.4), "61", "NONE",
            "Light rain", BigDecimal.valueOf(0.91), LocalDateTime.of(2026, 7, 13, 12, 0),
            "{\"source\":\"open-meteo\"}"
        );
    }

    private ProviderRefreshResponse response(String status, long snapshotId) {
        ProviderRefreshResponse response = new ProviderRefreshResponse();
        response.status = status;
        response.providerKey = "weather.open_meteo.dev";
        response.snapshotId = snapshotId;
        return response;
    }
}
