package com.example.venue.security;

import com.example.venue.common.Result;
import com.example.venue.common.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    /**
     * 原子性执行 INCR + EXPIRE 的 Lua 脚本，避免两步操作之间进程崩溃导致 key 无 TTL 永久存在。
     */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;
    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setScriptText(
            "local count = redis.call('INCR', KEYS[1]) " +
            "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return count");
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }
    private static final int RATE_LIMIT_WINDOW_SECONDS = 60;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int limitPerMinute;
    private final ClientIpResolver clientIpResolver;

    public RateLimitFilter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                           @Value("${app.security.rate-limit-per-minute:90}") int limitPerMinute,
                           ClientIpResolver clientIpResolver) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.limitPerMinute = limitPerMinute;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * 仅对写操作和敏感查询进行限流，公开只读接口（场馆浏览、公告）不限流。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 公开只读接口不限流
        if (path.startsWith("/api/venues") && "GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (path.startsWith("/api/notices/public")) {
            return true;
        }
        if (path.startsWith("/actuator/health")) {
            return true;
        }
        // 支付回调由签名校验保护，不走限流
        if (path.startsWith("/api/payments/callback")) {
            return true;
        }
        // 其余所有 /api/ 路径均限流
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String ip = clientIpResolver.resolveClientIp(request);
        // 限流键使用规范化路径：剥离矩阵参数并把纯数字路径段归一化为 {id}，
        // 防止攻击者通过变换矩阵参数（/api/auth/login;1、/api/auth/login;2）
        // 或遍历路径变量值（/api/system/users/1/password、/api/system/users/2/password）
        // 绕过限流。详见 A26-P1-08。
        String key = "rate:" + normalizePathForRateLimit(request.getRequestURI()) + ":" + ip;
        Long count = redisTemplate.execute(RATE_LIMIT_SCRIPT, List.of(key), String.valueOf(RATE_LIMIT_WINDOW_SECONDS));
        if (count != null && count > limitPerMinute) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(Result.fail(ResultCode.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试")));
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 规范化限流键中的请求路径，避免按原始 requestURI 分桶导致限流被绕过（A26-P1-08）。
     *
     * <p>两条失效路径与修复：
     * <ol>
     *   <li>矩阵参数绕过：Tomcat 的 {@link HttpServletRequest#getRequestURI()} 返回未解码且
     *       <b>保留路径矩阵参数</b>的原始 URI，而 Spring MVC 的 {@code PathPatternParser}
     *       与 Spring Security 的请求匹配都忽略矩阵参数。攻击者可通过
     *       {@code /api/auth/login;1}、{@code /api/auth/login;2} 各自产生独立 Redis 计数键
     *       绕过登录暴力破解限流。修复：逐段剥离 ';' 之后的内容。</li>
     *   <li>路径变量按值分桶：{@code /api/system/users/1/password}、
     *       {@code /api/system/users/2/password} 各自拥有独立额度，遍历 id 时永不触发限流。
     *       修复：按 '/' 分段，纯数字段替换为 {@code {id}}，非数字段保持原样。</li>
     * </ol>
     *
     * <p>注意：仅做字符级处理，不解码 percent-encoding，避免引入解码副作用；
     * 不依赖 {@code UrlPathHelper} 以保持过滤器的零额外依赖与可测试性。
     */
    static String normalizePathForRateLimit(String requestUri) {
        if (requestUri == null || requestUri.isEmpty()) {
            return requestUri;
        }
        String path = stripMatrixParameters(requestUri);
        return normalizeNumericSegments(path);
    }

    /**
     * 逐段剥离矩阵参数（';' 之后到下一个 '/' 之前的内容）。
     * getRequestURI() 不含查询串，故首个 ';' 必为矩阵参数起点；
     * 多个路径段可各自携带矩阵参数（如 /api/auth;v=1/login;s=xxx），需逐段处理。
     */
    private static String stripMatrixParameters(String uri) {
        if (uri.indexOf(';') < 0) {
            return uri;
        }
        StringBuilder sb = new StringBuilder(uri.length());
        boolean inMatrix = false;
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c == ';') {
                inMatrix = true;
                continue;
            }
            if (c == '/') {
                inMatrix = false;
                sb.append(c);
                continue;
            }
            if (!inMatrix) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 按 '/' 分段，纯数字段替换为 {id}，非数字段与空段保持原样（保留前导/连续 '/')。
     * 例如 /api/system/users/123/password → /api/system/users/{id}/password；
     * /api/auth/login → /api/auth/login（不变）。
     */
    private static String normalizeNumericSegments(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String[] segments = path.split("/", -1);
        StringBuilder sb = new StringBuilder(path.length());
        boolean first = true;
        for (String segment : segments) {
            if (!first) {
                sb.append('/');
            }
            first = false;
            if (segment.isEmpty()) {
                continue;
            }
            if (isAllDigits(segment)) {
                sb.append("{id}");
            } else {
                sb.append(segment);
            }
        }
        return sb.toString();
    }

    private static boolean isAllDigits(String segment) {
        if (segment.isEmpty()) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            if (!Character.isDigit(segment.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
