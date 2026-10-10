package com.example.venue.community.activity.schedule;

import com.example.venue.community.activity.service.ActivityNotificationDispatcher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ActivityNotificationScheduler {
    private final ActivityNotificationDispatcher dispatcher;

    public ActivityNotificationScheduler(ActivityNotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(initialDelayString = "${app.community.activity.notification.initial-delay-ms:30000}",
        fixedDelayString = "${app.community.activity.notification.fixed-delay-ms:30000}")
    public void dispatchDue() {
        dispatcher.dispatchDue(100);
    }
}
