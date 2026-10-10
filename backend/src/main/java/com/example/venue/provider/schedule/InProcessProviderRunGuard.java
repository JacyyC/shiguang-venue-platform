package com.example.venue.provider.schedule;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InProcessProviderRunGuard {
    private final Set<Lane> runningLanes = ConcurrentHashMap.newKeySet();

    public boolean tryAcquire(Lane lane) {
        return runningLanes.add(Objects.requireNonNull(lane, "lane"));
    }

    public void release(Lane lane) {
        runningLanes.remove(Objects.requireNonNull(lane, "lane"));
    }

    public enum Lane {
        WEATHER,
        RISK,
        METRICS,
        RETENTION,
        STORED_VALUE_RECONCILIATION
    }
}
