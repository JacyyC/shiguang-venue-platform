package com.example.venue.community.activity.service;

import com.example.venue.community.activity.entity.CommunityActivityNotificationTask;
import com.example.venue.community.activity.mapper.CommunityActivityNotificationTaskMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ActivityNotificationService {
    private final CommunityActivityNotificationTaskMapper mapper;
    private final ApplicationEventPublisher eventPublisher;

    public ActivityNotificationService(CommunityActivityNotificationTaskMapper mapper,
                                       ApplicationEventPublisher eventPublisher) {
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
    }

    public void enqueue(String eventType,
                        String eventKey,
                        Long recipientUserId,
                        String title,
                        String content,
                        String businessType,
                        Long businessId) {
        if (recipientUserId == null) {
            return;
        }
        CommunityActivityNotificationTask task = new CommunityActivityNotificationTask();
        task.eventType = bounded(eventType, 64);
        task.eventKey = bounded(eventKey, 128);
        task.recipientUserId = recipientUserId;
        task.title = bounded(title, 200);
        task.content = bounded(content, 1000);
        task.businessType = bounded(businessType, 32);
        task.businessId = businessId;
        task.status = "PENDING";
        task.attemptCount = 0;
        task.createTime = LocalDateTime.now();
        task.updateTime = task.createTime;
        try {
            mapper.insert(task);
        } catch (DuplicateKeyException duplicate) {
            return;
        }
        eventPublisher.publishEvent(new ActivityNotificationQueuedEvent(task.id));
    }

    private String bounded(String value, int limit) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("活动通知任务字段不能为空");
        }
        String normalized = value.trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }
}
