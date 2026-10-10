package com.example.venue.provider;

import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRegistryRegistration;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.entity.ProviderCallLog;
import com.example.venue.provider.entity.ProviderRegistryEntry;
import com.example.venue.provider.mapper.ProviderCallLogMapper;
import com.example.venue.provider.mapper.ProviderRegistryEntryMapper;
import com.example.venue.provider.service.ProviderObservabilityService;
import com.example.venue.provider.service.ProviderRegistryStateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProviderObservabilityServiceTest {
    @Test
    void recordCallPersistsManualActorAndIdempotencyBeforeApplyingRegistryState() {
        ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        ProviderCallLogMapper callLogMapper = mock(ProviderCallLogMapper.class);
        ProviderRegistryStateService registryStateService = mock(ProviderRegistryStateService.class);
        ProviderObservabilityService service = new ProviderObservabilityService(
            registryMapper, callLogMapper, registryStateService);
        ProviderCallRecord record = record("SUCCESS", "MANUAL", 7L, true);

        service.recordCall(record);

        ArgumentCaptor<ProviderCallLog> logCaptor = ArgumentCaptor.forClass(ProviderCallLog.class);
        verify(callLogMapper).insert(logCaptor.capture());
        ProviderCallLog log = logCaptor.getValue();
        assertThat(log.providerKey).isEqualTo("weather.open_meteo.dev");
        assertThat(log.status).isEqualTo("SUCCESS");
        assertThat(log.idempotencyKey).isEqualTo("idem-success");
        assertThat(log.sourceType).isEqualTo("MANUAL");
        assertThat(log.triggeredBy).isEqualTo(7L);
        verify(registryStateService).apply(eq(record), any(LocalDateTime.class));
    }

    @Test
    void recordCallPersistsScheduledAndSystemSourcesWithoutActors() {
        ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        ProviderCallLogMapper callLogMapper = mock(ProviderCallLogMapper.class);
        ProviderRegistryStateService registryStateService = mock(ProviderRegistryStateService.class);
        ProviderObservabilityService service = new ProviderObservabilityService(
            registryMapper, callLogMapper, registryStateService);

        service.recordCall(record("SUCCESS", "SCHEDULED", null, true));
        service.recordCall(record("SUCCESS", "SYSTEM", null, true));

        ArgumentCaptor<ProviderCallLog> logCaptor = ArgumentCaptor.forClass(ProviderCallLog.class);
        verify(callLogMapper, org.mockito.Mockito.times(2)).insert(logCaptor.capture());
        assertThat(logCaptor.getAllValues())
            .extracting(log -> log.sourceType)
            .containsExactly("SCHEDULED", "SYSTEM");
        assertThat(logCaptor.getAllValues())
            .extracting(log -> log.triggeredBy)
            .containsOnlyNulls();
        verify(registryStateService, org.mockito.Mockito.times(2)).apply(any(), any(LocalDateTime.class));
    }

    @Test
    void evidenceOnlyRecordPersistsCallLogWithoutChangingRegistryState() {
        ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        ProviderCallLogMapper callLogMapper = mock(ProviderCallLogMapper.class);
        ProviderRegistryStateService registryStateService = mock(ProviderRegistryStateService.class);
        ProviderObservabilityService service = new ProviderObservabilityService(
            registryMapper, callLogMapper, registryStateService);

        service.recordCall(record("SKIPPED", "SCHEDULED", null, false));

        verify(callLogMapper).insert(any(ProviderCallLog.class));
        verifyNoInteractions(registryStateService);
    }

    @Test
    void recordRejectsInvalidSourceAndSkippedRegistryImpact() {
        assertThatThrownBy(() -> record("SUCCESS", "BATCH", 7L, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sourceType");
        assertThatThrownBy(() -> record("SUCCESS", "MANUAL", null, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("triggeredBy");
        assertThatThrownBy(() -> record("SUCCESS", "SCHEDULED", 7L, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("triggeredBy");
        assertThatThrownBy(() -> record("SUCCESS", "SYSTEM", 7L, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("triggeredBy");
        assertThatThrownBy(() -> record("SKIPPED", "SCHEDULED", null, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SKIPPED");
    }

    @Test
    void validSystemRecordNormalizesSourceAndKeepsNullActor() {
        ProviderCallRecord record = record("SUCCESS", " system ", null, true);

        assertThat(record.sourceType()).isEqualTo("SYSTEM");
        assertThat(record.triggeredBy()).isNull();
    }

    @Test
    void upsertRegistryCreatesDescriptorWithStatusDerivedFromEnabledFlag() {
        ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        ProviderCallLogMapper callLogMapper = mock(ProviderCallLogMapper.class);
        ProviderRegistryStateService registryStateService = mock(ProviderRegistryStateService.class);
        ProviderObservabilityService service = new ProviderObservabilityService(
            registryMapper, callLogMapper, registryStateService);
        when(registryMapper.selectByProviderKeyIncludingDeleted("payment.alipay.sandbox")).thenReturn(null);

        service.upsertRegistry(registration("payment.alipay.sandbox", false));

        ArgumentCaptor<ProviderRegistryEntry> entryCaptor = ArgumentCaptor.forClass(ProviderRegistryEntry.class);
        verify(registryMapper).upsertDescriptor(entryCaptor.capture());
        ProviderRegistryEntry inserted = entryCaptor.getValue();
        assertThat(inserted.providerKey).isEqualTo("payment.alipay.sandbox");
        assertThat(inserted.providerDomain).isEqualTo("PAYMENT");
        assertThat(inserted.providerEnv).isEqualTo("SANDBOX");
        assertThat(inserted.enabled).isZero();
        assertThat(inserted.status).isEqualTo("DISABLED");
        assertThat(inserted.deleted).isZero();
        assertThat(inserted.consecutiveFailures).isZero();
    }

    @Test
    void upsertRegistryReactivatesDeletedDescriptorWithoutDiscardingCallHistory() {
        ProviderRegistryEntryMapper registryMapper = mock(ProviderRegistryEntryMapper.class);
        ProviderCallLogMapper callLogMapper = mock(ProviderCallLogMapper.class);
        ProviderRegistryStateService registryStateService = mock(ProviderRegistryStateService.class);
        ProviderObservabilityService service = new ProviderObservabilityService(
            registryMapper, callLogMapper, registryStateService);
        ProviderRegistryEntry existing = new ProviderRegistryEntry();
        existing.id = 9L;
        existing.providerKey = "payment.alipay.sandbox";
        existing.status = "FAILED";
        existing.lastSuccessAt = LocalDateTime.of(2026, 7, 12, 9, 0);
        existing.lastFailureAt = LocalDateTime.of(2026, 7, 12, 10, 0);
        existing.lastFailureCode = "OLD_FAILURE";
        existing.lastFailureMessage = "old message";
        existing.consecutiveFailures = 4;
        existing.deleted = 1;
        when(registryMapper.selectByProviderKeyIncludingDeleted("payment.alipay.sandbox")).thenReturn(existing);
        when(registryMapper.selectByProviderKeyIncludingDeletedForUpdate("payment.alipay.sandbox")).thenReturn(existing);
        when(registryMapper.restoreDeletedDescriptor(any(), eq("payment.alipay.sandbox"))).thenReturn(1);

        service.upsertRegistry(registration("payment.alipay.sandbox", true));

        ArgumentCaptor<ProviderRegistryEntry> entryCaptor = ArgumentCaptor.forClass(ProviderRegistryEntry.class);
        verify(registryMapper).restoreDeletedDescriptor(entryCaptor.capture(), eq("payment.alipay.sandbox"));
        ProviderRegistryEntry updated = entryCaptor.getValue();
        assertThat(updated.providerDomain).isEqualTo("PAYMENT");
        assertThat(updated.providerEnv).isEqualTo("SANDBOX");
        assertThat(updated.displayName).isEqualTo("支付宝沙箱");
        assertThat(updated.enabled).isOne();
        assertThat(updated.deleted).isZero();
        assertThat(updated.status).isEqualTo("ACTIVE");
        assertThat(updated.lastSuccessAt).isNull();
        assertThat(updated.lastFailureAt).isNull();
        assertThat(updated.lastFailureCode).isNull();
        assertThat(updated.lastFailureMessage).isNull();
        assertThat(updated.consecutiveFailures).isNull();
    }

    @Test
    void providerCallRecordRejectsUnsupportedStatusAtPublicConstructorBoundary() {
        assertThatThrownBy(() -> record("BOGUS", "MANUAL", 7L, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Provider call status");
    }

    private ProviderCallRecord record(String status, String sourceType, Long triggeredBy, boolean updateRegistry) {
        return ProviderCallRecord.of(
            new ProviderRunContext(sourceType, triggeredBy),
            "weather.open_meteo.dev", "WEATHER", "DEV", "OPEN_METEO", "idem-" + status.toLowerCase(),
            "FETCH_WEATHER", status, "OPEN_METEO_V1", "{\"venueId\":1}", "{\"temperatureC\":22.5}",
            "DEGRADED".equals(status) ? "PARTIAL" : null,
            "DEGRADED".equals(status) ? "partial evidence" : null,
            42L, "WX-1", "WEATHER", 10L, BigDecimal.valueOf(0.90), updateRegistry);
    }

    private ProviderRegistryRegistration registration(String providerKey, boolean enabled) {
        return new ProviderRegistryRegistration(
            providerKey, "PAYMENT", "SANDBOX", "ALIPAY", "支付宝沙箱", "ALIPAY_SANDBOX_V1", enabled,
            "{\"sandbox\":true}");
    }
}
