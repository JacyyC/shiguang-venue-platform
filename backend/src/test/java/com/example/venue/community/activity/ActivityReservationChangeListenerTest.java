package com.example.venue.community.activity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.venue.community.activity.service.ActivityReservationChangeListener;
import com.example.venue.community.activity.service.ActivityReservationPropagationService;
import com.example.venue.reservation.service.ReservationCommitmentChangedEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ActivityReservationChangeListenerTest {
    @Mock
    private ActivityReservationPropagationService propagationService;

    @Test
    void propagationFailureLogsOnlyShortReferenceAndFailureClass() {
        ActivityReservationChangeListener listener = new ActivityReservationChangeListener(propagationService);
        doThrow(new IllegalStateException("database connection contains reservation 101"))
            .when(propagationService).propagate(101L, "RESERVATION_RELEASED");
        Logger logger = (Logger) LoggerFactory.getLogger(ActivityReservationChangeListener.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        try {
            listener.onChange(new ReservationCommitmentChangedEvent(101L, "RELEASED", "RESERVATION_RELEASED"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }

        verify(propagationService).propagate(101L, "RESERVATION_RELEASED");
        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).doesNotContain("101").doesNotContain("database connection");
        assertThat(message).contains("reservationRef=RSV-")
            .contains("reasonCode=RESERVATION_RELEASED")
            .contains("failureClass=IllegalStateException");
    }
}
