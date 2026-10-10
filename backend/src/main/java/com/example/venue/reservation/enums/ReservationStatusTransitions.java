package com.example.venue.reservation.enums;

import com.example.venue.common.BusinessException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

/**
 * 预约状态合法迁移表与校验工具。
 * <p>统一供 {@link com.example.venue.reservation.service.ReservationService} 和
 * {@link com.example.venue.common.StateTransitionService} 使用，确保所有预约状态变更路径
 * 都经过同一份合法迁移表校验，避免绕过状态机保护。</p>
 */
public final class ReservationStatusTransitions {
    private ReservationStatusTransitions() {
    }

    /** 合法的状态迁移路径表，非法跳转将被拒绝 */
    private static final EnumMap<ReservationStatus, Set<ReservationStatus>> LEGAL_TRANSITIONS;
    static {
        LEGAL_TRANSITIONS = new EnumMap<>(ReservationStatus.class);
        LEGAL_TRANSITIONS.put(ReservationStatus.PENDING_REVIEW, EnumSet.of(
            ReservationStatus.APPROVED, ReservationStatus.PENDING_PAYMENT,
            ReservationStatus.REJECTED, ReservationStatus.CANCELED, ReservationStatus.EXPIRED));
        LEGAL_TRANSITIONS.put(ReservationStatus.APPROVED, EnumSet.of(
            ReservationStatus.PENDING_PAYMENT, ReservationStatus.CANCELED, ReservationStatus.EXPIRED));
        LEGAL_TRANSITIONS.put(ReservationStatus.PENDING_PAYMENT, EnumSet.of(
            ReservationStatus.PAID, ReservationStatus.CANCELED, ReservationStatus.EXPIRED));
        LEGAL_TRANSITIONS.put(ReservationStatus.PAID, EnumSet.of(
            ReservationStatus.COMPLETED, ReservationStatus.CANCELED, ReservationStatus.REFUNDING));
        LEGAL_TRANSITIONS.put(ReservationStatus.REFUNDING, EnumSet.of(
            ReservationStatus.PAID,              // 退款审核拒绝，恢复为已支付
            ReservationStatus.EXPIRED,           // 迟到退款审核拒绝，恢复为过期（预约已释放不能恢复已支付）
            ReservationStatus.REFUNDED, ReservationStatus.CANCELED));
        LEGAL_TRANSITIONS.put(ReservationStatus.COMPLETED, EnumSet.noneOf(ReservationStatus.class));
        LEGAL_TRANSITIONS.put(ReservationStatus.REJECTED, EnumSet.noneOf(ReservationStatus.class));
        // 已支付预约取消后会进入退款处理；退款确认到账仍需经过统一状态机落为退款完成。
        LEGAL_TRANSITIONS.put(ReservationStatus.CANCELED, EnumSet.of(ReservationStatus.REFUNDED));
        // A26-P2-06：迟到支付确认后预约保持 EXPIRED（不再推回 REFUNDING 占用态），
        // 退款到账时由 EXPIRED 直接迁移到 REFUNDED 终态。原 EXPIRED→REFUNDING 路径已移除，
        // 因为迟到支付退款不恢复预约占用（保持 EXPIRED），markRefundSuccess 时直接 EXPIRED→REFUNDED。
        LEGAL_TRANSITIONS.put(ReservationStatus.EXPIRED, EnumSet.of(ReservationStatus.REFUNDED));
        LEGAL_TRANSITIONS.put(ReservationStatus.REFUNDED, EnumSet.noneOf(ReservationStatus.class));
    }

    /**
     * 校验状态迁移合法性。非法迁移抛出 BusinessException。
     *
     * @param from 源状态
     * @param to   目标状态
     */
    public static void validate(ReservationStatus from, ReservationStatus to) {
        Set<ReservationStatus> allowed = LEGAL_TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw BusinessException.badRequest("非法的状态变更: " + from + " → " + to);
        }
    }

    /**
     * 解析并校验状态字符串。fromStatus 为空、任一状态无效或迁移非法时抛出 BusinessException。
     *
     * @param fromStatus 源状态名称（ReservationStatus 枚举名）
     * @param toStatus   目标状态名称（ReservationStatus 枚举名）
     * @return 解析后的目标状态枚举
     */
    public static ReservationStatus parseAndValidate(String fromStatus, String toStatus) {
        if (fromStatus == null) {
            throw BusinessException.badRequest("预约当前状态为空，无法变更");
        }
        ReservationStatus from;
        ReservationStatus to;
        try {
            from = ReservationStatus.valueOf(fromStatus);
            to = ReservationStatus.valueOf(toStatus);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("无效的预约状态: " + fromStatus + " → " + toStatus);
        }
        validate(from, to);
        return to;
    }
}
