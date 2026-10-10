package com.example.venue.notice.service;

import com.example.venue.notice.provider.NotificationDeliveryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Profile("acceptance")
public class AcceptanceNotificationDeliveryFaultHook implements NotificationDeliveryFaultHook {
    private static final Logger log = LoggerFactory.getLogger(AcceptanceNotificationDeliveryFaultHook.class);

    private final NotificationAcceptanceProperties properties;
    private final PauseStrategy pauseStrategy;

    @Autowired
    public AcceptanceNotificationDeliveryFaultHook(NotificationAcceptanceProperties properties) {
        this(properties, duration -> Thread.sleep(duration.toMillis()));
    }

    public AcceptanceNotificationDeliveryFaultHook(NotificationAcceptanceProperties properties,
                                                   PauseStrategy pauseStrategy) {
        this.properties = properties;
        this.pauseStrategy = pauseStrategy;
    }

    @Override
    public void afterProviderResult(Long outboxId, NotificationDeliveryResult result) {
        long pauseMs = properties.getPauseAfterProviderMs();
        if (pauseMs <= 0) {
            return;
        }
        log.info("Acceptance fault hook pausing outbox {} for {} ms before terminal state", outboxId, pauseMs);
        try {
            pauseStrategy.pause(Duration.ofMillis(pauseMs));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Acceptance fault hook interrupted before terminal state", ex);
        }
    }

    @FunctionalInterface
    public interface PauseStrategy {
        void pause(Duration duration) throws InterruptedException;
    }
}
