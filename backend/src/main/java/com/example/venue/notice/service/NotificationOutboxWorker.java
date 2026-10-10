package com.example.venue.notice.service;

import com.example.venue.notice.mapper.NoticeOutboxMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class NotificationOutboxWorker {
    private final NoticeOutboxMapper outboxMapper;
    private final NotificationOutboxDeliveryService deliveryService;
    private final NotificationOutboxProperties properties;
    private final Clock clock;

    @Autowired
    public NotificationOutboxWorker(NoticeOutboxMapper outboxMapper,
                                    NotificationOutboxDeliveryService deliveryService,
                                    NotificationOutboxProperties properties) {
        this(outboxMapper, deliveryService, properties, Clock.systemDefaultZone());
    }

    public NotificationOutboxWorker(NoticeOutboxMapper outboxMapper,
                                    NotificationOutboxDeliveryService deliveryService,
                                    NotificationOutboxProperties properties,
                                    Clock clock) {
        this.outboxMapper = outboxMapper;
        this.deliveryService = deliveryService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.notice.outbox.poll-delay-ms:5000}")
    public void poll() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime staleBefore = now.minusSeconds(properties.getLeaseSeconds());
        List<Long> dueIds = outboxMapper.findDueIds(now, staleBefore, properties.getBatchSize());
        if (dueIds == null) {
            return;
        }
        for (Long outboxId : dueIds) {
            deliveryService.deliver(outboxId, staleBefore);
        }
    }
}
