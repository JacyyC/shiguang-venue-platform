package com.example.venue.provider;

import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.entity.ProviderRegistryEntry;
import com.example.venue.provider.mapper.ProviderRegistryEntryMapper;
import com.example.venue.provider.service.ProviderRegistryStateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProviderRegistryStateServiceTest {
    private static final String PROVIDER_KEY = "weather.open_meteo.dev";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 13, 11, 30);

    @Test
    void successfulOutcomePreservesHistoricalFailureFieldsAndResetsOnlyCounter() {
        ProviderRegistryEntryMapper mapper = mock(ProviderRegistryEntryMapper.class);
        ProviderRegistryStateService service = new ProviderRegistryStateService(mapper);
        ProviderRegistryEntry current = registry("FAILED", 1, 3, "OLD_FAILURE", "old message");
        when(mapper.selectByProviderKeyForUpdate(PROVIDER_KEY)).thenReturn(current);

        service.apply(record("SUCCESS", ProviderRunContext.scheduled(), true), NOW);

        ProviderRegistryEntry updated = updated(mapper);
        assertThat(updated.status).isEqualTo("ACTIVE");
        assertThat(updated.lastSuccessAt).isEqualTo(NOW);
        assertThat(updated.consecutiveFailures).isZero();
        assertThat(updated.lastFailureAt).isEqualTo(current.lastFailureAt);
        assertThat(updated.lastFailureCode).isEqualTo("OLD_FAILURE");
        assertThat(updated.lastFailureMessage).isEqualTo("old message");
        verify(mapper).selectByProviderKeyForUpdate(PROVIDER_KEY);
    }

    @Test
    void partialEvidenceDegradedMarksLatestSuccessAndFailureWithoutIncreasingCounter() {
        ProviderRegistryEntryMapper mapper = mock(ProviderRegistryEntryMapper.class);
        ProviderRegistryStateService service = new ProviderRegistryStateService(mapper);
        ProviderRegistryEntry current = registry("ACTIVE", 1, 2, "OLD_FAILURE", "old message");
        when(mapper.selectByProviderKeyForUpdate(PROVIDER_KEY)).thenReturn(current);

        service.apply(record("DEGRADED", ProviderRunContext.scheduled(), true), NOW);

        ProviderRegistryEntry updated = updated(mapper);
        assertThat(updated.status).isEqualTo("DEGRADED");
        assertThat(updated.lastSuccessAt).isEqualTo(NOW);
        assertThat(updated.lastFailureAt).isEqualTo(NOW);
        assertThat(updated.lastFailureCode).isEqualTo("PARTIAL_EVIDENCE");
        assertThat(updated.lastFailureMessage).isEqualTo("partial evidence");
        assertThat(updated.consecutiveFailures).isZero();
    }

    @Test
    void failedOutcomeTransitionsToDegradedBelowThresholdAndIncrementsOnce() {
        ProviderRegistryEntryMapper mapper = mock(ProviderRegistryEntryMapper.class);
        ProviderRegistryStateService service = new ProviderRegistryStateService(mapper);
        ProviderRegistryEntry current = registry("ACTIVE", 1, 3, null, null);
        when(mapper.selectByProviderKeyForUpdate(PROVIDER_KEY)).thenReturn(current);

        service.apply(record("FAILED", ProviderRunContext.scheduled(), true), NOW);

        ProviderRegistryEntry updated = updated(mapper);
        assertThat(updated.status).isEqualTo("DEGRADED");
        assertThat(updated.lastSuccessAt).isEqualTo(current.lastSuccessAt);
        assertThat(updated.lastFailureAt).isEqualTo(NOW);
        assertThat(updated.lastFailureCode).isEqualTo("FAILED_CODE");
        assertThat(updated.consecutiveFailures).isEqualTo(2);
    }

    @Test
    void timeoutOutcomeTransitionsToFailedAtThresholdAndIncrementsOnce() {
        ProviderRegistryEntryMapper mapper = mock(ProviderRegistryEntryMapper.class);
        ProviderRegistryStateService service = new ProviderRegistryStateService(mapper);
        ProviderRegistryEntry current = registry("DEGRADED", 2, 3, "OLD_FAILURE", "old message");
        when(mapper.selectByProviderKeyForUpdate(PROVIDER_KEY)).thenReturn(current);

        service.apply(record("TIMEOUT", ProviderRunContext.scheduled(), true), NOW);

        ProviderRegistryEntry updated = updated(mapper);
        assertThat(updated.status).isEqualTo("FAILED");
        assertThat(updated.lastFailureAt).isEqualTo(NOW);
        assertThat(updated.lastFailureCode).isEqualTo("TIMEOUT_CODE");
        assertThat(updated.consecutiveFailures).isEqualTo(3);
    }

    @Test
    void everySkippedSourceLeavesRegistryStateUntouched() {
        ProviderRegistryEntryMapper mapper = mock(ProviderRegistryEntryMapper.class);
        ProviderRegistryStateService service = new ProviderRegistryStateService(mapper);

        service.apply(record("SKIPPED", ProviderRunContext.manual(7L), false), NOW);
        service.apply(record("SKIPPED", ProviderRunContext.scheduled(), false), NOW);
        service.apply(record("SKIPPED", ProviderRunContext.system(), false), NOW);

        verifyNoInteractions(mapper);
    }

    @Test
    void disabledRegistryRemainsDisabledWithoutChangingEvidenceHistory() {
        ProviderRegistryEntryMapper mapper = mock(ProviderRegistryEntryMapper.class);
        ProviderRegistryStateService service = new ProviderRegistryStateService(mapper);
        ProviderRegistryEntry current = registry("ACTIVE", 4, 3, "OLD_FAILURE", "old message");
        current.enabled = 0;
        when(mapper.selectByProviderKeyForUpdate(PROVIDER_KEY)).thenReturn(current);

        service.apply(record("SUCCESS", ProviderRunContext.scheduled(), true), NOW);

        ProviderRegistryEntry updated = updated(mapper);
        assertThat(updated.status).isEqualTo("DISABLED");
        assertThat(updated.lastSuccessAt).isEqualTo(current.lastSuccessAt);
        assertThat(updated.lastFailureAt).isEqualTo(current.lastFailureAt);
        assertThat(updated.lastFailureCode).isEqualTo("OLD_FAILURE");
        assertThat(updated.lastFailureMessage).isEqualTo("old message");
        assertThat(updated.consecutiveFailures).isEqualTo(4);
    }

    @Test
    void stateImpactFailsDeterministicallyWhenNoActiveRegistryRowCanBeLocked() {
        ProviderRegistryEntryMapper mapper = mock(ProviderRegistryEntryMapper.class);
        ProviderRegistryStateService service = new ProviderRegistryStateService(mapper);
        when(mapper.selectByProviderKeyForUpdate(PROVIDER_KEY)).thenReturn(null);

        assertThatThrownBy(() -> service.apply(record("SUCCESS", ProviderRunContext.scheduled(), true), NOW))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(PROVIDER_KEY);

        verify(mapper).selectByProviderKeyForUpdate(PROVIDER_KEY);
        verify(mapper, never()).update(any(), any());
    }

    private ProviderRegistryEntry updated(ProviderRegistryEntryMapper mapper) {
        ArgumentCaptor<ProviderRegistryEntry> entryCaptor = ArgumentCaptor.forClass(ProviderRegistryEntry.class);
        verify(mapper).update(entryCaptor.capture(), any());
        return entryCaptor.getValue();
    }

    private ProviderCallRecord record(String status, ProviderRunContext context, boolean updateRegistry) {
        String code = switch (status) {
            case "DEGRADED" -> "PARTIAL_EVIDENCE";
            case "FAILED" -> "FAILED_CODE";
            case "TIMEOUT" -> "TIMEOUT_CODE";
            default -> null;
        };
        String message = code == null ? null : "partial evidence";
        return ProviderCallRecord.of(
            context, PROVIDER_KEY, "WEATHER", "DEV", "OPEN_METEO", "idem-" + status,
            "FETCH_WEATHER", status, "OPEN_METEO_V1", "{\"venueId\":1}", "{\"ok\":true}",
            code, message, 20L, null, "WEATHER", 1L, BigDecimal.valueOf(0.9), updateRegistry);
    }

    private ProviderRegistryEntry registry(String status,
                                           int consecutiveFailures,
                                           int failureThreshold,
                                           String failureCode,
                                           String failureMessage) {
        ProviderRegistryEntry entry = new ProviderRegistryEntry();
        entry.id = 1L;
        entry.providerKey = PROVIDER_KEY;
        entry.enabled = 1;
        entry.status = status;
        entry.failureThreshold = failureThreshold;
        entry.consecutiveFailures = consecutiveFailures;
        entry.lastSuccessAt = NOW.minusHours(2);
        entry.lastFailureAt = NOW.minusHours(1);
        entry.lastFailureCode = failureCode;
        entry.lastFailureMessage = failureMessage;
        entry.deleted = 0;
        return entry;
    }
}
