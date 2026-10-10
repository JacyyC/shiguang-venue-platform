package com.example.venue.business.constant;

/**
 * 业务领域常量。
 * 用于 FunnelEventService 记录漏斗事件时的 domain/stage 参数，
 * 替代散布在 PaymentService、OrderService、ReservationService 中的硬编码字符串。
 * 选择常量类而非枚举：FunnelEventService.recordMilestone() 接受 String 参数，
 * 常量类避免 .name() 样板代码。
 */
public final class BusinessDomainConst {
    private BusinessDomainConst() {}

    public static final String PAYMENT = "PAYMENT";
    public static final String ORDER = "ORDER";
    public static final String RESERVATION = "RESERVATION";
}
