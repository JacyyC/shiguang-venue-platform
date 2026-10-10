package com.example.venue.advertising.service;

import com.example.venue.advertising.config.AdvertisingRetentionProperties;
import com.example.venue.advertising.mapper.AdvertisingRetentionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdvertisingRetentionServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 3, 12, 0);

    private AdvertisingRetentionProperties properties;
    private AdvertisingRetentionMapper mapper;
    private PlatformTransactionManager transactionManager;
    private Clock clock;

    @BeforeEach
    void setUp() {
        properties = new AdvertisingRetentionProperties();
        properties.setBatchSize(2);
        properties.setMaxBatchesPerRun(2);
        mapper = org.mockito.Mockito.mock(AdvertisingRetentionMapper.class);
        transactionManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        clock = Clock.fixed(Instant.parse("2026-08-03T04:00:00Z"), ZONE);
    }

    @Test
    void usesPolicyCutoffsAndProcessesEachFactInIndependentBatches() {
        LocalDateTime rawCutoff = NOW.minusDays(90);
        LocalDateTime attributionCutoff = NOW.minusDays(180);
        LocalDateTime auditCutoff = NOW.minusDays(730);
        when(mapper.selectExpiredEventIds(rawCutoff, 2)).thenReturn(List.of(11L), List.of());
        when(mapper.selectExpiredDecisionIds(rawCutoff, 2)).thenReturn(List.of(21L), List.of());
        when(mapper.selectExpiredProcessedCallbackIds(attributionCutoff, 2)).thenReturn(List.of(31L), List.of());
        when(mapper.selectExpiredAttributionIds(attributionCutoff, 2)).thenReturn(List.of(41L), List.of());
        when(mapper.selectExpiredAuditIds(auditCutoff, 2)).thenReturn(List.of(51L), List.of());

        service().runOnce();

        verify(mapper).deleteEventIds(List.of(11L));
        verify(mapper).deleteDecisionIds(List.of(21L));
        verify(mapper).deleteCallbackIds(List.of(31L));
        verify(mapper).deleteAttributionIds(List.of(41L));
        verify(mapper).deleteAuditIds(List.of(51L));
        assertThat(AdvertisingRetentionService.RAW_FACT_RETENTION_DAYS).isEqualTo(90);
        assertThat(AdvertisingRetentionService.ATTRIBUTION_RETENTION_DAYS).isEqualTo(180);
        assertThat(AdvertisingRetentionService.AUDIT_RETENTION_DAYS).isEqualTo(730);
    }

    @Test
    void respectsTheConfiguredBatchLimit() {
        properties.setMaxBatchesPerRun(1);
        LocalDateTime rawCutoff = NOW.minusDays(90);
        when(mapper.selectExpiredEventIds(rawCutoff, 2)).thenReturn(List.of(1L, 2L));

        service().runOnce();

        verify(mapper).deleteEventIds(List.of(1L, 2L));
        verify(mapper).selectExpiredDecisionIds(NOW.minusDays(90), 2);
    }

    @Test
    void rollsBackTheFailedBatchAndStopsLaterFactTypes() {
        LocalDateTime rawCutoff = NOW.minusDays(90);
        when(mapper.selectExpiredEventIds(rawCutoff, 2)).thenReturn(List.of(1L));
        doThrow(new IllegalStateException("retention failure")).when(mapper).deleteEventIds(List.of(1L));

        service().runOnce();

        verify(transactionManager).rollback(any());
        verify(mapper, never()).selectExpiredDecisionIds(any(), any(Integer.class));
    }

    private AdvertisingRetentionService service() {
        return new AdvertisingRetentionService(properties, mapper, transactionManager, clock);
    }
}
