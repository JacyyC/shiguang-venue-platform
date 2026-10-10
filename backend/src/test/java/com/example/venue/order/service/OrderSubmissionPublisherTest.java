package com.example.venue.order.service;

import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.entity.OrderStatusLog;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OrderSubmissionPublisherTest {
    @Test
    void submittedEventUsesThePersistedOrderStatusLogIdentity() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        OrderSubmissionPublisher publisher = new OrderSubmissionPublisher(eventPublisher);
        BizOrder order = new BizOrder();
        order.id = 41L;
        order.reservationId = 99L;
        order.userId = 7L;
        order.venueId = 8L;
        order.status = "PENDING_PAYMENT";
        order.createTime = LocalDateTime.of(2026, 8, 3, 12, 0);

        OrderStatusLog log = new OrderStatusLog();
        log.id = 801L;
        log.createTime = LocalDateTime.of(2026, 8, 3, 12, 1);

        publisher.publish(order, log);

        var captor = org.mockito.ArgumentCaptor.forClass(OrderSubmittedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventKey()).isEqualTo("ORDER_SUBMITTED:41:801");
        assertThat(captor.getValue().occurredAt()).isEqualTo(log.createTime);
    }

    @Test
    void compatibilityEntryLeavesOccurredAtUnknownInsteadOfUsingAnEpoch() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        OrderSubmissionPublisher publisher = new OrderSubmissionPublisher(eventPublisher);
        BizOrder order = new BizOrder();
        order.id = 41L;
        order.reservationId = 99L;
        order.userId = 7L;
        order.venueId = 8L;
        order.status = "PENDING_PAYMENT";

        publisher.publish(order);

        var captor = org.mockito.ArgumentCaptor.forClass(OrderSubmittedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().occurredAt()).isNull();
        assertThat(captor.getValue().eventKey()).isEqualTo("ORDER_SUBMITTED:41");
    }
}
