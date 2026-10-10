package com.example.venue.community.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.ResultCode;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CommunityRateLimitService {
    private static final long TEN_MINUTES_MILLIS = 10 * 60 * 1000L;
    private static final long ONE_DAY_MILLIS = 24 * 60 * 60 * 1000L;
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT;

    static {
        SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>();
        SLIDING_WINDOW_SCRIPT.setResultType(Long.class);
        SLIDING_WINDOW_SCRIPT.setScriptText("""
            local now = tonumber(ARGV[1])
            local member = ARGV[2]
            local pairCount = #KEYS
            for index = 1, pairCount do
              local limit = tonumber(ARGV[2 + index * 2 - 1])
              local window = tonumber(ARGV[2 + index * 2])
              redis.call('ZREMRANGEBYSCORE', KEYS[index], '-inf', now - window)
              if redis.call('ZCARD', KEYS[index]) >= limit then
                return index
              end
            end
            for index = 1, pairCount do
              local window = tonumber(ARGV[2 + index * 2])
              redis.call('ZADD', KEYS[index], now, member)
              redis.call('PEXPIRE', KEYS[index], window)
            end
            return 0
            """);
    }

    private final StringRedisTemplate redisTemplate;

    public CommunityRateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void check(Long userId, Action action) {
        if (userId == null || action == null) {
            throw BusinessException.badRequest("社区限流参数不完整");
        }
        String baseKey = "community:rate:" + action.key + ":" + userId;
        long now = Instant.now().toEpochMilli();
        String member = now + ":" + UUID.randomUUID();
        List<String> keys;
        Object[] arguments;
        if (action.tenMinuteLimit == null) {
            keys = List.of(baseKey + ":day");
            arguments = new Object[]{
                String.valueOf(now), member,
                String.valueOf(action.dailyLimit), String.valueOf(ONE_DAY_MILLIS)
            };
        } else {
            keys = List.of(baseKey + ":10m", baseKey + ":day");
            arguments = new Object[]{
                String.valueOf(now), member,
                String.valueOf(action.tenMinuteLimit), String.valueOf(TEN_MINUTES_MILLIS),
                String.valueOf(action.dailyLimit), String.valueOf(ONE_DAY_MILLIS)
            };
        }
        try {
            Long rejectedWindow = redisTemplate.execute(SLIDING_WINDOW_SCRIPT, keys, arguments);
            if (rejectedWindow != null && rejectedWindow > 0) {
                throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, action.message);
            }
            if (rejectedWindow == null) {
                throw new BusinessException(ResultCode.SYSTEM_ERROR, "社区限流服务暂不可用");
            }
        } catch (DataAccessException ex) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "社区限流服务暂不可用");
        }
    }

    public enum Action {
        POST("post", 5, 20, "发帖过于频繁，请稍后再试"),
        COMMENT("comment", 30, 200, "评论过于频繁，请稍后再试"),
        REPORT("report", null, 10, "举报提交过于频繁，请稍后再试"),
        JOIN("join", null, 20, "入组申请过于频繁，请稍后再试"),
        // 活动与同行参与申请：与社区入组是不同业务动作，必须走独立配额与独立文案，
        // 否则两类合法动作会互相挤占同一日配额，且申请被拒时给出错误对象的提示。
        APPLY("apply", null, 20, "参与申请过于频繁，请稍后再试");

        private final String key;
        private final Integer tenMinuteLimit;
        private final int dailyLimit;
        private final String message;

        Action(String key, Integer tenMinuteLimit, int dailyLimit, String message) {
            this.key = key;
            this.tenMinuteLimit = tenMinuteLimit;
            this.dailyLimit = dailyLimit;
            this.message = message;
        }
    }
}
