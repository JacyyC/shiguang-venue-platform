package com.example.venue.community.activity.service;

import com.example.venue.community.activity.entity.CommunityActivityNotificationTask;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.notice.service.NoticeService;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ActivityNotificationDispatcher {
    private final ActivityNotificationTaskStateService stateService;
    private final NoticeService noticeService;

    public ActivityNotificationDispatcher(ActivityNotificationTaskStateService stateService,
                                          NoticeService noticeService) {
        this.stateService = stateService;
        this.noticeService = noticeService;
    }

    public void dispatch(Long taskId) {
        CommunityActivityNotificationTask task = stateService.claim(taskId);
        if (task == null) {
            return;
        }
        try {
            noticeService.createBusinessNotification(new NotificationEvent(
                task.eventType,
                task.eventKey,
                task.recipientUserId,
                NotificationRecipientKind.USER,
                task.title,
                task.content,
                task.businessType,
                task.businessId,
                Map.of()
            ));
            stateService.delivered(task);
        } catch (RuntimeException exception) {
            stateService.failedAttempt(task);
        }
    }

    public int dispatchDue(int limit) {
        var ids = stateService.dueIds(limit);
        ids.forEach(this::dispatch);
        return ids.size();
    }
}
