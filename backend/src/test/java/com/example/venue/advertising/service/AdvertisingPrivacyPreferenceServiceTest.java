package com.example.venue.advertising.service;

import com.example.venue.advertising.dto.AdvertisingDecisionRequests;
import com.example.venue.advertising.dto.AdvertisingDecisionResponses;
import com.example.venue.advertising.entity.AdvertisingPrivacyPreference;
import com.example.venue.advertising.mapper.AdvertisingPrivacyPreferenceMapper;
import com.example.venue.common.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdvertisingPrivacyPreferenceServiceTest {
    @Mock
    private AdvertisingPrivacyPreferenceMapper preferenceMapper;

    private AdvertisingPrivacyPreferenceService service;

    @BeforeEach
    void setUp() {
        service = new AdvertisingPrivacyPreferenceService(preferenceMapper);
        authenticate(71L);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingRowDefaultsBothCommercialAndMeasurementToClosed() {
        when(preferenceMapper.findByUserId(71L)).thenReturn(null);

        AdvertisingDecisionResponses.PrivacyPreferenceResponse response = service.current();

        assertThat(response.commercialEnabled()).isFalse();
        assertThat(response.measurementEnabled()).isFalse();
        assertThat(response.policyVersion()).isEqualTo(AdvertisingPrivacyPreferenceService.POLICY_VERSION);
        verify(preferenceMapper, never()).insert(any(AdvertisingPrivacyPreference.class));
    }

    @Test
    void updatePersistsOnlyTheAuthenticatedUserAndPolicyVersion() {
        when(preferenceMapper.findByUserIdForUpdate(71L)).thenReturn(null);

        AdvertisingDecisionResponses.PrivacyPreferenceResponse response = service.update(
            new AdvertisingDecisionRequests.UpdatePrivacyRequest(true, true));

        ArgumentCaptor<AdvertisingPrivacyPreference> captor =
            ArgumentCaptor.forClass(AdvertisingPrivacyPreference.class);
        verify(preferenceMapper).insert(captor.capture());
        AdvertisingPrivacyPreference saved = captor.getValue();
        assertThat(saved.userId).isEqualTo(71L);
        assertThat(saved.commercialEnabled).isEqualTo(1);
        assertThat(saved.measurementEnabled).isEqualTo(1);
        assertThat(saved.policyVersion).isEqualTo(AdvertisingPrivacyPreferenceService.POLICY_VERSION);
        assertThat(response.commercialEnabled()).isTrue();
        assertThat(response.measurementEnabled()).isTrue();
    }

    @Test
    void disablingOneSwitchImmediatelyClosesCommercialMeasurementEligibility() {
        AdvertisingPrivacyPreference preference = preference(1, 0);
        when(preferenceMapper.findByUserIdForUpdate(71L)).thenReturn(preference);

        AdvertisingDecisionResponses.PrivacyPreferenceResponse response = service.update(
            new AdvertisingDecisionRequests.UpdatePrivacyRequest(false, true));

        verify(preferenceMapper).updateById(preference);
        assertThat(preference.commercialEnabled).isZero();
        assertThat(preference.measurementEnabled).isEqualTo(1);
        assertThat(response.commercialEnabled()).isFalse();
        assertThat(service.isCommercialMeasurementEnabled(71L)).isFalse();
    }

    @Test
    void transactionalEmailPreferenceIsNotReadOrWrittenByThisService() {
        when(preferenceMapper.findByUserId(71L)).thenReturn(null);

        service.current();

        // The constructor exposes only the advertising preference mapper; no notice email contract is reachable.
        verify(preferenceMapper).findByUserId(71L);
    }

    private AdvertisingPrivacyPreference preference(long id, int commercialEnabled) {
        AdvertisingPrivacyPreference preference = new AdvertisingPrivacyPreference();
        preference.id = id;
        preference.userId = 71L;
        preference.commercialEnabled = commercialEnabled;
        preference.measurementEnabled = 1;
        preference.policyVersion = AdvertisingPrivacyPreferenceService.POLICY_VERSION;
        return preference;
    }

    private void authenticate(Long userId) {
        CurrentUser principal = new CurrentUser(userId, "user-" + userId, Set.of("USER"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
