package com.example.venue.order.enums;

import com.example.venue.common.BusinessException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

/**
 * 订单状态合法迁移表与校验工具。
 * <p>统一供 {@link com.example.venue.common.StateTransitionService} 及订单相关服务使用，
 * 确保所有订单状态变更路径都经过同一份合法迁移表校验，避免绕过状态机保护。</p>
 */
public final class OrderStatusTransitions {
    private OrderStatusTransitions() {
    }

    /** 合法的状态迁移路径表，非法跳转将被拒绝 */
    private static final EnumMap<OrderStatus, Set<OrderStatus>> LEGAL_TRANSITIONS;
    static {
        LEGAL_TRANSITIONS = new EnumMap<>(OrderStatus.class);
        LEGAL_TRANSITIONS.put(OrderStatus.PENDING_PAYMENT, EnumSet.of(
            OrderStatus.PAYING, OrderStatus.PAID, OrderStatus.TIMEOUT,
            OrderStatus.PAY_FAILED, OrderStatus.CLOSED));
        LEGAL_TRANSITIONS.put(OrderStatus.PAYING, EnumSet.of(
            OrderStatus.PAID, OrderStatus.PAY_FAILED, OrderStatus.TIMEOUT));
        LEGAL_TRANSITIONS.put(OrderStatus.PAID, EnumSet.of(
            OrderStatus.REFUNDING, OrderStatus.CLOSED));
        LEGAL_TRANSITIONS.put(OrderStatus.REFUNDING, EnumSet.of(
            OrderStatus.PAID,              // 退款审核拒绝，恢复为已支付
            OrderStatus.TIMEOUT,           // 迟到退款审核拒绝，恢复为超时（预约已释放不能恢复已支付）
            OrderStatus.REFUNDED));
        LEGAL_TRANSITIONS.put(OrderStatus.PAY_FAILED, EnumSet.of(
            OrderStatus.PENDING_PAYMENT,    // 允许重试支付
            OrderStatus.TIMEOUT));
        LEGAL_TRANSITIONS.put(OrderStatus.CLOSED, EnumSet.noneOf(OrderStatus.class));
        // 迟到支付确认后进入退款审核：允许从超时推进到退款中，避免退款永久卡住
        LEGAL_TRANSITIONS.put(OrderStatus.TIMEOUT, EnumSet.of(OrderStatus.REFUNDING));
        LEGAL_TRANSITIONS.put(OrderStatus.REFUNDED, EnumSet.noneOf(OrderStatus.class));
        LEGAL_TRANSITIONS.put(OrderStatus.PARTIAL_REFUNDED, EnumSet.noneOf(OrderStatus.class));
    }

    /**
     * 校验状态迁移合法性。非法迁移抛出 BusinessException。
     *
     * @param from 源状态
     * @param to   目标状态
     */
    public static void validate(OrderStatus from, OrderStatus to) {
        Set<OrderStatus> allowed = LEGAL_TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw BusinessException.badRequest("非法的状态变更: " + from + " → " + to);
        }
    }

    /**
     * 解析并校验状态字符串。fromStatus 为空、任一状态无效或迁移非法时抛出 BusinessException。
     *
     * @param fromStatus 源状态名称（OrderStatus 枚举名）
     * @param toStatus   目标状态名称（OrderStatus 枚举名）
     * @return 解析后的目标状态枚举
     */
    public static OrderStatus parseAndValidate(String fromStatus, String toStatus) {
        if (fromStatus == null) {
            throw BusinessException.badRequest("订单当前状态为空，无法变更");
        }
        OrderStatus from;
        OrderStatus to;
        try {
            from = OrderStatus.valueOf(fromStatus);
            to = OrderStatus.valueOf(toStatus);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("无效的订单状态: " + fromStatus + " → " + toStatus);
        }
        validate(from, to);
        return to;
    }
}
