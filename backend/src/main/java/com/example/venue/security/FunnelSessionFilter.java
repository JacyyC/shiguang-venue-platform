package com.example.venue.security;

import com.example.venue.funnel.service.FunnelSessionContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * A26-P1-03 修复：从请求头 X-Funnel-Session 提取浏览器漏斗 sessionKey 并绑定到
 * FunnelSessionContext（ThreadLocal），使 Service 层 recordMilestone 调用方
 * 能够获取与前序浏览器事件共享的同一 sessionKey。
 *
 * <p>请求结束 finally 清理 ThreadLocal，避免线程池复用导致上下文泄漏。</p>
 *
 * <p>header 缺失或为空白时不下发绑定，FunnelSessionContext.current() 返回 null，
 * 由 FunnelEventService.recordInternal 记 WARN 并保留 server-&lt;uuid&gt; 兜底，
 * 适用于定时任务、回调链路等无浏览器 sessionKey 的场景。</p>
 */
@Component
public class FunnelSessionFilter extends OncePerRequestFilter {
    public static final String FUNNEL_SESSION_HEADER = "X-Funnel-Session";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String sessionKey = request.getHeader(FUNNEL_SESSION_HEADER);
        boolean bound = false;
        if (sessionKey != null && !sessionKey.isBlank()) {
            FunnelSessionContext.set(sessionKey.trim());
            bound = true;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (bound) {
                FunnelSessionContext.clear();
            }
        }
    }
}
