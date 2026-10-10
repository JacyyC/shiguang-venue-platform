package com.example.venue.reservation.service;

import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.entity.ReservationStatusLog;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Component
public class ReservationLifecycleChangePublisher {
    private final ApplicationEventPublisher eventPublisher;

    public ReservationLifecycleChangePublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void publishSubmitted(Reservation reservation) {
        publishSubmitted(reservation, null);
    }

    public void publishSubmitted(Reservation reservation, ReservationStatusLog statusLog) {
        if (reservation == null || reservation.id == null) {
            return;
        }
        LocalDateTime occurredAt = occurredAt(statusLog, reservation);
        eventPublisher.publishEvent(new ReservationLifecycleChangedEvent(
            reservation.id,
            reservation.userId,
            reservation.venueId,
            statusLogKey(reservation.id, statusLog, null, reservation.status,
                "RESERVATION_SUBMITTED", "USER"),
            "SUBMITTED",
            null,
            reservation.status,
            "RESERVATION_SUBMITTED",
            "USER",
            occurredAt
        ));
    }

    public void publishStatusChange(Reservation reservation,
                                    String fromStatus,
                                    String toStatus,
                                    String reasonCode,
                                    String source) {
        publishStatusChange(reservation, null, fromStatus, toStatus, reasonCode, source);
    }

    public void publishStatusChange(Reservation reservation, ReservationStatusLog statusLog) {
        publishStatusChange(reservation, statusLog,
            statusLog == null ? null : statusLog.fromStatus,
            statusLog == null ? reservation == null ? null : reservation.status : statusLog.toStatus,
            statusLog == null ? null : statusLog.reason,
            statusLog == null ? null : statusLog.source);
    }

    private void publishStatusChange(Reservation reservation,
                                     ReservationStatusLog statusLog,
                                     String fromStatus,
                                     String toStatus,
                                     String reasonCode,
                                     String source) {
        if (reservation == null || reservation.id == null || toStatus == null) {
            return;
        }
        LocalDateTime occurredAt = occurredAt(statusLog, reservation);
        eventPublisher.publishEvent(new ReservationLifecycleChangedEvent(
            reservation.id,
            reservation.userId,
            reservation.venueId,
            statusLogKey(reservation.id, statusLog, fromStatus, toStatus, reasonCode, source),
            "STATUS_CHANGED",
            fromStatus,
            toStatus,
            reasonCode,
            source,
            occurredAt
        ));
    }

    private LocalDateTime occurredAt(ReservationStatusLog statusLog, Reservation reservation) {
        if (statusLog != null && statusLog.createTime != null) {
            return statusLog.createTime;
        }
        if (reservation.createTime != null) {
            return reservation.createTime;
        }
        if (reservation.updateTime != null) {
            return reservation.updateTime;
        }
        return null;
    }

    private String statusLogKey(Long reservationId,
                                ReservationStatusLog statusLog,
                                String fromStatus,
                                String toStatus,
                                String reasonCode,
                                String source) {
        if (statusLog != null && statusLog.id != null) {
            return "RESERVATION_STATUS:" + reservationId + ":" + statusLog.id;
        }
        if (fromStatus == null && "SUBMITTED".equals(toStatus)) {
            return "RESERVATION_SUBMITTED:" + reservationId;
        }
        String canonical = String.join("|", nullSafe(reservationId), nullSafe(fromStatus),
            nullSafe(toStatus), nullSafe(reasonCode), nullSafe(source));
        return "RESERVATION_STATUS:" + reservationId + ":" + sha256(canonical).substring(0, 32);
    }

    private String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
