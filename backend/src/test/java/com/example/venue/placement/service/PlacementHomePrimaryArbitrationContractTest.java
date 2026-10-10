package com.example.venue.placement.service;

import com.example.venue.common.CurrentUser;
import com.example.venue.placement.entity.PlacementCandidateVersion;
import com.example.venue.placement.entity.PlacementSlot;
import com.example.venue.placement.mapper.PlacementCandidateVersionMapper;
import com.example.venue.placement.mapper.PlacementDecisionMapper;
import com.example.venue.placement.mapper.PlacementEventMapper;
import com.example.venue.placement.mapper.PlacementSlotMapper;
import com.example.venue.placement.profile.PlacementMemberProfileReader;
import com.example.venue.placement.source.PlacementSourceReader;
import com.example.venue.placement.source.PlacementSourceReaderRegistry;
import com.example.venue.placement.source.PlacementSourceSnapshot;
import com.example.venue.placement.source.PlacementSourceType;
import com.example.venue.placement.contract.OwnedPlacementDecisionReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlacementHomePrimaryArbitrationContractTest {
    @Mock private PlacementSlotMapper slotMapper;
    @Mock private PlacementCandidateVersionMapper candidateMapper;
    @Mock private PlacementDecisionMapper decisionMapper;
    @Mock private PlacementEventMapper eventMapper;
    @Mock private PlacementMemberProfileReader memberProfileReader;
    @Mock private PlacementTokenService tokenService;
    @Mock private PlacementFrequencyGuard frequencyGuard;
    @Mock private PlacementSourceReader reader;

    private PlacementDecisionService service;

    @BeforeEach
    void setUp() {
        when(reader.sourceType()).thenReturn(PlacementSourceType.GLOBAL_NOTICE);
        service = new PlacementDecisionService(
            slotMapper, candidateMapper, decisionMapper, eventMapper,
            new PlacementSourceReaderRegistry(List.of(reader)), memberProfileReader,
            tokenService, frequencyGuard, new ObjectMapper());
        authenticate(91L);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noPublishedR6CandidateIsAnExplicitEmptyResult() {
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(enabledSlot());
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), eq(100))).thenReturn(List.of());

        assertThat(service.readHomePrimary().status())
            .isEqualTo(OwnedPlacementDecisionReader.Status.EMPTY);
    }

    @Test
    void sourceReaderFailureIsUnavailableAndMustNotTriggerCommercialFallback() {
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(enabledSlot());
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), eq(100)))
            .thenReturn(List.of(candidate()));
        when(memberProfileReader.currentLevelCode(91L)).thenReturn("BASIC");
        when(reader.readForUser(801L, 91L)).thenThrow(new RuntimeException("source timeout"));

        assertThat(service.readHomePrimary().status())
            .isEqualTo(OwnedPlacementDecisionReader.Status.UNAVAILABLE);
    }

    @Test
    void withdrawnSourceIsExplicitEmptyButNoSourcePayloadLeaks() {
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(enabledSlot());
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), eq(100)))
            .thenReturn(List.of(candidate()));
        when(memberProfileReader.currentLevelCode(91L)).thenReturn("BASIC");
        when(reader.readForUser(801L, 91L)).thenReturn(null);

        assertThat(service.readHomePrimary().status())
            .isEqualTo(OwnedPlacementDecisionReader.Status.EMPTY);
    }

    @Test
    void availableR6DecisionBlocksCommercialFallback() {
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(enabledSlot());
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), eq(100)))
            .thenReturn(List.of(candidate()));
        when(memberProfileReader.currentLevelCode(91L)).thenReturn("BASIC");
        when(reader.readForUser(801L, 91L)).thenReturn(new PlacementSourceSnapshot(
            PlacementSourceType.GLOBAL_NOTICE, 801L, "v1", "自营", "摘要", null,
            LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1), "/me/messages"));
        when(decisionMapper.lockUserCandidateRange(91L, 1L, 701L)).thenReturn(1);
        when(decisionMapper.findReusableIssuedForUpdate(eq(91L), eq(1L), eq(701L), any())).thenReturn(null);
        when(eventMapper.countAcceptedViewable(eq(91L), eq(1L), eq(701L), any())).thenReturn(0);
        when(frequencyGuard.tryReadHint(eq(91L), eq(1L), eq(701L), eq(24))).thenReturn(0L);
        when(tokenService.generate()).thenReturn(new PlacementTokenService.GeneratedToken("raw", "hash"));
        when(tokenService.earliestExpiry(any(), any(), any())).thenReturn(LocalDateTime.now().plusMinutes(5));

        assertThat(service.readHomePrimary().status())
            .isEqualTo(OwnedPlacementDecisionReader.Status.AVAILABLE);
    }

    private PlacementSlot enabledSlot() {
        PlacementSlot slot = new PlacementSlot();
        slot.id = 1L;
        slot.slotCode = "HOME_PRIMARY";
        slot.status = "ENABLED";
        return slot;
    }

    private PlacementCandidateVersion candidate() {
        PlacementCandidateVersion candidate = new PlacementCandidateVersion();
        candidate.id = 701L;
        candidate.slotId = 1L;
        candidate.sourceType = "GLOBAL_NOTICE";
        candidate.sourceId = 801L;
        candidate.effectiveFrom = LocalDateTime.now().minusMinutes(1);
        candidate.effectiveUntil = LocalDateTime.now().plusHours(1);
        candidate.audienceType = "ALL_AUTHENTICATED";
        candidate.priority = 1;
        candidate.frequencyCapPerUser = 1;
        candidate.frequencyCapWindowHours = 24;
        candidate.status = "PUBLISHED";
        return candidate;
    }

    private void authenticate(Long userId) {
        CurrentUser principal = new CurrentUser(userId, "user-" + userId, Set.of("USER"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
