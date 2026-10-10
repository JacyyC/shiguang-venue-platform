package com.example.venue.notice.service;

import com.example.venue.notice.entity.NoticeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock 实时消息发布器，仅用于开发/测试环境模拟消息投递。
 * 默认激活（matchIfMissing=true），生产环境可通过设置 app.notice.mock-provider=false 关闭。
 * SSE realtime publisher 为无条件 @Component，不受此配置影响。
 */
@Component
@ConditionalOnProperty(name = "app.notice.mock-provider", havingValue = "true", matchIfMissing = true)
public class MockRealtimeMessagePublisher implements RealtimeMessagePublisher {
    private static final Logger log = LoggerFactory.getLogger(MockRealtimeMessagePublisher.class);

    @Override
    public String channel() {
        return "MOCK";
    }

    @Override
    public void publish(NoticeMessage message) {
        log.info("Mock message delivery user={}, title={}", message.userId, message.title);
    }
}
