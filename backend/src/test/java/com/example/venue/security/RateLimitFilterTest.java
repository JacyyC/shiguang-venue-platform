package com.example.venue.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {
    // === A26-P1-08：限流键规范化（矩阵参数剥离 + 纯数字路径段归一化为 {id}） ===

    @Test
    void normalizePathForRateLimitStripsMatrixParametersFromLoginPath() {
        // 登录暴力破解限流可被矩阵参数绕过：/api/auth/login;1、/api/auth/login;2 各自独立额度
        // 修复后均归一化为 /api/auth/login，共享同一限流键
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/auth/login;1"))
            .isEqualTo("/api/auth/login");
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/auth/login;2"))
            .isEqualTo("/api/auth/login");
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/auth/login;jsessionid=xxx"))
            .isEqualTo("/api/auth/login");
        // 无矩阵参数的登录路径保持原样
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/auth/login"))
            .isEqualTo("/api/auth/login");
    }

    @Test
    void normalizePathForRateLimitStripsMatrixParametersFromEverySegment() {
        // 多个路径段各自携带矩阵参数，需逐段剥离
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/auth;v=1/login;s=abc"))
            .isEqualTo("/api/auth/login");
        // 矩阵参数位于路径段中间，剥离后段内容保留
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/orders;token=xyz/123"))
            .isEqualTo("/api/orders/{id}");
    }

    @Test
    void normalizePathForRateLimitNormalizesNumericPathSegmentsToIdPlaceholder() {
        // 路径变量按值分桶：/api/system/users/1/password 与 /api/system/users/2/password 各自独立额度
        // 修复后均归一化为 /api/system/users/{id}/password，共享同一限流键
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/system/users/1/password"))
            .isEqualTo("/api/system/users/{id}/password");
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/system/users/456/password"))
            .isEqualTo("/api/system/users/{id}/password");
        // 多个数字段同时归一化
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/orders/100/items/200"))
            .isEqualTo("/api/orders/{id}/items/{id}");
    }

    @Test
    void normalizePathForRateLimitKeepsNonNumericSegmentsAsIs() {
        // 非数字路径段（含字母、连字符、UUID 等）保持原样，避免误归一化
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/auth/login"))
            .isEqualTo("/api/auth/login");
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/venues/downtown-spaces"))
            .isEqualTo("/api/venues/downtown-spaces");
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/system/users/admin/password"))
            .isEqualTo("/api/system/users/admin/password");
        // 字母数字混合段不归一化（仅纯数字段才替换为 {id}）
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/orders/ord-123"))
            .isEqualTo("/api/orders/ord-123");
        // UUID 段保持原样
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/reservations/550e8400-e29b-41d4-a716-446655440000"))
            .isEqualTo("/api/reservations/550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    void normalizePathForRateLimitHandlesEmptyAndNullInput() {
        assertThat(RateLimitFilter.normalizePathForRateLimit(null)).isNull();
        assertThat(RateLimitFilter.normalizePathForRateLimit("")).isEqualTo("");
    }

    @Test
    void normalizePathForRateLimitPreservesLeadingAndTrailingSlashes() {
        // 前导 '/' 保留（split 产生的空段不写内容但保留分隔符位置）
        assertThat(RateLimitFilter.normalizePathForRateLimit("/api/auth/login"))
            .isEqualTo("/api/auth/login");
        // 根路径保留
        assertThat(RateLimitFilter.normalizePathForRateLimit("/"))
            .isEqualTo("/");
    }

    @Test
    void loginRateLimitKeyIsSharedAcrossMatrixParameterVariants() throws Exception {
        // 集成性验证：doFilterInternal 传给 RedisTemplate 的限流键
        // 不应因矩阵参数变化而分桶
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), eq("60")))
            .thenReturn(1L);
        RateLimitFilter filter = new RateLimitFilter(redisTemplate, new ObjectMapper(), 90, new ClientIpResolver("127.0.0.1"));

        // 同一来源 IP 请求 /api/auth/login、/api/auth/login;1、/api/auth/login;2
        // 修复前：三个不同 Redis key，各自独立额度
        // 修复后：均归一化为 rate:/api/auth/login:<ip>，共享同一限流桶
        for (String uri : new String[]{"/api/auth/login", "/api/auth/login;1", "/api/auth/login;2"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
            request.setRemoteAddr("203.0.113.10");
            filter.doFilterInternal(request, new MockHttpServletResponse(), (req, resp) -> {});
        }

        // 验证三次调用都使用同一个限流键（key 作为 List 的唯一元素传入）
        org.mockito.ArgumentCaptor<java.util.List<String>> keyCaptor =
            org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.times(3))
            .execute(any(), keyCaptor.capture(), eq("60"));
        java.util.List<String> firstKey = keyCaptor.getAllValues().get(0);
        for (java.util.List<String> captured : keyCaptor.getAllValues()) {
            assertThat(captured).isEqualTo(firstKey);
            assertThat(captured.get(0)).isEqualTo("rate:/api/auth/login:203.0.113.10");
        }
    }

    @Test
    void numericPathSegmentsShareRateLimitKeyAcrossDifferentIds() throws Exception {
        // 集成性验证：按 id 遍历接口的暴力破解场景
        // /api/system/users/1/password、/api/system/users/2/password 应共享同一限流键
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), eq("60")))
            .thenReturn(1L);
        RateLimitFilter filter = new RateLimitFilter(redisTemplate, new ObjectMapper(), 90, new ClientIpResolver("127.0.0.1"));

        for (String uri : new String[]{
            "/api/system/users/1/password",
            "/api/system/users/2/password",
            "/api/system/users/999/password"
        }) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
            request.setRemoteAddr("203.0.113.20");
            filter.doFilterInternal(request, new MockHttpServletResponse(), (req, resp) -> {});
        }

        org.mockito.ArgumentCaptor<java.util.List<String>> keyCaptor =
            org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.times(3))
            .execute(any(), keyCaptor.capture(), eq("60"));
        for (java.util.List<String> captured : keyCaptor.getAllValues()) {
            assertThat(captured.get(0)).isEqualTo("rate:/api/system/users/{id}/password:203.0.113.20");
        }
    }

    private RateLimitFilter filter(String trustedProxies) {
        return new RateLimitFilter(mock(StringRedisTemplate.class), new ObjectMapper(), 90, new ClientIpResolver(trustedProxies));
    }
}
