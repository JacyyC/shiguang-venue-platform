package com.example.venue.notice.controller;

import com.example.venue.common.BusinessException;
import com.example.venue.notice.mapper.NoticeMessageMapper;
import com.example.venue.notice.service.SseConnectionManager;
import com.example.venue.security.SecurityContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 实时通知端点，需 JWT 鉴权。
 * 端点：GET /api/notifications/sse
 * 响应：text/event-stream
 */
@RestController
public class NoticeSseController {
    private final SseConnectionManager connectionManager;
    private final NoticeMessageMapper messageMapper;

    public NoticeSseController(SseConnectionManager connectionManager, NoticeMessageMapper messageMapper) {
        this.connectionManager = connectionManager;
        this.messageMapper = messageMapper;
    }

    @GetMapping(value = "/api/notifications/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        SseEmitter emitter = new SseEmitter(SseConnectionManager.SSE_TIMEOUT);
        connectionManager.register(userId, emitter);
        // 立即推送一次当前未读数
        long unread = messageMapper.unreadCount(userId);
        connectionManager.send(userId, "{\"type\":\"UNREAD_COUNT\",\"unreadCount\":" + unread + "}");
        return emitter;
    }
}
