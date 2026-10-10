package com.example.venue.notice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 连接池管理器，维护 userId 到 SseEmitter 的映射，支持实时推送和心跳保活。
 */
@Component
public class SseConnectionManager {
    private static final Logger log = LoggerFactory.getLogger(SseConnectionManager.class);

    /** SSE 连接超时时间：30 分钟 */
    public static final long SSE_TIMEOUT = 30L * 60 * 1000;

    private final ConcurrentHashMap<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 注册用户的 SSE 连接，设置生命周期回调自动清理。
     */
    public void register(Long userId, SseEmitter emitter) {
        SseEmitter oldEmitter = emitters.get(userId);
        if (oldEmitter != null && oldEmitter != emitter) {
            try {
                oldEmitter.complete();
            } catch (Exception ignored) {
            }
        }
        emitters.put(userId, emitter);
        emitter.onCompletion(() -> emitters.remove(userId, emitter));
        emitter.onTimeout(() -> emitters.remove(userId, emitter));
        emitter.onError(ex -> emitters.remove(userId, emitter));
    }

    /**
     * 向指定用户推送事件数据，用户不在线时静默跳过。
     */
    public void send(Long userId, String eventData) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(eventData));
        } catch (IOException ex) {
            cleanupStaleEmitter(userId, emitter, ex);
        } catch (RuntimeException ex) {
            handleRuntimeSendFailure(userId, emitter, ex);
        }
    }

    /**
     * 向所有在线用户推送事件数据。
     */
    public void sendToAll(String eventData) {
        emitters.forEach((userId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().data(eventData));
            } catch (IOException ex) {
                cleanupStaleEmitter(userId, emitter, ex);
            } catch (RuntimeException ex) {
                handleRuntimeSendFailure(userId, emitter, ex);
            }
        });
    }

    /**
     * 移除用户的 SSE 连接并关闭。
     */
    public void remove(Long userId) {
        SseEmitter emitter = emitters.remove(userId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 每 30 秒发送心跳，保持连接活跃。
     */
    @Scheduled(fixedRate = 30000)
    public void heartbeat() {
        sendToAll("{\"type\":\"HEARTBEAT\"}");
    }

    private void cleanupStaleEmitter(Long userId, SseEmitter emitter, Exception ex) {
        log.debug("SSE send failed for user {}, removing connection", userId, ex);
        emitters.remove(userId, emitter);
    }

    private void handleRuntimeSendFailure(Long userId, SseEmitter emitter, RuntimeException ex) {
        if (isClientDisconnect(ex)) {
            cleanupStaleEmitter(userId, emitter, ex);
            return;
        }
        throw ex;
    }

    private boolean isClientDisconnect(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof AsyncRequestNotUsableException || current instanceof IOException) {
                return true;
            }
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("broken pipe")
                || normalized.contains("connection reset")
                || normalized.contains("connection aborted")
                || normalized.contains("response not usable")
                || normalized.contains("client disconnected")) {
                return true;
            }
        }
        return false;
    }
}
