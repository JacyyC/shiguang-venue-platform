package com.example.venue.order.service;

import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.entity.OrderStatusLog;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class OrderSubmissionPublisher {
    private final ApplicationEventPublisher eventPublisher;

    public OrderSubmissionPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void publish(BizOrder order) {
        publish(order, null);
    }

    public void publish(BizOrder order, OrderStatusLog statusLog) {
        if (order == null || order.id == null || order.reservationId == null) {
            return;
        }
        LocalDateTime occurredAt = statusLog != null && statusLog.createTime != null
            ? statusLog.createTime
            : order.createTime;
        String eventKey = statusLog != null && statusLog.id != null
            ? "ORDER_SUBMITTED:" + order.id + ":" + statusLog.id
            : "ORDER_SUBMITTED:" + order.id;
        eventPublisher.publishEvent(new OrderSubmittedEvent(
            order.id,
            order.reservationId,
            order.userId,
            order.venueId,
            eventKey,
            order.status,
            occurredAt
        ));
    }
}
