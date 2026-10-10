package com.example.venue.advertising.service;

import com.example.venue.order.service.OrderSubmittedEvent;
import com.example.venue.reservation.service.ReservationLifecycleChangedEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.HexFormat;

@Component
public class AdvertisingConversionListener {
    private static final Logger log = LoggerFactory.getLogger(AdvertisingConversionListener.class);
    private static final Set<String> REVOKING_STATUSES = Set.of(
        "REJECTED", "CANCELED", "EXPIRED", "REFUNDING", "REFUNDED");

    private final AdvertisingConversionService conversionService;
    private final TaskExecutor taskExecutor;

    public AdvertisingConversionListener(
        AdvertisingConversionService conversionService,
        @Qualifier("advertisingConversionTaskExecutor") TaskExecutor taskExecutor
    ) {
        this.conversionService = conversionService;
        this.taskExecutor = taskExecutor;
    }

    @EventListener
    @Transactional
    public void onReservationChange(ReservationLifecycleChangedEvent event) {
        if (event == null || event.reservationId() == null) {
            return;
        }
        if (event.submitted()) {
            enqueue(AdvertisingConversionService.ConversionCommand.from(event, "RESERVATION_SUBMITTED"));
        } else if (REVOKING_STATUSES.contains(event.toStatus())) {
            enqueue(AdvertisingConversionService.ConversionCommand.from(event, "RESERVATION_REVOKED"));
        }
    }

    @EventListener
    @Transactional
    public void onOrderSubmitted(OrderSubmittedEvent event) {
        if (event != null && event.reservationId() != null) {
            enqueue(AdvertisingConversionService.ConversionCommand.from(event));
        }
    }

    private void enqueue(AdvertisingConversionService.ConversionCommand command) {
        // Store the minimal outbox fact with the business transaction. Attribution
        // runs independently after commit; rollback removes both facts together.
        conversionService.persistPending(command);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(command);
                }
            });
        } else {
            dispatch(command);
        }
    }

    private void dispatch(AdvertisingConversionService.ConversionCommand command) {
        try {
            taskExecutor.execute(() -> accept(command));
        } catch (RuntimeException exception) {
            // PENDING already has next_retry_at. Do not open a second transaction
            // here: afterCommit still holds the business connection until cleanup.
            log.warn("广告回连任务入队失败 reservationRef={} eventRef={} failureType={}",
                referenceDigest(command.reservationId()), referenceDigest(command.eventKey()),
                exception.getClass().getSimpleName());
        }
    }

    private void accept(AdvertisingConversionService.ConversionCommand command) {
        try {
            conversionService.accept(command);
        } catch (RuntimeException exception) {
            recover(command, exception, "广告回连任务执行失败");
        }
    }

    private void recover(AdvertisingConversionService.ConversionCommand command,
                         RuntimeException exception,
                         String message) {
        try {
            conversionService.markPendingRetry(command.eventKey());
        } catch (RuntimeException recoveryException) {
            log.warn("{} reservationRef={} eventRef={} failureType={} recoveryType={}",
                message, referenceDigest(command.reservationId()), referenceDigest(command.eventKey()),
                exception.getClass().getSimpleName(), recoveryException.getClass().getSimpleName());
            return;
        }
        log.warn("{} reservationRef={} eventRef={} failureType={}",
            message, referenceDigest(command.reservationId()), referenceDigest(command.eventKey()),
            exception.getClass().getSimpleName());
    }

    static String referenceDigest(Object reference) {
        String value = reference == null ? "" : reference.toString();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
