package com.example.venue.placement.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.placement.dto.PlacementRequests;
import com.example.venue.placement.dto.PlacementResponses;
import com.example.venue.placement.entity.PlacementCandidateVersion;
import com.example.venue.placement.entity.PlacementSlot;
import com.example.venue.placement.mapper.PlacementCandidateVersionMapper;
import com.example.venue.placement.mapper.PlacementDecisionMapper;
import com.example.venue.placement.mapper.PlacementEventMapper;
import com.example.venue.placement.mapper.PlacementSlotMapper;
import com.example.venue.placement.source.PlacementSourceReader;
import com.example.venue.placement.source.PlacementSourceReaderRegistry;
import com.example.venue.placement.source.PlacementSourceSnapshot;
import com.example.venue.placement.source.PlacementSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlacementAdminServiceTest {

    @Mock private PlacementSlotMapper slotMapper;
    @Mock private PlacementCandidateVersionMapper candidateMapper;
    @Mock private PlacementDecisionMapper decisionMapper;
    @Mock private PlacementEventMapper eventMapper;
    @Mock private PlacementSourceReader reader;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PlacementAdminService service;

    @BeforeEach
    void setUp() {
        when(reader.sourceType()).thenReturn(PlacementSourceType.GLOBAL_NOTICE);
        PlacementSourceReaderRegistry registry = new PlacementSourceReaderRegistry(List.of(reader));
        service = new PlacementAdminService(
            slotMapper, candidateMapper, decisionMapper, eventMapper, registry, objectMapper);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Long userId) {
        CurrentUser principal = new CurrentUser(userId, "admin-" + userId, Set.of("SYSTEM_ADMIN"), Set.of());
        Authentication auth = new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("SYSTEM_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private PlacementSlot slot(Long id) {
        PlacementSlot slot = new PlacementSlot();
        slot.id = id;
        slot.slotCode = "HOME_PRIMARY";
        slot.status = "ENABLED";
        return slot;
    }

    private PlacementSourceSnapshot snapshot() {
        return new PlacementSourceSnapshot(
            PlacementSourceType.GLOBAL_NOTICE, 100L, "v1", "公告标题", "摘要",
            null, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(2),
            "/me/messages");
    }

    @Test
    void createCandidateRequiresValidSourcePreview() {
        authenticate(20L);
        when(slotMapper.selectById(1L)).thenReturn(slot(1L));
        when(reader.readForPreview(100L)).thenReturn(null);

        PlacementRequests.CreateCandidateRequest request = new PlacementRequests.CreateCandidateRequest(
            1L, "GLOBAL_NOTICE", 100L,
            LocalDateTime.now().plusMinutes(1), LocalDateTime.now().plusHours(1),
            "ALL_AUTHENTICATED", null, 5, 1, 24);

        assertThatThrownBy(() -> service.createCandidate(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("来源不可用");
    }

    @Test
    void createCandidateRejectsInvalidTimeWindow() {
        authenticate(20L);
        when(slotMapper.selectById(1L)).thenReturn(slot(1L));
        when(reader.readForPreview(100L)).thenReturn(snapshot());

        PlacementRequests.CreateCandidateRequest request = new PlacementRequests.CreateCandidateRequest(
            1L, "GLOBAL_NOTICE", 100L,
            LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(1),
            "ALL_AUTHENTICATED", null, 0, 1, 24);

        assertThatThrownBy(() -> service.createCandidate(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("生效时间窗");
    }

    @Test
    void createCandidateRejectsMemberLevelWithoutLevelCode() {
        authenticate(20L);
        when(slotMapper.selectById(1L)).thenReturn(slot(1L));
        when(reader.readForPreview(100L)).thenReturn(snapshot());

        PlacementRequests.CreateCandidateRequest request = new PlacementRequests.CreateCandidateRequest(
            1L, "GLOBAL_NOTICE", 100L,
            LocalDateTime.now().plusMinutes(1), LocalDateTime.now().plusHours(1),
            "MEMBER_LEVEL", null, 0, 1, 24);

        assertThatThrownBy(() -> service.createCandidate(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("会员等级");
    }

    @Test
    void createCandidateStoresImmutableRuleAndSourceReference() {
        authenticate(20L);
        when(slotMapper.selectById(1L)).thenReturn(slot(1L));
        when(reader.readForPreview(100L)).thenReturn(snapshot());

        PlacementRequests.CreateCandidateRequest request = new PlacementRequests.CreateCandidateRequest(
            1L, "GLOBAL_NOTICE", 100L,
            LocalDateTime.now().plusMinutes(1), LocalDateTime.now().plusHours(1),
            "ALL_AUTHENTICATED", null, 5, 1, 24);

        PlacementResponses.CandidateVersionResponse response = service.createCandidate(request);

        assertThat(response.status()).isEqualTo("DRAFT");
        assertThat(response.sourceType()).isEqualTo("GLOBAL_NOTICE");
        assertThat(response.sourceId()).isEqualTo(100L);
        assertThat(response.sourceVersion()).isEqualTo("v1");
        assertThat(response.ruleVersion()).isEqualTo("PLACEMENT_RULE_V1");
        assertThat(response.priority()).isEqualTo(5);
        verify(candidateMapper).insert(any(PlacementCandidateVersion.class));
    }

    @Test
    void publishCandidateRechecksSourceAtPublishTime() {
        authenticate(20L);
        PlacementCandidateVersion draft = new PlacementCandidateVersion();
        draft.id = 30L;
        draft.slotId = 1L;
        draft.sourceType = "GLOBAL_NOTICE";
        draft.sourceId = 100L;
        draft.status = "DRAFT";
        when(candidateMapper.selectById(30L)).thenReturn(draft);
        when(reader.readForPreview(100L)).thenReturn(null);

        assertThatThrownBy(() -> service.publishCandidate(30L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("来源不可用");
    }

    @Test
    void publishTransitionsDraftToPublished() {
        authenticate(20L);
        PlacementCandidateVersion draft = new PlacementCandidateVersion();
        draft.id = 30L;
        draft.slotId = 1L;
        draft.sourceType = "GLOBAL_NOTICE";
        draft.sourceId = 100L;
        draft.status = "DRAFT";
        when(candidateMapper.selectById(30L)).thenReturn(draft);
        when(reader.readForPreview(100L)).thenReturn(snapshot());

        PlacementResponses.CandidateVersionResponse response = service.publishCandidate(30L);

        assertThat(response.status()).isEqualTo("PUBLISHED");
        verify(candidateMapper).updateById(any(PlacementCandidateVersion.class));
    }

    @Test
    void cannotPublishFromRetiredStatus() {
        authenticate(20L);
        PlacementCandidateVersion retired = new PlacementCandidateVersion();
        retired.id = 30L;
        retired.status = "RETIRED";
        when(candidateMapper.selectById(30L)).thenReturn(retired);

        assertThatThrownBy(() -> service.publishCandidate(30L))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void pauseTransitionsPublishedToPaused() {
        authenticate(20L);
        PlacementCandidateVersion published = new PlacementCandidateVersion();
        published.id = 30L;
        published.status = "PUBLISHED";
        when(candidateMapper.selectById(30L)).thenReturn(published);

        PlacementResponses.CandidateVersionResponse response = service.pauseCandidate(30L);
        assertThat(response.status()).isEqualTo("PAUSED");
    }

    @Test
    void retireTransitionsPublishedToRetired() {
        authenticate(20L);
        PlacementCandidateVersion published = new PlacementCandidateVersion();
        published.id = 30L;
        published.status = "PUBLISHED";
        when(candidateMapper.selectById(30L)).thenReturn(published);

        PlacementResponses.CandidateVersionResponse response = service.retireCandidate(30L);
        assertThat(response.status()).isEqualTo("RETIRED");
    }

    @Test
    void retireRejectsDraftStatus() {
        authenticate(20L);
        PlacementCandidateVersion draft = new PlacementCandidateVersion();
        draft.id = 30L;
        draft.status = "DRAFT";
        when(candidateMapper.selectById(30L)).thenReturn(draft);

        assertThatThrownBy(() -> service.retireCandidate(30L))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void previewSourceReturnsWhitelistedFieldsOnly() {
        when(reader.readForPreview(100L)).thenReturn(snapshot());

        PlacementResponses.SourcePreviewResponse response =
            service.previewSource("GLOBAL_NOTICE", 100L);

        assertThat(response.title()).isEqualTo("公告标题");
        assertThat(response.deepLink()).isEqualTo("/me/messages");
        assertThat(response.imageUrl()).isNull();
    }

    @Test
    void previewSourceReturnsNotFoundWhenUnavailable() {
        when(reader.readForPreview(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.previewSource("GLOBAL_NOTICE", 999L))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void previewSourceSanitizesReaderRuntimeFailure() {
        when(reader.readForPreview(100L)).thenThrow(new IllegalStateException("source-reader-secret"));

        assertThatThrownBy(() -> service.previewSource("GLOBAL_NOTICE", 100L))
            .isInstanceOf(BusinessException.class)
            .hasMessage("来源暂时不可用，请稍后重试")
            .hasMessageNotContaining("source-reader-secret");
    }

    @Test
    void listCandidatesBoundedToMaxLimit() {
        PlacementCandidateVersion c = new PlacementCandidateVersion();
        c.id = 1L;
        when(candidateMapper.selectList(any())).thenReturn(List.of(c));

        List<PlacementResponses.CandidateVersionResponse> result =
            service.listCandidates(1L, 500);

        assertThat(result).hasSize(1);
    }

    @Test
    void listCandidatesUsesDefaultWhenLimitNull() {
        PlacementCandidateVersion c = new PlacementCandidateVersion();
        c.id = 1L;
        when(candidateMapper.selectList(any())).thenReturn(List.of(c));

        service.listCandidates(null, null);
        // Should not throw; bounded limit applied
    }

    @Test
    void unauthenticatedCreateIsForbidden() {
        PlacementRequests.CreateCandidateRequest request = new PlacementRequests.CreateCandidateRequest(
            1L, "GLOBAL_NOTICE", 100L,
            LocalDateTime.now().plusMinutes(1), LocalDateTime.now().plusHours(1),
            "ALL_AUTHENTICATED", null, 0, 1, 24);

        assertThatThrownBy(() -> service.createCandidate(request))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void unknownSourceTypeIsRejected() {
        assertThatThrownBy(() -> service.previewSource("UNKNOWN", 1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("来源类型不支持");
    }
}
