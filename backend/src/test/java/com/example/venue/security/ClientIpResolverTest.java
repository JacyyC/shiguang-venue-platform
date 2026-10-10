package com.example.venue.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A26-P2-26：ClientIpResolver 从 RateLimitFilter 提取为公共组件，供 AuthService 与 AuditLogAspect 复用。
 * 本测试覆盖可信代理判定、X-Forwarded-For / X-Real-IP 头解析、CIDR 匹配与伪造头防护。
 */
class ClientIpResolverTest {

    @Test
    void resolveClientIpReturnsRemoteAddrWhenNotBehindTrustedProxy() {
        // 直连地址不在可信代理列表：直接用 remoteAddr，忽略任何代理头，防止伪造。
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,::1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.7");
        request.addHeader("X-Real-IP", "203.0.113.5"); // 攻击者伪造，应被忽略
        request.addHeader("X-Forwarded-For", "203.0.113.5"); // 攻击者伪造，应被忽略

        assertThat(resolver.resolveClientIp(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void resolveClientIpReturnsXRealIpWhenBehindTrustedProxy() {
        // 直连可信代理（127.0.0.1）：信任 nginx 写入的 X-Real-IP 头。
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,::1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP", "203.0.113.5");

        assertThat(resolver.resolveClientIp(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void resolveClientIpReturnsXForwardedForWhenSingleEntry() {
        // 直连可信代理，X-Forwarded-For 为单一地址（nginx 写入 $remote_addr）：信任该地址。
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,::1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.5");

        assertThat(resolver.resolveClientIp(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void resolveClientIpRejectsChainedXForwardedForAndFallsBackToXRealIp() {
        // 直连可信代理，X-Forwarded-For 含多个地址（链式）：拒绝整个头（防伪造首项绕过），回退 X-Real-IP。
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,::1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "10.0.0.1, 203.0.113.5"); // 链式，拒绝
        request.addHeader("X-Real-IP", "203.0.113.5"); // 回退到 X-Real-IP

        assertThat(resolver.resolveClientIp(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void resolveClientIpFallsBackToRemoteAddrWhenProxyHeadersBlank() {
        // 直连可信代理，但 X-Forwarded-For 与 X-Real-IP 均缺失/空白：回退 remoteAddr（代理自身地址）。
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,::1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        // 不设置任何代理头

        assertThat(resolver.resolveClientIp(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void resolveClientIpMatchesTrustedProxyByCidr() {
        // 直连地址匹配 CIDR（如 docker 网段 172.16.0.0/12）：视为可信代理，信任 X-Real-IP。
        // 对应 deploy/docker-compose.yml 的 TRUSTED_PROXIES=172.16.0.0/12。
        ClientIpResolver resolver = new ClientIpResolver("172.16.0.0/12");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.5"); // 在 172.16.0.0/12 网段内
        request.addHeader("X-Real-IP", "203.0.113.5");

        assertThat(resolver.resolveClientIp(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void resolveClientIpRejectsCidrMatchAcrossAddressFamilies() {
        // IPv4 CIDR 不匹配 IPv6 地址：直连 IPv6 地址不被视为可信代理，忽略代理头。
        ClientIpResolver resolver = new ClientIpResolver("172.16.0.0/12");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("::1"); // IPv6 回环，不匹配 IPv4 CIDR
        request.addHeader("X-Real-IP", "203.0.113.5"); // 应被忽略

        assertThat(resolver.resolveClientIp(request)).isEqualTo("::1");
    }

    @Test
    void resolveClientIpTrimsWhitespaceInHeaders() {
        // X-Real-IP / X-Forwarded-For 头值前后空白被 trim，避免空白污染日志。
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,::1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP", "  203.0.113.5  ");

        assertThat(resolver.resolveClientIp(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void resolveClientIpHandlesBlankXForwardedForEntry() {
        // X-Forwarded-For 单一项但为空白：不信任，回退 X-Real-IP；X-Real-IP 也空白则回退 remoteAddr。
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,::1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "   "); // 单一项但空白，不信任
        request.addHeader("X-Real-IP", "203.0.113.5"); // 回退到 X-Real-IP

        assertThat(resolver.resolveClientIp(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void emptyTrustedProxiesTreatsAllAsUntrusted() {
        // 可信代理列表为空（配置空字符串）：任意 remoteAddr 都不被信任，永远返回 remoteAddr。
        ClientIpResolver resolver = new ClientIpResolver("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP", "203.0.113.5"); // 应被忽略

        assertThat(resolver.resolveClientIp(request)).isEqualTo("127.0.0.1");
    }
}
