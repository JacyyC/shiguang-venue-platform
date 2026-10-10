package com.example.venue.notice.service;

import com.example.venue.notice.entity.NoticeMessage;

public interface RealtimeMessagePublisher {
    String channel();

    void publish(NoticeMessage message);
}
