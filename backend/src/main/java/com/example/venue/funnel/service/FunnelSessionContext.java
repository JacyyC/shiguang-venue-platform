package com.example.venue.funnel.service;

/**
 * 漏斗会话上下文。
 *
 * <p>A26-P1-03 修复：服务端权威里程碑事件（RESERVATION_SUBMITTED/ORDER_CREATED/
 * PAYMENT_INITIATED/PAYMENT_RESULT_RECORDED）由 Service 层触发，无法直接访问
 * HttpServletRequest。通过 ThreadLocal 在请求过滤器中绑定浏览器传入的
 * X-Funnel-Session，使 Service 层 recordMilestone 调用方能够获取并与前序浏览器
 * 事件共享同一 sessionKey，避免漏斗 cohort 第 6~9 步结构性断链。</p>
 *
 * <p>非请求线程（定时任务、回调链路）调用 current() 返回 null，由
 * FunnelEventService.recordInternal 记 WARN 并保留 server-&lt;uuid&gt; 兜底。</p>
 */
public final class FunnelSessionContext {
    private static final ThreadLocal<String> SESSION_KEY_HOLDER = new ThreadLocal<>();

    private FunnelSessionContext() {
    }

    /**
     * 返回当前请求绑定的漏斗 sessionKey，未绑定或非请求线程返回 null。
     */
    public static String current() {
        return SESSION_KEY_HOLDER.get();
    }

    /**
     * 绑定 sessionKey 到当前线程。仅 FunnelSessionFilter 应调用此方法。
     */
    public static void set(String sessionKey) {
        SESSION_KEY_HOLDER.set(sessionKey);
    }

    /**
     * 清理当前线程绑定。FunnelSessionFilter 在 finally 中调用，避免线程池复用导致泄漏。
     */
    public static void clear() {
        SESSION_KEY_HOLDER.remove();
    }
}
