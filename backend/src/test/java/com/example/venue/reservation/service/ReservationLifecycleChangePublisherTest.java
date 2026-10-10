package com.example.venue.reservation.service;

import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.entity.ReservationStatusLog;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ReservationLifecycleChangePublisherTest {
    @Test
    void replaysKeepTheSameStatusLogKeyButDifferentFactsDoNotDeduplicate() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ReservationLifecycleChangePublisher publisher = new ReservationLifecycleChangePublisher(eventPublisher);
        Reservation reservation = reservation();

        ReservationStatusLog first = statusLog(701L, "PENDING_REVIEW", "APPROVED",
            LocalDateTime.of(2026, 8, 3, 12, 0));
        ReservationStatusLog different = statusLog(702L, "APPROVED", "CANCELED",
            LocalDateTime.of(2026, 8, 3, 12, 1));

        publisher.publishStatusChange(reservation, first);
        publisher.publishStatusChange(reservation, first);
        publisher.publishStatusChange(reservation, different);

        var captor = org.mockito.ArgumentCaptor.forClass(ReservationLifecycleChangedEvent.class);
        verify(eventPublisher, times(3)).publishEvent(captor.capture());
        assertThat(captor.getAllValues().get(0).eventKey())
            .isEqualTo("RESERVATION_STATUS:99:701");
        assertThat(captor.getAllValues().get(1).eventKey())
            .isEqualTo(captor.getAllValues().get(0).eventKey());
        assertThat(captor.getAllValues().get(2).eventKey())
            .isEqualTo("RESERVATION_STATUS:99:702")
            .isNotEqualTo(captor.getAllValues().get(0).eventKey());
        assertThat(captor.getAllValues().get(0).occurredAt())
            .isEqualTo(first.createTime);
    }

    @Test
    void compatibilityEntryDoesNotInventAnEpochOrPutTimeInTheFallbackKey() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ReservationLifecycleChangePublisher publisher = new ReservationLifecycleChangePublisher(eventPublisher);
        Reservation reservation = reservation();
        reservation.createTime = null;
        reservation.updateTime = null;

        publisher.publishStatusChange(reservation, "PENDING_REVIEW", "APPROVED", "TEST", "USER");

        var captor = org.mockito.ArgumentCaptor.forClass(ReservationLifecycleChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().occurredAt()).isNull();
        assertThat(captor.getValue().eventKey()).doesNotContain("1970");
    }

    private Reservation reservation() {
        Reservation reservation = new Reservation();
        reservation.id = 99L;
        reservation.userId = 7L;
        reservation.venueId = 8L;
        reservation.status = "CANCELED";
        reservation.createTime = LocalDateTime.of(2026, 8, 3, 11, 0);
        return reservation;
    }

    private ReservationStatusLog statusLog(Long id, String from, String to, LocalDateTime createTime) {
        ReservationStatusLog log = new ReservationStatusLog();
        log.id = id;
        log.reservationId = 99L;
        log.fromStatus = from;
        log.toStatus = to;
        log.reason = "TEST";
        log.source = "TEST";
        log.createTime = createTime;
        return log;
    }
}
