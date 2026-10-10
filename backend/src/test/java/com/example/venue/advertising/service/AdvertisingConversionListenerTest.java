package com.example.venue.advertising.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.venue.order.service.OrderSubmittedEvent;
import com.example.venue.reservation.service.ReservationLifecycleChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AdvertisingConversionListenerTest {
    @Mock private AdvertisingConversionService conversionService;
    @Mock private TaskExecutor taskExecutor;
    @InjectMocks private AdvertisingConversionListener listener;
    private ListAppender<ILoggingEvent> logAppender;
    private Level previousLoggerLevel;

    @BeforeEach
    void executeQueuedTasksImmediately() {
        lenient().doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));
    }

    @AfterEach
    void detachLogAppender() {
        if (logAppender != null) {
            Logger logger = (Logger) LoggerFactory.getLogger(AdvertisingConversionListener.class);
            logger.detachAppender(logAppender);
            logger.setLevel(previousLoggerLevel);
        }
    }

    @Test
    void conversionFailureAfterReservationCommitDoesNotEscapeTheListener() {
        doThrow(new IllegalStateException("advertising database unavailable"))
            .when(conversionService).accept(any(AdvertisingConversionService.ConversionCommand.class));

        assertThatCode(() -> listener.onReservationChange(new ReservationLifecycleChangedEvent(
            101L, 201L, 301L, "RESERVATION_SUBMITTED:101", "SUBMITTED", null,
            "PENDING_REVIEW", "RESERVATION_SUBMITTED", "USER", LocalDateTime.now())))
            .doesNotThrowAnyException();

        verify(conversionService).accept(any(AdvertisingConversionService.ConversionCommand.class));
        verify(conversionService).persistPending(any(AdvertisingConversionService.ConversionCommand.class));
        verify(conversionService).markPendingRetry(eq("RESERVATION_SUBMITTED:101"));
    }

    @Test
    void rejectedQueueLeavesDurablePendingFactWithoutBorrowingAnotherConnection() {
        doThrow(new RejectedExecutionException("executor stopped"))
            .when(taskExecutor).execute(any(Runnable.class));

        listener.onReservationChange(new ReservationLifecycleChangedEvent(
            101L, 201L, 301L, "RESERVATION_STATUS:101:501", "STATUS_CHANGED", "PAID",
            "CANCELED", "USER_CANCEL", "USER", LocalDateTime.of(2026, 8, 3, 12, 0)));

        verify(conversionService).persistPending(any(AdvertisingConversionService.ConversionCommand.class));
        verify(conversionService, never()).markPendingRetry(any());
    }

    @Test
    void orderSubmissionAlsoUsesTheIndependentAfterCommitPath() {
        listener.onOrderSubmitted(new OrderSubmittedEvent(
            401L, 101L, 201L, 301L, "ORDER_SUBMITTED:401", "PENDING_PAYMENT", LocalDateTime.now()));

        verify(conversionService).persistPending(any(AdvertisingConversionService.ConversionCommand.class));
        verify(conversionService).accept(any(AdvertisingConversionService.ConversionCommand.class));
    }

    @Test
    void failureLogUsesReferenceDigestsInsteadOfBusinessIdentifiers() {
        Logger logger = (Logger) LoggerFactory.getLogger(AdvertisingConversionListener.class);
        previousLoggerLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
        doThrow(new RejectedExecutionException("executor stopped"))
            .when(taskExecutor).execute(any(Runnable.class));

        listener.onReservationChange(new ReservationLifecycleChangedEvent(
            101L, 201L, 301L, "RESERVATION_STATUS:101:501", "STATUS_CHANGED", "PAID",
            "CANCELED", "USER_CANCEL", "USER", LocalDateTime.of(2026, 8, 3, 12, 0)));

        String message = logAppender.list.get(0).getFormattedMessage();
        assertThat(message).doesNotContain("101").doesNotContain("RESERVATION_STATUS:101:501");
        assertThat(message).contains("reservationRef=").contains("eventRef=");
    }
}
