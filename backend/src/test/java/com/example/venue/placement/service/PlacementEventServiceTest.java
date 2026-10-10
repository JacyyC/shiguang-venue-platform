package com.example.venue.placement.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.placement.dto.PlacementRequests;
import com.example.venue.placement.dto.PlacementResponses;
import com.example.venue.placement.entity.PlacementDecision;
import com.example.venue.placement.entity.PlacementEvent;
import com.example.venue.placement.mapper.PlacementDecisionMapper;
import com.example.venue.placement.mapper.PlacementEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
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
class PlacementEventServiceTest {

    @Mock private PlacementDecisionMapper decisionMapper;
    @Mock private PlacementEventMapper eventMapper;
    @Mock private PlacementTokenService tokenService;
    @Mock private PlacementFrequencyGuard frequencyGuard;

    private PlacementEventService service;

    @BeforeEach
    void setUp() {
        service = new PlacementEventService(decisionMapper, eventMapper, tokenService, frequencyGuard);
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

    private PlacementDecision validDecision(Long decisionId, Long userId) {
        PlacementDecision decision = new PlacementDecision();
        decision.id = decisionId;
        decision.userId = userId;
        decision.slotId = 1L;
        decision.candidateVersionId = 50L;
        decision.tokenHash = "valid-hash";
        decision.expiresAt = LocalDateTime.now().plusMinutes(5);
        decision.status = "ISSUED";
        return decision;
    }

    private PlacementRequests.RecordEventRequest viewableRequest(String token) {
        return new PlacementRequests.RecordEventRequest("CLIENT_VIEWABLE", token, 0.75, 1500);
    }

    private PlacementRequests.RecordEventRequest clickRequest(String token) {
        return new PlacementRequests.RecordEventRequest("CLICK", token, null, null);
    }

    @Test
    void unauthenticatedEventIsRejected() {
        assertThatThrownBy(() -> service.recordEvent(1L, viewableRequest("token"), "idem-1"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void invalidEventTypeIsRejected() {
        authenticate(10L);
        assertThatThrownBy(() -> service.recordEvent(1L,
            new PlacementRequests.RecordEventRequest("SCROLL", "token", null, null), "idem-1"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void blankIdempotencyKeyIsRejected() {
        authenticate(10L);
        assertThatThrownBy(() -> service.recordEvent(1L, viewableRequest("token"), ""))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void oversizeIdempotencyKeyIsRejected() {
        authenticate(10L);
        assertThatThrownBy(() -> service.recordEvent(1L, viewableRequest("token"), "k".repeat(161)))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void acceptsMaxLengthIdempotencyKeyWithoutExceedingPersistentEventKeyCapacity() {
        authenticate(10L);
        PlacementDecision decision = validDecision(5L, 10L);
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(decision);
        when(eventMapper.findByKey(any())).thenReturn(null);
        when(eventMapper.findByDecisionAndType(5L, "CLICK")).thenReturn(null);
        when(eventMapper.insertEvent(any())).thenReturn(1);

        PlacementResponses.RecordEventResult result =
            service.recordEvent(5L, clickRequest("token"), "k".repeat(160));

        ArgumentCaptor<PlacementEvent> eventCaptor = ArgumentCaptor.forClass(PlacementEvent.class);
        verify(eventMapper).insertEvent(eventCaptor.capture());
        assertThat(result.resultStatus()).isEqualTo("ACCEPTED");
        assertThat(eventCaptor.getValue().eventKey).hasSize(64);
        assertThat(eventCaptor.getValue().eventKey).doesNotContain("k".repeat(160));
    }

    @Test
    void missingTokenIsRejected() {
        authenticate(10L);
        assertThatThrownBy(() -> service.recordEvent(1L,
            new PlacementRequests.RecordEventRequest("CLICK", null, null, null), "idem-1"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void tokenHashNotFoundReturnsNonEnumerableError() {
        authenticate(10L);
        when(tokenService.hash("forged")).thenReturn("forged-hash");
        when(decisionMapper.findByTokenHash("forged-hash")).thenReturn(null);

        assertThatThrownBy(() -> service.recordEvent(1L, clickRequest("forged"), "idem-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PLACEMENT_TOKEN_INVALID");
    }

    @Test
    void crossUserTokenIsRejectedWithoutLeakingOwnership() {
        authenticate(10L);
        PlacementDecision otherUsersDecision = validDecision(5L, 99L);
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(otherUsersDecision);

        assertThatThrownBy(() -> service.recordEvent(5L, clickRequest("token"), "idem-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PLACEMENT_TOKEN_INVALID");
    }

    @Test
    void decisionIdMismatchIsRejected() {
        authenticate(10L);
        PlacementDecision decision = validDecision(5L, 10L);
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(decision);

        assertThatThrownBy(() -> service.recordEvent(999L, clickRequest("token"), "idem-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PLACEMENT_TOKEN_INVALID");
    }

    @Test
    void expiredTokenIsRejectedWithConflict() {
        authenticate(10L);
        PlacementDecision decision = validDecision(5L, 10L);
        decision.expiresAt = LocalDateTime.now().minusMinutes(1);
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(decision);

        assertThatThrownBy(() -> service.recordEvent(5L, clickRequest("token"), "idem-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PLACEMENT_TOKEN_EXPIRED");
    }

    @Test
    void expiredStatusIsRejectedWithConflict() {
        authenticate(10L);
        PlacementDecision decision = validDecision(5L, 10L);
        decision.status = "EXPIRED";
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(decision);

        assertThatThrownBy(() -> service.recordEvent(5L, clickRequest("token"), "idem-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PLACEMENT_TOKEN_EXPIRED");
    }

    @Test
    void acceptsViewableEventAboveThresholdAndConsumesDecision() {
        authenticate(10L);
        PlacementDecision decision = validDecision(5L, 10L);
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(decision);
        when(eventMapper.findByKey(any())).thenReturn(null);
        when(eventMapper.findByDecisionAndType(5L, "CLIENT_VIEWABLE")).thenReturn(null);
        when(eventMapper.insertEvent(any())).thenReturn(1);
        when(decisionMapper.markConsumed(eq(5L), any())).thenReturn(1);

        PlacementResponses.RecordEventResult result =
            service.recordEvent(5L, viewableRequest("token"), "idem-1");

        assertThat(result.resultStatus()).isEqualTo("ACCEPTED");
        assertThat(result.eventType()).isEqualTo("CLIENT_VIEWABLE");
        verify(eventMapper).insertEvent(any(PlacementEvent.class));
        verify(decisionMapper).markConsumed(eq(5L), any());
        verify(frequencyGuard).recordAccepted(10L, 1L, 50L, 24);
    }

    @Test
    void rejectsViewableBelowRatioThreshold() {
        authenticate(10L);
        PlacementDecision decision = validDecision(5L, 10L);
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(decision);
        when(eventMapper.findByKey(any())).thenReturn(null);
        when(eventMapper.findByDecisionAndType(5L, "CLIENT_VIEWABLE")).thenReturn(null);

        PlacementRequests.RecordEventRequest lowRatio =
            new PlacementRequests.RecordEventRequest("CLIENT_VIEWABLE", "token", 0.49, 1500);

        PlacementResponses.RecordEventResult result =
            service.recordEvent(5L, lowRatio, "idem-1");

        assertThat(result.resultStatus()).isEqualTo("REJECTED");
        assertThat(result.rejectionCode()).isEqualTo("VISIBILITY_BELOW_THRESHOLD");
        verify(eventMapper, never()).insertEvent(any(PlacementEvent.class));
        verify(decisionMapper, never()).markConsumed(anyLong(), any());
    }

    @Test
    void rejectsViewableBelowDurationThreshold() {
        authenticate(10L);
        PlacementDecision decision = validDecision(5L, 10L);
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(decision);
        when(eventMapper.findByKey(any())).thenReturn(null);
        when(eventMapper.findByDecisionAndType(5L, "CLIENT_VIEWABLE")).thenReturn(null);

        PlacementRequests.RecordEventRequest shortDuration =
            new PlacementRequests.RecordEventRequest("CLIENT_VIEWABLE", "token", 0.5, 999);

        PlacementResponses.RecordEventResult result =
            service.recordEvent(5L, shortDuration, "idem-1");

        assertThat(result.resultStatus()).isEqualTo("REJECTED");
        assertThat(result.rejectionCode()).isEqualTo("VISIBILITY_BELOW_THRESHOLD");
    }

    @Test
    void acceptsClickWithoutConsumingDecision() {
        authenticate(10L);
        PlacementDecision decision = validDecision(5L, 10L);
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(decision);
        when(eventMapper.findByKey(any())).thenReturn(null);
        when(eventMapper.findByDecisionAndType(5L, "CLICK")).thenReturn(null);
        when(eventMapper.insertEvent(any())).thenReturn(1);

        PlacementResponses.RecordEventResult result =
            service.recordEvent(5L, clickRequest("token"), "idem-1");

        assertThat(result.resultStatus()).isEqualTo("ACCEPTED");
        verify(decisionMapper, never()).markConsumed(anyLong(), any());
        verify(frequencyGuard, never()).recordAccepted(anyLong(), anyLong(), anyLong(), anyInt());
    }

    @Test
    void sameKeySamePayloadReplaysOriginalResult() {
        authenticate(10L);
        PlacementDecision decision = validDecision(5L, 10L);
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(decision);

        PlacementEvent existing = new PlacementEvent();
        existing.resultStatus = "ACCEPTED";
        existing.rejectionCode = null;
        existing.eventType = "CLICK";
        existing.requestHash = requestHash("CLICK", null, null);
        when(eventMapper.findByKey("5:CLICK:idem-1")).thenReturn(existing);

        PlacementResponses.RecordEventResult result =
            service.recordEvent(5L, clickRequest("token"), "idem-1");

        assertThat(result.resultStatus()).isEqualTo("ACCEPTED");
        verify(eventMapper, never()).insertEvent(any(PlacementEvent.class));
    }

    @Test
    void uniqueKeyRaceReadsPersistedEventAndReplaysOriginalResult() {
        authenticate(10L);
        PlacementDecision decision = validDecision(5L, 10L);
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(decision);

        PlacementEvent existing = new PlacementEvent();
        existing.resultStatus = "ACCEPTED";
        existing.eventType = "CLICK";
        existing.requestHash = requestHash("CLICK", null, null);
        when(eventMapper.findByKey(any())).thenReturn(null, existing);
        when(eventMapper.findByDecisionAndType(5L, "CLICK")).thenReturn(null);
        when(eventMapper.insertEvent(any())).thenThrow(new DuplicateKeyException("uk_placement_event_key"));

        PlacementResponses.RecordEventResult result =
            service.recordEvent(5L, clickRequest("token"), "idem-1");

        assertThat(result.resultStatus()).isEqualTo("ACCEPTED");
        verify(eventMapper).insertEvent(any(PlacementEvent.class));
    }

    @Test
    void sameKeyDifferentPayloadConflicts() {
        authenticate(10L);
        PlacementDecision decision = validDecision(5L, 10L);
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(decision);

        PlacementEvent existing = new PlacementEvent();
        existing.resultStatus = "ACCEPTED";
        existing.rejectionCode = null;
        existing.eventType = "CLIENT_VIEWABLE";
        existing.requestHash = "different-hash";
        when(eventMapper.findByKey(any())).thenReturn(existing);

        assertThatThrownBy(() -> service.recordEvent(5L, viewableRequest("token"), "idem-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PLACEMENT_EVENT_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void duplicateEventTypeWithDifferentKeyIsRejected() {
        authenticate(10L);
        PlacementDecision decision = validDecision(5L, 10L);
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(decision);
        when(eventMapper.findByKey(any())).thenReturn(null);

        PlacementEvent existing = new PlacementEvent();
        existing.eventType = "CLICK";
        when(eventMapper.findByDecisionAndType(5L, "CLICK")).thenReturn(existing);

        PlacementResponses.RecordEventResult result =
            service.recordEvent(5L, clickRequest("token"), "different-key");

        assertThat(result.resultStatus()).isEqualTo("REJECTED");
        assertThat(result.rejectionCode()).isEqualTo("DUPLICATE_EVENT_TYPE");
        verify(eventMapper, never()).insertEvent(any(PlacementEvent.class));
    }

    @Test
    void clickBeforeExposureIsAcceptedAsDataGap() {
        authenticate(10L);
        PlacementDecision decision = validDecision(5L, 10L);
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(decision);
        when(eventMapper.findByKey(any())).thenReturn(null);
        when(eventMapper.findByDecisionAndType(5L, "CLICK")).thenReturn(null);
        when(eventMapper.insertEvent(any())).thenReturn(1);

        PlacementResponses.RecordEventResult clickResult =
            service.recordEvent(5L, clickRequest("token"), "click-key");
        assertThat(clickResult.resultStatus()).isEqualTo("ACCEPTED");

        // subsequent exposure can still be accepted
        when(eventMapper.findByKey(any())).thenReturn(null);
        when(eventMapper.findByDecisionAndType(5L, "CLIENT_VIEWABLE")).thenReturn(null);
        when(eventMapper.insertEvent(any())).thenReturn(1);
        when(decisionMapper.markConsumed(eq(5L), any())).thenReturn(1);

        PlacementResponses.RecordEventResult viewResult =
            service.recordEvent(5L, viewableRequest("token"), "view-key");
        assertThat(viewResult.resultStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void nonDuplicateDatabaseErrorRemainsVisible() {
        authenticate(10L);
        PlacementDecision decision = validDecision(5L, 10L);
        when(tokenService.hash("token")).thenReturn("hash");
        when(decisionMapper.findByTokenHash("hash")).thenReturn(decision);
        when(eventMapper.findByKey(any())).thenReturn(null);
        when(eventMapper.findByDecisionAndType(5L, "CLICK")).thenReturn(null);
        DataIntegrityViolationException databaseFailure =
            new DataIntegrityViolationException("placement_event CHECK constraint failed");
        when(eventMapper.insertEvent(any())).thenThrow(databaseFailure);

        assertThatThrownBy(() -> service.recordEvent(5L, clickRequest("token"), "database-failure"))
            .isSameAs(databaseFailure);
        verify(decisionMapper, never()).markConsumed(anyLong(), any());
        verify(frequencyGuard, never()).recordAccepted(anyLong(), anyLong(), anyLong(), anyInt());
    }

    private String requestHash(String eventType, Double visibleRatio, Integer visibleDurationMs) {
        String payload = eventType + "|"
            + (visibleRatio == null ? "" : visibleRatio) + "|"
            + (visibleDurationMs == null ? "" : visibleDurationMs);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
