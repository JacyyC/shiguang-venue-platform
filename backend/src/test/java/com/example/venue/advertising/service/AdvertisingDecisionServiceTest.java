package com.example.venue.advertising.service;

import com.example.venue.advertising.dto.AdvertisingDecisionCandidate;
import com.example.venue.advertising.entity.AdvertisingDecision;
import com.example.venue.advertising.entity.AdvertisingDecisionGuard;
import com.example.venue.advertising.mapper.AdvertisingDecisionGuardMapper;
import com.example.venue.advertising.mapper.AdvertisingDecisionMapper;
import com.example.venue.advertising.mapper.AdvertisingEventMapper;
import com.example.venue.common.CurrentUser;
import com.example.venue.placement.contract.OwnedPlacementDecisionReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdvertisingDecisionServiceTest {
    @Mock private AdvertisingDecisionMapper decisionMapper;
    @Mock private AdvertisingDecisionGuardMapper guardMapper;
    @Mock private AdvertisingEventMapper eventMapper;
    @Mock private AdvertisingPrivacyPreferenceService privacyService;
    @Mock private AdvertisingTokenService tokenService;
    @Mock private OwnedPlacementDecisionReader ownedPlacementReader;

    private AdvertisingDecisionService service;

    @BeforeEach
    void setUp() {
        service = new AdvertisingDecisionService(
            decisionMapper, guardMapper, eventMapper, privacyService,
            tokenService, ownedPlacementReader, new ObjectMapper());
        authenticate(81L);
        lenient().when(privacyService.isCommercialMeasurementEnabled(81L)).thenReturn(true);
        lenient().when(privacyService.lockForAdvertisingFact(81L)).thenReturn(true);
        lenient().when(ownedPlacementReader.readHomePrimary())
            .thenReturn(OwnedPlacementDecisionReader.ArbitrationResult.empty());
        lenient().when(guardMapper.lockScope(anyLong(), eq(AdvertisingDecisionService.HOME_COMMERCIAL_CODE), any()))
            .thenReturn(new AdvertisingDecisionGuard());
        lenient().when(decisionMapper.insert(any(AdvertisingDecision.class))).thenReturn(1);
        lenient().when(decisionMapper.rotateIssuedTokenHash(anyLong(), any(), any())).thenReturn(1);
        lenient().when(tokenService.generate()).thenReturn(new AdvertisingTokenService.GeneratedToken(
            "advertising-raw-token", "advertising-token-hash"));
        lenient().when(tokenService.earliestExpiry(any(), any())).thenReturn(LocalDateTime.now().plusMinutes(10));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void privacyClosedDoesNotCallR6OrReadCommercialCandidates() {
        when(privacyService.isCommercialMeasurementEnabled(81L)).thenReturn(false);

        assertThat(service.decideHomeCommercial()).isNull();

        verify(ownedPlacementReader, never()).readHomePrimary();
        verify(decisionMapper, never()).findPublishedCandidates(any(), anyInt());
    }

    @Test
    void onlyExplicitR6EmptyAllowsCommercialFallback() {
        when(ownedPlacementReader.readHomePrimary())
            .thenReturn(OwnedPlacementDecisionReader.ArbitrationResult.available());

        assertThat(service.decideHomeCommercial()).isNull();

        verify(decisionMapper, never()).findPublishedCandidates(any(), anyInt());
    }

    @Test
    void R6UnavailableAlsoClosesCommercialFallback() {
        when(ownedPlacementReader.readHomePrimary())
            .thenReturn(OwnedPlacementDecisionReader.ArbitrationResult.unavailable());

        assertThat(service.decideHomeCommercial()).isNull();

        verify(decisionMapper, never()).findPublishedCandidates(any(), anyInt());
    }

    @Test
    void issuesIndependentDecisionWithFixedLabelTargetAndHashedToken() {
        AdvertisingDecisionCandidate candidate = candidate(301L, "VENUE_DETAIL", 9001L);
        when(decisionMapper.findPublishedCandidates(any(), eq(100))).thenReturn(List.of(candidate));
        when(decisionMapper.findPublishedCandidateForUpdate(eq(301L), any())).thenReturn(candidate);
        when(eventMapper.countAcceptedViewableByUserSlot(anyLong(), any(), any())).thenReturn(0);
        when(eventMapper.countAcceptedViewableByUserCreative(anyLong(), anyLong(), any())).thenReturn(0);

        var result = service.decideHomeCommercial();

        assertThat(result).isNotNull();
        assertThat(result.adLabel()).isEqualTo("广告");
        assertThat(result.accessibleAdLabel()).isEqualTo("广告内容");
        assertThat(result.deepLink()).isEqualTo("/venues/9001");
        assertThat(result.token()).isEqualTo("advertising-raw-token");
        ArgumentCaptor<AdvertisingDecision> captor = ArgumentCaptor.forClass(AdvertisingDecision.class);
        verify(decisionMapper).insert(captor.capture());
        AdvertisingDecision saved = captor.getValue();
        assertThat(saved.tokenHash).isEqualTo("advertising-token-hash");
        assertThat(saved.creativeSnapshotJson).doesNotContain("advertising-raw-token");
        assertThat(saved.slotCode).isEqualTo("HOME_COMMERCIAL");
        verify(guardMapper).findScope(81L, "HOME_COMMERCIAL", "SLOT");
        verify(guardMapper).findScope(81L, "HOME_COMMERCIAL", "CREATIVE:301");
        verify(guardMapper).insertScope(81L, "HOME_COMMERCIAL", "SLOT");
        verify(guardMapper).insertScope(81L, "HOME_COMMERCIAL", "CREATIVE:301");
    }

    @Test
    void reusesIssuedDecisionWithoutIssuingAnotherTokenOrCountingAsExposure() {
        AdvertisingDecisionCandidate candidate = candidate(301L, "VENUE_LIST", null);
        AdvertisingDecision existing = new AdvertisingDecision();
        existing.id = 701L;
        existing.userId = 81L;
        existing.slotCode = "HOME_COMMERCIAL";
        existing.creativeVersionId = 301L;
        existing.policyVersion = AdvertisingPrivacyPreferenceService.POLICY_VERSION;
        existing.expiresAt = LocalDateTime.now().plusMinutes(5);
        existing.status = "ISSUED";
        existing.creativeSnapshotJson = "{\"headline\":\"标题\",\"bodyText\":\"正文\",\"callToAction\":\"VIEW\",\"targetType\":\"VENUE_LIST\",\"deepLink\":\"/venues\"}";
        when(decisionMapper.findPublishedCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(decisionMapper.findPublishedCandidateForUpdate(eq(301L), any())).thenReturn(candidate);
        when(decisionMapper.findReusableIssuedForUpdate(anyLong(), any(), anyLong(), any())).thenReturn(existing);

        var result = service.decideHomeCommercial();

        assertThat(result).isNotNull();
        assertThat(result.reused()).isTrue();
        assertThat(result.token()).isEqualTo("advertising-raw-token");
        verify(tokenService).generate();
        verify(decisionMapper).rotateIssuedTokenHash(eq(701L), eq("advertising-token-hash"), any());
        verify(decisionMapper, never()).insert(any(AdvertisingDecision.class));
        verify(eventMapper, never()).countAcceptedViewableByUserSlot(anyLong(), any(), any());
    }

    @Test
    void MySqlSlotCapIsAuthoritativeAndPreventsNewDecision() {
        AdvertisingDecisionCandidate candidate = candidate(301L, "ACTIVITY_DETAIL", 902L);
        when(decisionMapper.findPublishedCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(decisionMapper.findPublishedCandidateForUpdate(eq(301L), any())).thenReturn(candidate);
        when(decisionMapper.findReusableIssuedForUpdate(anyLong(), any(), anyLong(), any())).thenReturn(null);
        when(eventMapper.countAcceptedViewableByUserSlot(anyLong(), any(), any())).thenReturn(3);

        assertThat(service.decideHomeCommercial()).isNull();

        verify(eventMapper, never()).countAcceptedViewableByUserCreative(anyLong(), anyLong(), any());
        verify(decisionMapper, never()).insert(any(AdvertisingDecision.class));
    }

    @Test
    void invalidTargetCandidateIsSkippedWithoutWritingDecision() {
        AdvertisingDecisionCandidate candidate = candidate(301L, "EXTERNAL_URL", 9L);
        when(decisionMapper.findPublishedCandidates(any(), anyInt())).thenReturn(List.of(candidate));

        assertThat(service.decideHomeCommercial()).isNull();

        verify(decisionMapper, never()).findReusableIssuedForUpdate(anyLong(), any(), anyLong(), any());
        verify(decisionMapper, never()).insert(any(AdvertisingDecision.class));
    }

    @Test
    void databaseFrequencyFailureIsVisibleAndDoesNotFallbackToRedisOrWriteDecision() {
        AdvertisingDecisionCandidate candidate = candidate(301L, "VENUE_LIST", null);
        when(decisionMapper.findPublishedCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(decisionMapper.findPublishedCandidateForUpdate(eq(301L), any())).thenReturn(candidate);
        when(decisionMapper.findReusableIssuedForUpdate(anyLong(), any(), anyLong(), any())).thenReturn(null);
        when(eventMapper.countAcceptedViewableByUserSlot(anyLong(), any(), any()))
            .thenThrow(new RuntimeException("mysql unavailable"));

        assertThatThrownBy(() -> service.decideHomeCommercial())
            .hasMessage("mysql unavailable");
        verify(decisionMapper, never()).insert(any(AdvertisingDecision.class));
    }

    @Test
    void finalR6RecheckFailsClosedWhenOwnedPlacementAppearsAfterInitialEmpty() {
        AdvertisingDecisionCandidate candidate = candidate(301L, "VENUE_LIST", null);
        when(decisionMapper.findPublishedCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(decisionMapper.findPublishedCandidateForUpdate(eq(301L), any())).thenReturn(candidate);
        when(ownedPlacementReader.readHomePrimary())
            .thenReturn(OwnedPlacementDecisionReader.ArbitrationResult.empty(),
                OwnedPlacementDecisionReader.ArbitrationResult.available());

        assertThat(service.decideHomeCommercial()).isNull();

        verify(decisionMapper, never()).insert(any(AdvertisingDecision.class));
    }

    @Test
    void finalCandidateRecheckFailsClosedAfterCreativeIsPaused() {
        AdvertisingDecisionCandidate candidate = candidate(301L, "VENUE_LIST", null);
        when(decisionMapper.findPublishedCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(decisionMapper.findPublishedCandidateForUpdate(eq(301L), any())).thenReturn(null);

        assertThat(service.decideHomeCommercial()).isNull();

        verify(decisionMapper, never()).insert(any(AdvertisingDecision.class));
    }

    private AdvertisingDecisionCandidate candidate(long creativeId, String targetType, Long targetId) {
        AdvertisingDecisionCandidate candidate = new AdvertisingDecisionCandidate();
        candidate.advertiserId = 11L;
        candidate.campaignId = 21L;
        candidate.campaignNo = "CAMPAIGN-21";
        candidate.campaignEffectiveFrom = LocalDateTime.now().minusMinutes(2);
        candidate.campaignEffectiveUntil = LocalDateTime.now().plusHours(1);
        candidate.creativeVersionId = creativeId;
        candidate.creativeNo = "CREATIVE-" + creativeId;
        candidate.versionNo = 1;
        candidate.headline = "商业标题";
        candidate.bodyText = "商业正文";
        candidate.callToAction = "VIEW";
        candidate.targetType = targetType;
        candidate.targetId = targetId;
        return candidate;
    }

    private void authenticate(Long userId) {
        CurrentUser principal = new CurrentUser(userId, "user-" + userId, Set.of("USER"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
