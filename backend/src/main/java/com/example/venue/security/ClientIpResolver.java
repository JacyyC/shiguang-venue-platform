package com.example.venue.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

/**
 * 客户端真实 IP 解析器。
 *
 * <p>从 {@link RateLimitFilter} 提取为公共组件，使 {@code AuthService}（登录日志）与
 * {@code AuditLogAspect}（操作日志）复用同一套可信代理判定逻辑，避免登录暴力破解来源、
 * 管理员改配置或重置他人密码的来源全部被记成同一个反向代理内网地址（A26-P2-26）。
 *
 * <p>逻辑与原 {@code RateLimitFilter.resolveClientIp} 完全一致，仅做位置迁移：
 * 仅当请求直连地址（{@link HttpServletRequest#getRemoteAddr()}）属于可信代理列表时，
 * 才信任 X-Forwarded-For / X-Real-IP 头；否则直接使用直连地址，
 * 防止攻击者伪造代理头绕过限流或污染审计日志。
 */
@Component
public class ClientIpResolver {
    private final List<String> trustedProxies;

    public ClientIpResolver(@Value("${app.security.trusted-proxies:127.0.0.1,::1}") String trustedProxiesRaw) {
        this.trustedProxies = Arrays.stream(trustedProxiesRaw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    /**
     * 解析客户端真实 IP。
     * 仅当请求直连地址（{@link HttpServletRequest#getRemoteAddr()}）属于可信代理列表时，
     * 才信任 X-Forwarded-For / X-Real-IP 头；否则直接使用直连地址，
     * 防止攻击者伪造代理头绕过限流。
     */
    public String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (isTrustedProxy(remoteAddr)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // 仅信任由受控 Nginx 覆盖写入的单一客户端地址，拒绝链式头避免伪造首项绕过。
                String[] entries = forwarded.split(",");
                if (entries.length == 1 && !entries[0].trim().isBlank()) {
                    return entries[0].trim();
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return remoteAddr;
    }

    /**
     * 判断直连地址是否属于可信代理列表（支持单 IP 与 CIDR 匹配）。
     */
    private boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        for (String entry : trustedProxies) {
            if (entry.contains("/")) {
                if (matchesCidr(remoteAddr, entry)) {
                    return true;
                }
            } else if (remoteAddr.equalsIgnoreCase(entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 简单 CIDR 匹配：按网络前缀长度逐字节比较地址位。
     */
    private boolean matchesCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            byte[] cidrBytes = InetAddress.getByName(parts[0]).getAddress();
            int prefix = Integer.parseInt(parts[1]);
            byte[] ipBytes = InetAddress.getByName(ip).getAddress();
            // IPv4 与 IPv6 地址族不同（字节长度不同），无法匹配
            if (cidrBytes.length != ipBytes.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int i = 0; i < fullBytes && i < cidrBytes.length; i++) {
                if (cidrBytes[i] != ipBytes[i]) {
                    return false;
                }
            }
            if (remainingBits > 0 && fullBytes < cidrBytes.length) {
                int mask = 0xFF << (8 - remainingBits);
                if ((cidrBytes[fullBytes] & mask) != (ipBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
