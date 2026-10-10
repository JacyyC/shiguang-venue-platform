package com.example.venue.notice.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.notice.acceptance")
public class NotificationAcceptanceProperties {
    private boolean fakeProviderEnabled;
    private long pauseAfterProviderMs;

    public boolean isFakeProviderEnabled() {
        return fakeProviderEnabled;
    }

    public void setFakeProviderEnabled(boolean fakeProviderEnabled) {
        this.fakeProviderEnabled = fakeProviderEnabled;
    }

    public long getPauseAfterProviderMs() {
        return pauseAfterProviderMs;
    }

    public void setPauseAfterProviderMs(long pauseAfterProviderMs) {
        this.pauseAfterProviderMs = pauseAfterProviderMs;
    }
}
