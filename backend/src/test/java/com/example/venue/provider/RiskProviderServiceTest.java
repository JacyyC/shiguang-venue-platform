package com.example.venue.provider;

import com.example.venue.common.CurrentUser;
import com.example.venue.provider.dto.ProviderRefreshResponse;
import com.example.venue.provider.dto.ProviderRunContext;
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
import com.example.venue.venue.mapper.VenueMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RiskProviderServiceTest {
    private final WeatherObservationCollectionService weatherCollectionService =
        mock(WeatherObservationCollectionService.class);
    private final RiskObservationCollectionService riskCollectionService = mock(RiskObservationCollectionService.class);
    private final RuntimeMetricsCollectionService metricsCollectionService = mock(RuntimeMetricsCollectionService.class);
    private final ProviderAdminService service = new ProviderAdminService(
        mock(ProviderRegistryEntryMapper.class),
        mock(ProviderCallLogMapper.class),
        mock(VenueObservationLocationMapper.class),
        mock(VenueMapper.class),
        mock(WeatherObservationSnapshotMapper.class),
        mock(RiskAssessmentSnapshotMapper.class),
        mock(OpsMetricSnapshotMapper.class),
        mock(ProviderObservabilityService.class),
        weatherCollectionService,
        riskCollectionService,
        metricsCollectionService,
        Clock.systemUTC());

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
    void refreshRiskDelegatesVenueScopeWithTheManualUserContext() {
        ProviderRefreshResponse expected = response("SUCCESS", 501L);
        when(riskCollectionService.collect(any(Long.class), any(ProviderRunContext.class))).thenReturn(expected);

        ProviderRefreshResponse actual = service.refreshRisk(7L);

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<ProviderRunContext> contextCaptor = ArgumentCaptor.forClass(ProviderRunContext.class);
        verify(riskCollectionService).collect(org.mockito.ArgumentMatchers.eq(7L), contextCaptor.capture());
        assertThat(contextCaptor.getValue()).isEqualTo(ProviderRunContext.manual(7L));
        verifyNoInteractions(weatherCollectionService, metricsCollectionService);
    }

    @Test
    void refreshRiskPreservesTheOptionalSystemScope() {
        ProviderRefreshResponse expected = response("SUCCESS", 502L);
        when(riskCollectionService.collect(isNull(), any(ProviderRunContext.class))).thenReturn(expected);

        ProviderRefreshResponse actual = service.refreshRisk(null);

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<ProviderRunContext> contextCaptor = ArgumentCaptor.forClass(ProviderRunContext.class);
        verify(riskCollectionService).collect(isNull(), contextCaptor.capture());
        assertThat(contextCaptor.getValue()).isEqualTo(ProviderRunContext.manual(7L));
        verifyNoInteractions(weatherCollectionService, metricsCollectionService);
    }

    private ProviderRefreshResponse response(String status, Long snapshotId) {
        ProviderRefreshResponse response = new ProviderRefreshResponse();
        response.status = status;
        response.providerKey = "risk.internal_rules.dev";
        response.snapshotId = snapshotId;
        return response;
    }
}
