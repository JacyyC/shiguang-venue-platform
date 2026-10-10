package com.example.venue.notice.service;

import com.example.venue.notice.entity.NoticeMessage;
import com.example.venue.notice.mapper.NoticeMessageMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 实时消息发布器，通过 SseConnectionManager 向在线用户推送 NEW_MESSAGE 和 UNREAD_COUNT 事件。
 * 用户不在线时静默跳过（消息已入库，轮询兜底）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SseRealtimeMessagePublisher implements RealtimeMessagePublisher {
    private static final Logger log = LoggerFactory.getLogger(SseRealtimeMessagePublisher.class);

    private final SseConnectionManager connectionManager;
    private final NoticeMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public SseRealtimeMessagePublisher(SseConnectionManager connectionManager,
                                       NoticeMessageMapper messageMapper,
                                       ObjectMapper objectMapper) {
        this.connectionManager = connectionManager;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public String channel() {
        return "SSE";
    }

    @Override
    public void publish(NoticeMessage message) {
        if (message == null || message.userId == null) {
            return;
        }
        // 推送 NEW_MESSAGE 事件（用户不在线时 send 内部静默跳过）
        Map<String, Object> messageData = new LinkedHashMap<>();
        messageData.put("type", "NEW_MESSAGE");
        messageData.put("id", message.id);
        messageData.put("title", message.title);
        messageData.put("messageType", message.type);
        messageData.put("businessId", message.businessId);
        connectionManager.send(message.userId, toJson(messageData));

        // 推送 UNREAD_COUNT 事件
        long unread = messageMapper.unreadCount(message.userId);
        Map<String, Object> countData = new LinkedHashMap<>();
        countData.put("type", "UNREAD_COUNT");
        countData.put("unreadCount", unread);
        connectionManager.send(message.userId, toJson(countData));
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize SSE event data: {}", ex.getMessage());
            return "{}";
        }
    }
}
