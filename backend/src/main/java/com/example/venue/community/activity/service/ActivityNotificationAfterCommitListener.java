package com.example.venue.community.activity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ActivityNotificationAfterCommitListener {
    private static final Logger log = LoggerFactory.getLogger(ActivityNotificationAfterCommitListener.class);
    private final ActivityNotificationDispatcher dispatcher;
    private final TaskExecutor taskExecutor;

    public ActivityNotificationAfterCommitListener(
        ActivityNotificationDispatcher dispatcher,
        @Qualifier("activityNotificationTaskExecutor") TaskExecutor taskExecutor
    ) {
        this.dispatcher = dispatcher;
        this.taskExecutor = taskExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onQueued(ActivityNotificationQueuedEvent event) {
        try {
            // AFTER_COMMIT runs before the original transaction releases its connection. Dispatching
            // inline can exhaust a bounded pool when many transactions commit together.
            taskExecutor.execute(() -> dispatchSafely(event.taskId()));
        } catch (RuntimeException exception) {
            log.warn("活动通知提交后调度失败 taskId={}", event.taskId(), exception);
        }
    }

    private void dispatchSafely(Long taskId) {
        try {
            dispatcher.dispatch(taskId);
        } catch (RuntimeException exception) {
            log.warn("活动通知提交后派发失败 taskId={}", taskId, exception);
        }
    }
}
