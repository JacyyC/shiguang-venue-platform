package com.example.venue.notice;

import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.service.NotificationOutboxDeliveryService;
import com.example.venue.notice.service.NotificationOutboxProperties;
import com.example.venue.notice.service.NotificationOutboxWorker;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class NotificationOutboxWorkerTest {
    @Test
    void pollFindsBoundedDueIdsAndDeliversEachWithOneLeaseBoundary() {
        NoticeOutboxMapper outboxMapper = mock(NoticeOutboxMapper.class);
        NotificationOutboxDeliveryService deliveryService = mock(NotificationOutboxDeliveryService.class);
        NotificationOutboxProperties properties = new NotificationOutboxProperties();
        properties.setBatchSize(2);
        properties.setLeaseSeconds(60);
        Clock clock = Clock.fixed(
            Instant.parse("2026-07-10T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 10, 0);
        LocalDateTime staleBefore = now.minusSeconds(60);
        when(outboxMapper.findDueIds(now, staleBefore, 2)).thenReturn(List.of(41L, 42L));
        NotificationOutboxWorker worker = new NotificationOutboxWorker(
            outboxMapper, deliveryService, properties, clock);

        worker.poll();

        verify(outboxMapper).findDueIds(now, staleBefore, 2);
        var ordered = inOrder(deliveryService);
        ordered.verify(deliveryService).deliver(41L, staleBefore);
        ordered.verify(deliveryService).deliver(42L, staleBefore);
    }

    @Test
    void staleSendingEmailIsOfferedAgainForHonestAtLeastOnceRecovery() {
        NoticeOutboxMapper outboxMapper = mock(NoticeOutboxMapper.class);
        NotificationOutboxDeliveryService deliveryService = mock(NotificationOutboxDeliveryService.class);
        NotificationOutboxProperties properties = new NotificationOutboxProperties();
        properties.setBatchSize(1);
        properties.setLeaseSeconds(60);
        Clock clock = Clock.fixed(
            Instant.parse("2026-07-10T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 10, 0);
        LocalDateTime staleBefore = now.minusSeconds(60);
        when(outboxMapper.findDueIds(now, staleBefore, 1)).thenReturn(List.of(41L));
        NotificationOutboxWorker worker = new NotificationOutboxWorker(
            outboxMapper, deliveryService, properties, clock);

        worker.poll();
        worker.poll();

        verify(outboxMapper, times(2)).findDueIds(now, staleBefore, 1);
        verify(deliveryService, times(2)).deliver(41L, staleBefore);
    }
}
