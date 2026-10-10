package com.example.venue.placement.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.placement.dto.PlacementResponses;
import com.example.venue.placement.entity.PlacementCandidateVersion;
import com.example.venue.placement.entity.PlacementDecision;
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
import com.example.venue.security.SecurityContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlacementDecisionServiceTest {

    @Mock private PlacementSlotMapper slotMapper;
    @Mock private PlacementCandidateVersionMapper candidateMapper;
    @Mock private PlacementDecisionMapper decisionMapper;
    @Mock private PlacementEventMapper eventMapper;
    @Mock private PlacementMemberProfileReader memberProfileReader;
    @Mock private PlacementTokenService tokenService;
    @Mock private PlacementFrequencyGuard frequencyGuard;
    @Mock private PlacementSourceReader reader;
    @Mock private PlacementSourceReader promotionReader;
    @Mock private PlacementSourceReader communityReader;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PlacementSourceReaderRegistry registry;
    private PlacementDecisionService service;

    @BeforeEach
    void setUp() {
        when(reader.sourceType()).thenReturn(PlacementSourceType.GLOBAL_NOTICE);
        when(promotionReader.sourceType()).thenReturn(PlacementSourceType.PRICE_PROMOTION);
        when(communityReader.sourceType()).thenReturn(PlacementSourceType.COMMUNITY_ACTIVITY);
        registry = new PlacementSourceReaderRegistry(List.of(reader, promotionReader, communityReader));
        service = new PlacementDecisionService(
            slotMapper, candidateMapper, decisionMapper, eventMapper,
            registry, memberProfileReader, tokenService, frequencyGuard, objectMapper);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Long userId) {
        CurrentUser principal = new CurrentUser(userId, "user-" + userId, Set.of("USER"), Set.of());
        Authentication auth = new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private PlacementSlot slot() {
        PlacementSlot slot = new PlacementSlot();
        slot.id = 1L;
        slot.slotCode = "HOME_PRIMARY";
        slot.status = "ENABLED";
        return slot;
    }

    private PlacementCandidateVersion candidate(Long id, String audienceType, String levelCode, int priority) {
        return candidate(id, PlacementSourceType.GLOBAL_NOTICE, audienceType, levelCode, priority);
    }

    private PlacementCandidateVersion candidate(Long id,
                                                PlacementSourceType sourceType,
                                                String audienceType,
                                                String levelCode,
                                                int priority) {
        PlacementCandidateVersion c = new PlacementCandidateVersion();
        c.id = id;
        c.slotId = 1L;
        c.sourceType = sourceType.name();
        c.sourceId = 100L;
        c.effectiveFrom = LocalDateTime.now().minusHours(1);
        c.effectiveUntil = LocalDateTime.now().plusHours(2);
        c.audienceType = audienceType;
        c.audienceLevelCode = levelCode;
        c.priority = priority;
        c.frequencyCapPerUser = 1;
        c.frequencyCapWindowHours = 24;
        c.ruleVersion = "PLACEMENT_RULE_V1";
        c.status = "PUBLISHED";
        return c;
    }

    private PlacementSourceSnapshot snapshot() {
        return snapshot(PlacementSourceType.GLOBAL_NOTICE);
    }

    private PlacementSourceSnapshot snapshot(PlacementSourceType sourceType) {
        String title = sourceType == PlacementSourceType.GLOBAL_NOTICE ? "公告标题" : "来源标题";
        String deepLink = switch (sourceType) {
            case GLOBAL_NOTICE -> "/me/messages";
            case PRICE_PROMOTION -> "/venues";
            case COMMUNITY_ACTIVITY -> "/activities/100";
        };
        return new PlacementSourceSnapshot(
            sourceType, 100L, "v1", title, "摘要",
            null, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(2),
            deepLink);
    }

    private PlacementSourceReader readerFor(PlacementSourceType sourceType) {
        return switch (sourceType) {
            case GLOBAL_NOTICE -> reader;
            case PRICE_PROMOTION -> promotionReader;
            case COMMUNITY_ACTIVITY -> communityReader;
        };
    }

    private PlacementSourceType fallbackFor(PlacementSourceType brokenType) {
        return switch (brokenType) {
            case GLOBAL_NOTICE -> PlacementSourceType.PRICE_PROMOTION;
            case PRICE_PROMOTION -> PlacementSourceType.COMMUNITY_ACTIVITY;
            case COMMUNITY_ACTIVITY -> PlacementSourceType.GLOBAL_NOTICE;
        };
    }

    @Test
    void unauthenticatedRequestIsRejected() {
        assertThatThrownBy(() -> service.decideHomePrimary())
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void returnsNullWhenSlotDisabled() {
        authenticate(10L);
        PlacementSlot slot = slot();
        slot.status = "DISABLED";
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(slot);

        assertThat(service.decideHomePrimary()).isNull();
        verify(candidateMapper, never()).findPublishedForDecision(anyLong(), any(), anyInt());
    }

    @Test
    void returnsNullWhenNoCandidates() {
        authenticate(10L);
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(slot());
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), anyInt())).thenReturn(List.of());

        assertThat(service.decideHomePrimary()).isNull();
        verify(candidateMapper).findPublishedForDecision(eq(1L), any(), eq(100));
    }

    @Test
    void issuesDecisionForAllAuthenticatedAudience() {
        authenticate(10L);
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(slot());
        PlacementCandidateVersion c = candidate(50L, "ALL_AUTHENTICATED", null, 10);
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), anyInt())).thenReturn(List.of(c));
        when(reader.readForUser(100L, 10L)).thenReturn(snapshot());
        when(memberProfileReader.currentLevelCode(10L)).thenReturn("BASIC");
        when(decisionMapper.lockUserCandidateRange(10L, 1L, 50L)).thenReturn(1);
        when(decisionMapper.findReusableIssuedForUpdate(eq(10L), eq(1L), eq(50L), any())).thenReturn(null);
        when(eventMapper.countAcceptedViewable(eq(10L), eq(1L), eq(50L), any())).thenReturn(0);
        when(frequencyGuard.tryReadHint(10L, 1L, 50L, 24)).thenReturn(0L);
        when(tokenService.generate()).thenReturn(new PlacementTokenService.GeneratedToken("raw-token", "hash-value"));
        when(tokenService.earliestExpiry(any(), any(), any())).thenReturn(LocalDateTime.now().plusMinutes(10));

        PlacementResponses.HomePrimaryDecision result = service.decideHomePrimary();

        assertThat(result).isNotNull();
        assertThat(result.reused()).isFalse();
        assertThat(result.token()).isEqualTo("raw-token");
        assertThat(result.title()).isEqualTo("公告标题");
        assertThat(result.deepLink()).isEqualTo("/me/messages");
        verify(decisionMapper).insert(any(PlacementDecision.class));
    }

    @Test
    void memberLevelAudienceOnlyMatchesExactLevelCode() {
        authenticate(10L);
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(slot());
        PlacementCandidateVersion goldOnly = candidate(50L, "MEMBER_LEVEL", "GOLD", 10);
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), anyInt())).thenReturn(List.of(goldOnly));
        when(memberProfileReader.currentLevelCode(10L)).thenReturn("BASIC");

        assertThat(service.decideHomePrimary()).isNull();
        verify(reader, never()).readForUser(anyLong(), anyLong());
    }

    @Test
    void skipsCandidateWhenSourceReaderReturnsNull() {
        authenticate(10L);
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(slot());
        PlacementCandidateVersion c = candidate(50L, "ALL_AUTHENTICATED", null, 10);
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), anyInt())).thenReturn(List.of(c));
        when(reader.readForUser(100L, 10L)).thenReturn(null);
        when(memberProfileReader.currentLevelCode(10L)).thenReturn("BASIC");

        assertThat(service.decideHomePrimary()).isNull();
        verify(decisionMapper, never()).lockUserCandidateRange(anyLong(), anyLong(), anyLong());
    }

    @Test
    void sourceReaderExceptionIsTreatedAsUnavailableWithoutLeaking() {
        authenticate(10L);
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(slot());
        PlacementCandidateVersion c = candidate(50L, "ALL_AUTHENTICATED", null, 10);
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), anyInt())).thenReturn(List.of(c));
        when(reader.readForUser(100L, 10L)).thenThrow(new RuntimeException("内部来源异常"));
        when(memberProfileReader.currentLevelCode(10L)).thenReturn("BASIC");

        assertThat(service.decideHomePrimary()).isNull();
    }

    @ParameterizedTest
    @EnumSource(PlacementSourceType.class)
    void oneSourceReaderFailureDoesNotBlockAnotherSource(PlacementSourceType brokenType) {
        authenticate(10L);
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(slot());
        PlacementSourceType fallbackType = fallbackFor(brokenType);
        PlacementCandidateVersion broken = candidate(60L, brokenType, "ALL_AUTHENTICATED", null, 20);
        PlacementCandidateVersion fallback = candidate(61L, fallbackType, "ALL_AUTHENTICATED", null, 10);
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), anyInt()))
            .thenReturn(List.of(broken, fallback));
        when(memberProfileReader.currentLevelCode(10L)).thenReturn("BASIC");
        when(readerFor(brokenType).readForUser(100L, 10L))
            .thenThrow(new RuntimeException("source internals must stay private"));
        when(readerFor(fallbackType).readForUser(100L, 10L)).thenReturn(snapshot(fallbackType));
        when(decisionMapper.lockUserCandidateRange(10L, 1L, 61L)).thenReturn(1);
        when(decisionMapper.findReusableIssuedForUpdate(eq(10L), eq(1L), eq(61L), any())).thenReturn(null);
        when(eventMapper.countAcceptedViewable(eq(10L), eq(1L), eq(61L), any())).thenReturn(0);
        when(frequencyGuard.tryReadHint(10L, 1L, 61L, 24)).thenReturn(0L);
        when(tokenService.generate()).thenReturn(new PlacementTokenService.GeneratedToken("fallback-token", "fallback-hash"));
        when(tokenService.earliestExpiry(any(), any(), any())).thenReturn(LocalDateTime.now().plusMinutes(10));

        PlacementResponses.HomePrimaryDecision result = service.decideHomePrimary();

        assertThat(result).isNotNull();
        assertThat(result.candidateVersionId()).isEqualTo(61L);
        assertThat(result.title()).isEqualTo(
            fallbackType == PlacementSourceType.GLOBAL_NOTICE ? "公告标题" : "来源标题");
    }

    @Test
    void reusesExistingUnexpiredDecisionInsteadOfIssuingNew() {
        authenticate(10L);
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(slot());
        PlacementCandidateVersion c = candidate(50L, "ALL_AUTHENTICATED", null, 10);
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), anyInt())).thenReturn(List.of(c));
        when(reader.readForUser(100L, 10L)).thenReturn(snapshot());
        when(memberProfileReader.currentLevelCode(10L)).thenReturn("BASIC");
        when(decisionMapper.lockUserCandidateRange(10L, 1L, 50L)).thenReturn(1);

        PlacementDecision existing = new PlacementDecision();
        existing.id = 99L;
        existing.candidateVersionId = 50L;
        existing.tokenHash = "old-hash";
        existing.sourceSnapshotJson = "{\"title\":\"公告标题\",\"summary\":\"摘要\",\"deepLink\":\"/me/messages\",\"imageUrl\":null}";
        existing.expiresAt = LocalDateTime.now().plusMinutes(5);
        existing.status = "ISSUED";
        when(decisionMapper.findReusableIssuedForUpdate(eq(10L), eq(1L), eq(50L), any())).thenReturn(existing);
        when(tokenService.generate()).thenReturn(new PlacementTokenService.GeneratedToken(
            "rotated-token", "rotated-hash"));
        when(decisionMapper.rotateIssuedTokenHash(eq(99L), eq("rotated-hash"), any())).thenReturn(1);

        PlacementResponses.HomePrimaryDecision result = service.decideHomePrimary();

        assertThat(result).isNotNull();
        assertThat(result.reused()).isTrue();
        assertThat(result.token()).isEqualTo("rotated-token");
        assertThat(result.decisionId()).isEqualTo(99L);
        verify(tokenService).generate();
        verify(decisionMapper).rotateIssuedTokenHash(eq(99L), eq("rotated-hash"), any());
        verify(decisionMapper, never()).insert(any(PlacementDecision.class));
    }

    @Test
    void frequencyCapReachedSkipsToNextCandidate() {
        authenticate(10L);
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(slot());
        PlacementCandidateVersion c1 = candidate(50L, "ALL_AUTHENTICATED", null, 20);
        PlacementCandidateVersion c2 = candidate(51L, "ALL_AUTHENTICATED", null, 10);
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), anyInt())).thenReturn(List.of(c1, c2));
        when(memberProfileReader.currentLevelCode(10L)).thenReturn("BASIC");
        when(reader.readForUser(100L, 10L)).thenReturn(snapshot());

        when(decisionMapper.lockUserCandidateRange(10L, 1L, 50L)).thenReturn(1);
        when(decisionMapper.findReusableIssuedForUpdate(eq(10L), eq(1L), eq(50L), any())).thenReturn(null);
        when(eventMapper.countAcceptedViewable(eq(10L), eq(1L), eq(50L), any())).thenReturn(1);

        when(decisionMapper.lockUserCandidateRange(10L, 1L, 51L)).thenReturn(1);
        when(decisionMapper.findReusableIssuedForUpdate(eq(10L), eq(1L), eq(51L), any())).thenReturn(null);
        when(eventMapper.countAcceptedViewable(eq(10L), eq(1L), eq(51L), any())).thenReturn(0);
        when(frequencyGuard.tryReadHint(10L, 1L, 51L, 24)).thenReturn(0L);
        when(tokenService.generate()).thenReturn(new PlacementTokenService.GeneratedToken("raw-2", "hash-2"));
        when(tokenService.earliestExpiry(any(), any(), any())).thenReturn(LocalDateTime.now().plusMinutes(10));

        PlacementResponses.HomePrimaryDecision result = service.decideHomePrimary();

        assertThat(result).isNotNull();
        assertThat(result.candidateVersionId()).isEqualTo(51L);
        assertThat(result.token()).isEqualTo("raw-2");
    }

    @Test
    void redisHintAtCapButMysqlBelowStillAllowsIssuance() {
        authenticate(10L);
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(slot());
        PlacementCandidateVersion c = candidate(50L, "ALL_AUTHENTICATED", null, 10);
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), anyInt())).thenReturn(List.of(c));
        when(reader.readForUser(100L, 10L)).thenReturn(snapshot());
        when(memberProfileReader.currentLevelCode(10L)).thenReturn("BASIC");
        when(decisionMapper.lockUserCandidateRange(10L, 1L, 50L)).thenReturn(1);
        when(decisionMapper.findReusableIssuedForUpdate(eq(10L), eq(1L), eq(50L), any())).thenReturn(null);
        when(eventMapper.countAcceptedViewable(eq(10L), eq(1L), eq(50L), any())).thenReturn(0);
        // Redis says cap reached (1) but MySQL says 0 — MySQL wins
        when(frequencyGuard.tryReadHint(10L, 1L, 50L, 24)).thenReturn(1L);
        when(tokenService.generate()).thenReturn(new PlacementTokenService.GeneratedToken("raw", "hash"));
        when(tokenService.earliestExpiry(any(), any(), any())).thenReturn(LocalDateTime.now().plusMinutes(10));

        PlacementResponses.HomePrimaryDecision result = service.decideHomePrimary();

        assertThat(result).isNotNull();
        assertThat(result.token()).isEqualTo("raw");
        verify(decisionMapper).insert(any(PlacementDecision.class));
    }

    @Test
    void allCandidatesAtCapReturnsNullForSilentDegradation() {
        authenticate(10L);
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(slot());
        PlacementCandidateVersion c = candidate(50L, "ALL_AUTHENTICATED", null, 10);
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), anyInt())).thenReturn(List.of(c));
        when(reader.readForUser(100L, 10L)).thenReturn(snapshot());
        when(memberProfileReader.currentLevelCode(10L)).thenReturn("BASIC");
        when(decisionMapper.lockUserCandidateRange(10L, 1L, 50L)).thenReturn(1);
        when(decisionMapper.findReusableIssuedForUpdate(eq(10L), eq(1L), eq(50L), any())).thenReturn(null);
        when(eventMapper.countAcceptedViewable(eq(10L), eq(1L), eq(50L), any())).thenReturn(1);

        assertThat(service.decideHomePrimary()).isNull();
        verify(decisionMapper, never()).insert(any(PlacementDecision.class));
    }

    @Test
    void mysqlFrequencyCountFailureFailsClosedBeforeRedisHint() {
        authenticate(10L);
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(slot());
        PlacementCandidateVersion c = candidate(50L, "ALL_AUTHENTICATED", null, 10);
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), anyInt())).thenReturn(List.of(c));
        when(reader.readForUser(100L, 10L)).thenReturn(snapshot());
        when(memberProfileReader.currentLevelCode(10L)).thenReturn("BASIC");
        when(decisionMapper.lockUserCandidateRange(10L, 1L, 50L)).thenReturn(1);
        when(decisionMapper.findReusableIssuedForUpdate(eq(10L), eq(1L), eq(50L), any())).thenReturn(null);
        when(eventMapper.countAcceptedViewable(eq(10L), eq(1L), eq(50L), any()))
            .thenThrow(new RuntimeException("mysql unavailable"));

        assertThatThrownBy(() -> service.decideHomePrimary())
            .isInstanceOf(RuntimeException.class)
            .hasMessage("mysql unavailable");
        verify(frequencyGuard, never()).tryReadHint(anyLong(), anyLong(), anyLong(), anyInt());
        verify(decisionMapper, never()).insert(any(PlacementDecision.class));
    }

    @Test
    void unknownSourceTypeIsSkippedSilently() {
        authenticate(10L);
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(slot());
        PlacementCandidateVersion c = candidate(50L, "ALL_AUTHENTICATED", null, 10);
        c.sourceType = "UNKNOWN_TYPE";
        when(candidateMapper.findPublishedForDecision(eq(1L), any(), anyInt())).thenReturn(List.of(c));
        when(memberProfileReader.currentLevelCode(10L)).thenReturn("BASIC");

        assertThat(service.decideHomePrimary()).isNull();
        verify(reader, never()).readForUser(anyLong(), anyLong());
    }

    @Test
    void registryReturnsEmptyOptionalForUnregisteredType() {
        PlacementSourceReaderRegistry empty = new PlacementSourceReaderRegistry(List.of());
        assertThat(empty.find(PlacementSourceType.GLOBAL_NOTICE)).isEmpty();
    }
}
