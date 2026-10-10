package com.example.venue.community.activity.schedule;

import com.example.venue.community.activity.service.CommunityActivityService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ActivityExpiryScheduler {
    private final CommunityActivityService activityService;

    public ActivityExpiryScheduler(CommunityActivityService activityService) {
        this.activityService = activityService;
    }

    @Scheduled(initialDelayString = "${app.community.activity.expiry.initial-delay-ms:60000}",
        fixedDelayString = "${app.community.activity.expiry.fixed-delay-ms:60000}")
    public void expireDue() {
        activityService.expireDue(100);
    }
}
