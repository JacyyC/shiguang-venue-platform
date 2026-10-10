package com.example.venue.advertising.service;

import com.example.venue.advertising.entity.AdvertisingAttribution;
import com.example.venue.advertising.entity.AdvertisingConversionCallback;
import com.example.venue.advertising.entity.AdvertisingEvent;
import com.example.venue.advertising.mapper.AdvertisingAttributionMapper;
import com.example.venue.advertising.mapper.AdvertisingConversionCallbackMapper;
import com.example.venue.advertising.mapper.AdvertisingConversionGuardMapper;
import com.example.venue.advertising.mapper.AdvertisingEventMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdvertisingConversionServiceTest {
    private static final long USER_ID = 7001L;
    private static final long RESERVATION_ID = 7101L;
    private static final String POLICY = "G-R7-20260803-v1";

    @Mock private AdvertisingConversionCallbackMapper callbackMapper;
    @Mock private AdvertisingConversionGuardMapper guardMapper;
    @Mock private AdvertisingAttributionMapper attributionMapper;
    @Mock private AdvertisingEventMapper eventMapper;
    @Mock private AdvertisingPrivacyPreferenceService privacyService;
    @InjectMocks private AdvertisingConversionService service;

    @org.junit.jupiter.api.BeforeEach
    void lockConversionGuard() {
        lenient().when(guardMapper.ensureScope(any(), any())).thenReturn(1);
    }

    @Test
    void attributesTheLatestAcceptedClickWithinSevenDaysAndKeepsPolicySnapshot() {
        LocalDateTime conversionAt = LocalDateTime.of(2026, 8, 3, 12, 0);
        AdvertisingConversionService.ConversionCommand command = submission(conversionAt);
        AdvertisingEvent click = click(conversionAt.minusHours(2));
        when(privacyService.policyVersion(USER_ID)).thenReturn(POLICY);
        when(privacyService.lockForAdvertisingFact(USER_ID)).thenReturn(true);
        when(eventMapper.findLatestAcceptedClickForAttribution(
            USER_ID, conversionAt.minusDays(7), conversionAt)).thenReturn(click);
        doAnswer(invocation -> {
            AdvertisingConversionCallback callback = invocation.getArgument(0);
            callback.id = 1L;
            return 1;
        }).when(callbackMapper).insert(any(AdvertisingConversionCallback.class));

        service.accept(command);

        ArgumentCaptor<AdvertisingAttribution> attributionCaptor =
            ArgumentCaptor.forClass(AdvertisingAttribution.class);
        verify(attributionMapper).insert(attributionCaptor.capture());
        AdvertisingAttribution attribution = attributionCaptor.getValue();
        assertThat(attribution.status).isEqualTo(AdvertisingConversionService.ATTRIBUTED);
        assertThat(attribution.reasonCode).isEqualTo("LAST_VALID_CLICK_7D");
        assertThat(attribution.clickEventId).isEqualTo(click.id);
        assertThat(attribution.policyVersion).isEqualTo(POLICY);

        ArgumentCaptor<AdvertisingConversionCallback> callbackCaptor =
            ArgumentCaptor.forClass(AdvertisingConversionCallback.class);
        verify(callbackMapper).updateById(callbackCaptor.capture());
        assertThat(callbackCaptor.getValue().status).isEqualTo("PROCESSED");
        assertThat(callbackCaptor.getValue().resultCode).isEqualTo("ATTRIBUTED");
    }

    @Test
    void recordsGapWhenPrivacyIsDisabledAndDoesNotReadClickFacts() {
        LocalDateTime conversionAt = LocalDateTime.of(2026, 8, 3, 12, 0);
        AdvertisingConversionService.ConversionCommand command = submission(conversionAt);
        when(privacyService.policyVersion(USER_ID)).thenReturn(POLICY);
        when(privacyService.lockForAdvertisingFact(USER_ID)).thenReturn(false);
        doAnswer(invocation -> {
            AdvertisingConversionCallback callback = invocation.getArgument(0);
            callback.id = 2L;
            return 1;
        }).when(callbackMapper).insert(any(AdvertisingConversionCallback.class));

        service.accept(command);

        ArgumentCaptor<AdvertisingAttribution> attributionCaptor =
            ArgumentCaptor.forClass(AdvertisingAttribution.class);
        verify(attributionMapper).insert(attributionCaptor.capture());
        assertThat(attributionCaptor.getValue().status).isEqualTo(AdvertisingConversionService.GAP);
        assertThat(attributionCaptor.getValue().reasonCode).isEqualTo("PRIVACY_DISABLED");
        verify(eventMapper, never()).findLatestAcceptedClickForAttribution(any(), any(), any());
    }

    @Test
    void processedCallbackIsIdempotentAndDoesNotCreateAnotherAttribution() {
        AdvertisingConversionService.ConversionCommand command = submission(LocalDateTime.of(2026, 8, 3, 12, 0));
        AdvertisingConversionCallback callback = new AdvertisingConversionCallback();
        callback.status = "PROCESSED";
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("existing event"))
            .when(callbackMapper).insert(any(AdvertisingConversionCallback.class));
        when(callbackMapper.findByEventKeyForUpdate(command.eventKey())).thenReturn(callback);

        service.accept(command);

        verify(attributionMapper, never()).findByReservationIdForUpdate(any(Long.class));
        verify(callbackMapper, never()).updateById(any(AdvertisingConversionCallback.class));
    }

    @Test
    void persistsCallbackOutboxBeforeExecutionAndMovesItToRetryWhenDispatchFails() {
        AdvertisingConversionService.ConversionCommand command = submission(
            LocalDateTime.of(2026, 8, 3, 12, 0));
        when(privacyService.policyVersion(USER_ID)).thenReturn(POLICY);
        doAnswer(invocation -> {
            AdvertisingConversionCallback callback = invocation.getArgument(0);
            callback.id = 8L;
            return 1;
        }).when(callbackMapper).insert(any(AdvertisingConversionCallback.class));

        service.persistPending(command);

        ArgumentCaptor<AdvertisingConversionCallback> insertCaptor =
            ArgumentCaptor.forClass(AdvertisingConversionCallback.class);
        verify(callbackMapper).insert(insertCaptor.capture());
        AdvertisingConversionCallback callback = insertCaptor.getValue();
        assertThat(callback.status).isEqualTo("PENDING");
        assertThat(callback.nextRetryAt).isNotNull();

        when(callbackMapper.findByEventKeyForUpdate(command.eventKey())).thenReturn(callback);
        service.markPendingRetry(command.eventKey());

        assertThat(callback.status).isEqualTo("PENDING_RETRY");
        assertThat(callback.attemptCount).isEqualTo(1);
        verify(callbackMapper).updateById(callback);
    }

    @Test
    void revokesPreviouslyAttributedReservationWithoutChangingClickFacts() {
        LocalDateTime revokedAt = LocalDateTime.of(2026, 8, 3, 12, 0);
        AdvertisingConversionService.ConversionCommand command = new AdvertisingConversionService.ConversionCommand(
            "RESERVATION_STATUS:7101:PAID:CANCELED:1", "RESERVATION_REVOKED", RESERVATION_ID,
            USER_ID, 7201L, "CANCELED", revokedAt);
        AdvertisingConversionCallback callback = new AdvertisingConversionCallback();
        AdvertisingAttribution attribution = new AdvertisingAttribution();
        attribution.id = 3L;
        attribution.status = AdvertisingConversionService.ATTRIBUTED;
        attribution.clickEventId = 91L;
        when(privacyService.policyVersion(USER_ID)).thenReturn(POLICY);
        when(attributionMapper.findByReservationIdForUpdate(RESERVATION_ID)).thenReturn(attribution);
        doAnswer(invocation -> {
            AdvertisingConversionCallback inserted = invocation.getArgument(0);
            inserted.id = 4L;
            return 1;
        }).when(callbackMapper).insert(any(AdvertisingConversionCallback.class));

        service.accept(command);

        assertThat(attribution.status).isEqualTo(AdvertisingConversionService.REVOKED);
        assertThat(attribution.reasonCode).isEqualTo("RESERVATION_CANCELED");
        assertThat(attribution.clickEventId).isEqualTo(91L);
        verify(attributionMapper).updateById(attribution);
    }

    @Test
    void convertsUnexpectedFactFailureIntoPendingRetry() {
        AdvertisingConversionService.ConversionCommand command = submission(LocalDateTime.of(2026, 8, 3, 12, 0));
        when(privacyService.policyVersion(USER_ID)).thenReturn(POLICY);
        when(privacyService.lockForAdvertisingFact(USER_ID))
            .thenThrow(new IllegalStateException("database unavailable"));
        doAnswer(invocation -> {
            AdvertisingConversionCallback callback = invocation.getArgument(0);
            callback.id = 5L;
            return 1;
        }).when(callbackMapper).insert(any(AdvertisingConversionCallback.class));

        service.accept(command);

        ArgumentCaptor<AdvertisingConversionCallback> callbackCaptor =
            ArgumentCaptor.forClass(AdvertisingConversionCallback.class);
        verify(callbackMapper).updateById(callbackCaptor.capture());
        assertThat(callbackCaptor.getValue().status).isEqualTo("PENDING_RETRY");
        assertThat(callbackCaptor.getValue().resultCode).isEqualTo("ADVERTISING_CALLBACK_FAILED");
        assertThat(callbackCaptor.getValue().attemptCount).isEqualTo(1);
    }

    @Test
    void lateSubmissionCannotRestoreAReservationAfterANewerRevocationWasPersisted() {
        LocalDateTime revokedAt = LocalDateTime.of(2026, 8, 3, 12, 1);
        AdvertisingConversionService.ConversionCommand revoked = new AdvertisingConversionService.ConversionCommand(
            "RESERVATION_STATUS:7101:702", "RESERVATION_REVOKED", RESERVATION_ID,
            USER_ID, 7201L, "CANCELED", revokedAt);
        AdvertisingConversionService.ConversionCommand lateSubmission = submission(revokedAt.minusMinutes(1));
        when(privacyService.policyVersion(USER_ID)).thenReturn(POLICY);
        doAnswer(invocation -> {
            AdvertisingConversionCallback callback = invocation.getArgument(0);
            callback.id = callback.id == null ? 6L : callback.id;
            return 1;
        }).when(callbackMapper).insert(any(AdvertisingConversionCallback.class));

        service.accept(revoked);

        ArgumentCaptor<AdvertisingAttribution> attributionCaptor =
            ArgumentCaptor.forClass(AdvertisingAttribution.class);
        verify(attributionMapper).insert(attributionCaptor.capture());
        AdvertisingAttribution attribution = attributionCaptor.getValue();
        when(attributionMapper.findByReservationIdForUpdate(RESERVATION_ID)).thenReturn(attribution);

        service.accept(lateSubmission);

        assertThat(attribution.status).isEqualTo(AdvertisingConversionService.GAP);
        assertThat(attribution.conversionOccurredAt).isEqualTo(revokedAt);
        assertThat(attribution.sourceEventKey).isEqualTo(revoked.eventKey());
        ArgumentCaptor<AdvertisingConversionCallback> callbackCaptor =
            ArgumentCaptor.forClass(AdvertisingConversionCallback.class);
        verify(callbackMapper, org.mockito.Mockito.atLeastOnce()).updateById(callbackCaptor.capture());
        assertThat(callbackCaptor.getAllValues().get(callbackCaptor.getAllValues().size() - 1).resultCode)
            .isEqualTo("STALE_EVENT_IGNORED");
        verify(privacyService, org.mockito.Mockito.never()).lockForAdvertisingFact(USER_ID);
    }

    @Test
    void lateRevocationCannotUndoTheNewerSubmissionFact() {
        LocalDateTime submittedAt = LocalDateTime.of(2026, 8, 3, 12, 1);
        AdvertisingConversionService.ConversionCommand submitted = submission(submittedAt);
        AdvertisingConversionService.ConversionCommand lateRevocation = new AdvertisingConversionService.ConversionCommand(
            "RESERVATION_STATUS:7101:703", "RESERVATION_REVOKED", RESERVATION_ID,
            USER_ID, 7201L, "CANCELED", submittedAt.minusMinutes(1));
        when(privacyService.policyVersion(USER_ID)).thenReturn(POLICY);
        when(privacyService.lockForAdvertisingFact(USER_ID)).thenReturn(true);
        when(eventMapper.findLatestAcceptedClickForAttribution(any(), any(), any()))
            .thenReturn(click(submittedAt.minusMinutes(2)));
        doAnswer(invocation -> {
            AdvertisingConversionCallback callback = invocation.getArgument(0);
            callback.id = 7L;
            return 1;
        }).when(callbackMapper).insert(any(AdvertisingConversionCallback.class));

        service.accept(submitted);

        ArgumentCaptor<AdvertisingAttribution> attributionCaptor =
            ArgumentCaptor.forClass(AdvertisingAttribution.class);
        verify(attributionMapper).insert(attributionCaptor.capture());
        AdvertisingAttribution attribution = attributionCaptor.getValue();
        when(attributionMapper.findByReservationIdForUpdate(RESERVATION_ID)).thenReturn(attribution);

        service.accept(lateRevocation);

        assertThat(attribution.status).isEqualTo(AdvertisingConversionService.ATTRIBUTED);
        assertThat(attribution.conversionOccurredAt).isEqualTo(submittedAt);
        assertThat(attribution.sourceEventKey).isEqualTo(submitted.eventKey());
        ArgumentCaptor<AdvertisingConversionCallback> callbackCaptor =
            ArgumentCaptor.forClass(AdvertisingConversionCallback.class);
        verify(callbackMapper, org.mockito.Mockito.atLeastOnce()).updateById(callbackCaptor.capture());
        assertThat(callbackCaptor.getAllValues().get(callbackCaptor.getAllValues().size() - 1).resultCode)
            .isEqualTo("STALE_EVENT_IGNORED");
    }

    private AdvertisingConversionService.ConversionCommand submission(LocalDateTime occurredAt) {
        return new AdvertisingConversionService.ConversionCommand(
            "RESERVATION_SUBMITTED:" + RESERVATION_ID,
            "RESERVATION_SUBMITTED",
            RESERVATION_ID,
            USER_ID,
            7201L,
            "PENDING_REVIEW",
            occurredAt);
    }

    private AdvertisingEvent click(LocalDateTime occurredAt) {
        AdvertisingEvent click = new AdvertisingEvent();
        click.id = 91L;
        click.eventType = "CLICK";
        click.userId = USER_ID;
        click.decisionId = 81L;
        click.advertiserId = 82L;
        click.campaignId = 83L;
        click.creativeVersionId = 84L;
        click.policyVersion = POLICY;
        click.resultStatus = "ACCEPTED";
        click.occurredAt = occurredAt;
        return click;
    }
}
