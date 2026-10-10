package com.example.venue.community.activity.service;

import com.example.venue.community.activity.entity.CommunityActivityNotificationTask;
import com.example.venue.community.activity.mapper.CommunityActivityNotificationTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ActivityNotificationTaskStateService {
    private static final int MAX_ATTEMPTS = 8;
    private static final int STALE_SECONDS = 120;

    private final CommunityActivityNotificationTaskMapper mapper;

    public ActivityNotificationTaskStateService(CommunityActivityNotificationTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> dueIds(int requestedLimit) {
        LocalDateTime now = LocalDateTime.now();
        return mapper.findDueIds(now, now.minusSeconds(STALE_SECONDS), Math.min(Math.max(requestedLimit, 1), 100));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommunityActivityNotificationTask claim(Long taskId) {
        CommunityActivityNotificationTask task = mapper.selectById(taskId);
        if (task == null || task.attemptCount == null
            || "DELIVERED".equals(task.status) || "FAILED".equals(task.status)) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusSeconds(STALE_SECONDS);
        if (task.attemptCount >= MAX_ATTEMPTS) {
            if ("DISPATCHING".equals(task.status) && task.claimedAt != null
                && task.claimedAt.isBefore(staleBefore)) {
                mapper.markStaleExhausted(task.id, task.attemptCount, "NOTICE_HANDOFF_FAILED",
                    "站内通知暂时不可用，已达到重试上限", now, staleBefore);
            }
            return null;
        }
        if (mapper.claim(task.id, task.attemptCount, now, staleBefore) != 1) {
            return null;
        }
        task.status = "DISPATCHING";
        task.attemptCount = task.attemptCount + 1;
        task.claimedAt = now;
        return task;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delivered(CommunityActivityNotificationTask task) {
        mapper.markDelivered(task.id, task.attemptCount, LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failedAttempt(CommunityActivityNotificationTask task) {
        LocalDateTime now = LocalDateTime.now();
        if (task.attemptCount >= MAX_ATTEMPTS) {
            mapper.markFailed(task.id, task.attemptCount, "NOTICE_HANDOFF_FAILED",
                "站内通知暂时不可用，已达到重试上限", now);
            return;
        }
        long delaySeconds = Math.min(3600L, 30L * (1L << Math.min(task.attemptCount - 1, 6)));
        mapper.markRetry(task.id, task.attemptCount, "NOTICE_HANDOFF_FAILED",
            "站内通知暂时不可用，将自动重试", now.plusSeconds(delaySeconds), now);
    }
}
