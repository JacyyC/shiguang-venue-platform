package com.example.venue.community.activity.service;

import com.example.venue.reservation.service.ReservationCommitmentChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class ActivityReservationChangeListener {
    private static final Logger log = LoggerFactory.getLogger(ActivityReservationChangeListener.class);
    private final ActivityReservationPropagationService propagationService;

    public ActivityReservationChangeListener(ActivityReservationPropagationService propagationService) {
        this.propagationService = propagationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChange(ReservationCommitmentChangedEvent event) {
        try {
            propagationService.propagate(event.reservationId(), event.reasonCode());
        } catch (RuntimeException exception) {
            log.warn("预约承诺变更传播失败 reservationRef={} reasonCode={} failureClass={}",
                reservationRef(event.reservationId()), event.reasonCode(), exception.getClass().getSimpleName());
        }
    }

    private String reservationRef(Long reservationId) {
        if (reservationId == null) {
            return "null";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(("reservation:" + reservationId).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("RSV-");
            for (int index = 0; index < 8; index++) {
                value.append(String.format("%02x", digest[index]));
            }
            return value.toString().toUpperCase();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
