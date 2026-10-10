package com.example.venue.advertising.service;

import com.example.venue.advertising.dto.AdvertisingDecisionRequests;
import com.example.venue.advertising.dto.AdvertisingDecisionResponses;
import com.example.venue.advertising.entity.AdvertisingDecision;
import com.example.venue.advertising.entity.AdvertisingDecisionGuard;
import com.example.venue.advertising.entity.AdvertisingEvent;
import com.example.venue.advertising.mapper.AdvertisingDecisionGuardMapper;
import com.example.venue.advertising.mapper.AdvertisingDecisionMapper;
import com.example.venue.advertising.mapper.AdvertisingEventMapper;
import com.example.venue.common.BusinessException;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdvertisingEventServiceTest {
    private static final long USER_ID = 88L;
    private static final long DECISION_ID = 901L;
    private static final String TOKEN = "commercial-token-raw";

    @Mock private AdvertisingDecisionMapper decisionMapper;
    @Mock private AdvertisingEventMapper eventMapper;
    @Mock private AdvertisingDecisionGuardMapper guardMapper;
    @Mock private AdvertisingPrivacyPreferenceService privacyService;
    @Mock private AdvertisingFrequencyHint frequencyHint;

    private final AdvertisingTokenService tokenService = new AdvertisingTokenService();
    private AdvertisingEventService service;

    @BeforeEach
    void setUp() {
        service = new AdvertisingEventService(
            decisionMapper, eventMapper, guardMapper, privacyService, tokenService, frequencyHint);
        authenticate(USER_ID);
        lenient().when(privacyService.isCommercialMeasurementEnabled(USER_ID)).thenReturn(true);
        lenient().when(privacyService.lockForAdvertisingFact(USER_ID)).thenReturn(true);
        lenient().when(guardMapper.lockScope(anyLong(), eq(AdvertisingDecisionService.HOME_COMMERCIAL_CODE), any()))
            .thenReturn(new AdvertisingDecisionGuard());
        lenient().when(eventMapper.findByEventKey(any())).thenReturn(null);
        lenient().when(eventMapper.findByDecisionAndType(anyLong(), any())).thenReturn(null);
        lenient().when(eventMapper.insert(any(AdvertisingEvent.class))).thenReturn(1);
        lenient().when(decisionMapper.markConsumed(anyLong(), any())).thenReturn(1);
        lenient().when(eventMapper.countAcceptedViewableByUserSlot(anyLong(), any(), any())).thenReturn(0);
        lenient().when(eventMapper.countAcceptedViewableByUserCreative(anyLong(), anyLong(), any())).thenReturn(0);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsThresholdMeetingViewableAndConsumesDecision() {
        AdvertisingDecision decision = decision("ISSUED", LocalDateTime.now().plusMinutes(5));
        stubDecision(decision);

        AdvertisingDecisionResponses.RecordEventResult result = service.recordEvent(
            DECISION_ID, viewable(1.0, 1000), "view-key-1");

        assertThat(result.resultStatus()).isEqualTo("ACCEPTED");
        assertThat(result.replayed()).isFalse();
        ArgumentCaptor<AdvertisingEvent> captor = ArgumentCaptor.forClass(AdvertisingEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertThat(captor.getValue().eventType).isEqualTo("CLIENT_VIEWABLE");
        assertThat(captor.getValue().visibleRatio).isEqualByComparingTo("1.0000");
        verify(decisionMapper).markConsumed(eq(DECISION_ID), any());
        verify(frequencyHint).recordAccepted(USER_ID, 301L, 24);
    }

    @Test
    void acceptsClickBeforeViewableWithoutConsumingDecision() {
        AdvertisingDecision decision = decision("ISSUED", LocalDateTime.now().plusMinutes(5));
        stubDecision(decision);

        AdvertisingDecisionResponses.RecordEventResult result = service.recordEvent(
            DECISION_ID, click(), "click-key-1");

        assertThat(result.resultStatus()).isEqualTo("ACCEPTED");
        verify(eventMapper).insert(any(AdvertisingEvent.class));
        verify(decisionMapper, never()).markConsumed(anyLong(), any());
        verify(guardMapper, never()).findScope(anyLong(), any(), any());
        verify(guardMapper, never()).insertScope(anyLong(), any(), any());
    }

    @Test
    void belowThresholdIsRejectedWithoutWritingAnEvent() {
        stubDecision(decision("ISSUED", LocalDateTime.now().plusMinutes(5)));

        AdvertisingDecisionResponses.RecordEventResult result = service.recordEvent(
            DECISION_ID, viewable(0.4999, 1000), "view-key-low");

        assertThat(result.resultStatus()).isEqualTo("REJECTED");
        assertThat(result.rejectionCode()).isEqualTo("VISIBILITY_BELOW_THRESHOLD");
        verify(eventMapper, never()).insert(any(AdvertisingEvent.class));
    }

    @Test
    void privacyWithdrawalBlocksNewEventButDoesNotTouchExistingFacts() {
        stubDecision(decision("ISSUED", LocalDateTime.now().plusMinutes(5)));
        when(privacyService.isCommercialMeasurementEnabled(USER_ID)).thenReturn(false);

        AdvertisingDecisionResponses.RecordEventResult result = service.recordEvent(
            DECISION_ID, click(), "click-after-withdrawal");

        assertThat(result.rejectionCode()).isEqualTo("ADVERTISING_MEASUREMENT_DISABLED");
        verify(eventMapper, never()).insert(any(AdvertisingEvent.class));
        verify(decisionMapper, never()).markConsumed(anyLong(), any());
    }

    @Test
    void crossUserTokenIsRejectedWithoutEventLookupBySuppliedUserId() {
        authenticate(89L);
        AdvertisingDecision decision = decision("ISSUED", LocalDateTime.now().plusMinutes(5));
        stubDecision(decision);

        assertThatThrownBy(() -> service.recordEvent(DECISION_ID, click(), "cross-user"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ADVERTISING_TOKEN_INVALID");
        verify(eventMapper, never()).insert(any(AdvertisingEvent.class));
    }

    @Test
    void expiredTokenIsRejected() {
        stubDecision(decision("ISSUED", LocalDateTime.now().minusSeconds(1)));

        assertThatThrownBy(() -> service.recordEvent(DECISION_ID, click(), "expired"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ADVERTISING_TOKEN_EXPIRED");
        verify(eventMapper, never()).insert(any(AdvertisingEvent.class));
    }

    @Test
    void sameIdempotencyKeyAndPayloadReplaysOriginalEvent() {
        AdvertisingDecision decision = decision("ISSUED", LocalDateTime.now().plusMinutes(5));
        stubDecision(decision);
        when(eventMapper.findByEventKey(any())).thenReturn(null);

        service.recordEvent(DECISION_ID, click(), "same-key");
        AdvertisingEvent inserted = captureInsertedEvent();
        when(eventMapper.findByEventKey(any())).thenReturn(inserted);

        AdvertisingDecisionResponses.RecordEventResult replayed = service.recordEvent(
            DECISION_ID, click(), "same-key");

        assertThat(replayed.replayed()).isTrue();
        verify(eventMapper).insert(any(AdvertisingEvent.class));
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadConflicts() {
        AdvertisingDecision decision = decision("ISSUED", LocalDateTime.now().plusMinutes(5));
        stubDecision(decision);
        AdvertisingEvent existing = persistedEvent("CLICK", "different-request-hash");
        when(eventMapper.findByEventKey(any())).thenReturn(existing);

        assertThatThrownBy(() -> service.recordEvent(DECISION_ID, click(), "same-key"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ADVERTISING_EVENT_IDEMPOTENCY_CONFLICT");
        verify(eventMapper, never()).insert(any(AdvertisingEvent.class));
    }

    @Test
    void sameDecisionAndEventTypeWithAnotherKeyIsRejected() {
        AdvertisingDecision decision = decision("CONSUMED", LocalDateTime.now().plusMinutes(5));
        stubDecision(decision);
        when(eventMapper.findByDecisionAndType(DECISION_ID, "CLICK"))
            .thenReturn(persistedEvent("CLICK", "existing"));

        AdvertisingDecisionResponses.RecordEventResult result = service.recordEvent(
            DECISION_ID, click(), "another-key");

        assertThat(result.rejectionCode()).isEqualTo("DUPLICATE_EVENT_TYPE");
        verify(eventMapper, never()).insert(any(AdvertisingEvent.class));
    }

    @Test
    void MySqlSlotCapRejectsViewableEvenWhenRedisHintIsNotAvailable() {
        stubDecision(decision("ISSUED", LocalDateTime.now().plusMinutes(5)));
        when(eventMapper.countAcceptedViewableByUserSlot(anyLong(), any(), any())).thenReturn(3);
        AdvertisingDecisionResponses.RecordEventResult result = service.recordEvent(
            DECISION_ID, viewable(1.0, 2000), "cap-key");

        assertThat(result.rejectionCode()).isEqualTo("USER_SLOT_FREQUENCY_CAP");
        verify(eventMapper, never()).insert(any(AdvertisingEvent.class));
    }

    @Test
    void databaseInsertFailureIsNotConvertedToAFalseSuccess() {
        stubDecision(decision("ISSUED", LocalDateTime.now().plusMinutes(5)));
        when(eventMapper.insert(any(AdvertisingEvent.class)))
            .thenThrow(new RuntimeException("advertising database down"));

        assertThatThrownBy(() -> service.recordEvent(DECISION_ID, click(), "db-failure"))
            .hasMessage("advertising database down");
        verify(decisionMapper, never()).markConsumed(anyLong(), any());
    }

    private AdvertisingDecision decision(String status, LocalDateTime expiresAt) {
        AdvertisingDecision decision = new AdvertisingDecision();
        decision.id = DECISION_ID;
        decision.userId = USER_ID;
        decision.slotCode = AdvertisingDecisionService.HOME_COMMERCIAL_CODE;
        decision.advertiserId = 101L;
        decision.campaignId = 201L;
        decision.creativeVersionId = 301L;
        decision.tokenHash = tokenService.hash(TOKEN);
        decision.policyVersion = AdvertisingPrivacyPreferenceService.POLICY_VERSION;
        decision.expiresAt = expiresAt;
        decision.status = status;
        return decision;
    }

    private void stubDecision(AdvertisingDecision decision) {
        lenient().when(decisionMapper.findByTokenHash(eq(decision.tokenHash))).thenReturn(decision);
        lenient().when(decisionMapper.findByTokenHashForUpdate(eq(decision.tokenHash))).thenReturn(decision);
    }

    private AdvertisingDecisionRequests.RecordEventRequest viewable(double ratio, int duration) {
        return new AdvertisingDecisionRequests.RecordEventRequest(
            AdvertisingEventService.CLIENT_VIEWABLE, TOKEN, ratio, duration);
    }

    private AdvertisingDecisionRequests.RecordEventRequest click() {
        return new AdvertisingDecisionRequests.RecordEventRequest(
            AdvertisingEventService.CLICK, TOKEN, null, null);
    }

    private AdvertisingEvent persistedEvent(String eventType, String requestHash) {
        AdvertisingEvent event = new AdvertisingEvent();
        event.id = 1001L;
        event.eventType = eventType;
        event.requestHash = requestHash;
        event.resultStatus = "ACCEPTED";
        return event;
    }

    private AdvertisingEvent captureInsertedEvent() {
        ArgumentCaptor<AdvertisingEvent> captor = ArgumentCaptor.forClass(AdvertisingEvent.class);
        verify(eventMapper).insert(captor.capture());
        return captor.getValue();
    }

    private void authenticate(Long userId) {
        CurrentUser principal = new CurrentUser(userId, "user-" + userId, Set.of("USER"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
