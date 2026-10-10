package com.example.venue.notice;

import com.example.venue.notice.provider.NotificationDeliveryResult;
import com.example.venue.notice.service.AcceptanceNotificationDeliveryFaultHook;
import com.example.venue.notice.service.NotificationAcceptanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AcceptanceNotificationDeliveryFaultHookTest {
    @Test
    void acceptanceProfileContextSelectsProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("acceptance");
            context.register(NotificationAcceptanceProperties.class,
                AcceptanceNotificationDeliveryFaultHook.class);

            context.refresh();

            assertThat(context.getBean(AcceptanceNotificationDeliveryFaultHook.class)).isNotNull();
        }
    }

    @Test
    void pausesAfterProviderResultForConfiguredAcceptanceWindow() throws InterruptedException {
        NotificationAcceptanceProperties properties = new NotificationAcceptanceProperties();
        properties.setPauseAfterProviderMs(60_000);
        AtomicReference<Duration> pausedFor = new AtomicReference<>();
        AcceptanceNotificationDeliveryFaultHook hook = new AcceptanceNotificationDeliveryFaultHook(
            properties, pausedFor::set);

        hook.afterProviderResult(41L, accepted());

        assertThat(pausedFor.get()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void zeroPauseLeavesDeliveryUnchanged() throws InterruptedException {
        NotificationAcceptanceProperties properties = new NotificationAcceptanceProperties();
        AtomicReference<Duration> pausedFor = new AtomicReference<>();
        AcceptanceNotificationDeliveryFaultHook hook = new AcceptanceNotificationDeliveryFaultHook(
            properties, pausedFor::set);

        hook.afterProviderResult(41L, accepted());

        assertThat(pausedFor).hasValue(null);
    }

    private NotificationDeliveryResult accepted() {
        return new NotificationDeliveryResult(
            NotificationDeliveryResult.ACCEPTED, "in-app-41", false,
            null, null, "{\"status\":\"ACCEPTED\"}");
    }
}
