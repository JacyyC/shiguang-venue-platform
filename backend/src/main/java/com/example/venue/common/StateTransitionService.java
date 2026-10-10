package com.example.venue.common;

import com.example.venue.checkin.service.ReservationLifecycleService;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.entity.OrderStatusLog;
import com.example.venue.order.enums.OrderStatus;
import com.example.venue.order.enums.OrderStatusTransitions;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.order.mapper.OrderStatusLogMapper;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.entity.ReservationStatusLog;
import com.example.venue.reservation.service.ReservationLifecycleChangePublisher;
import com.example.venue.reservation.enums.ReservationStatusTransitions;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.mapper.ReservationStatusLogMapper;
import com.example.venue.security.SecurityContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 统一处理订单与预约状态转换的共享服务。
 * <p>
 * OrderService 和 PaymentService 中有完全相同的 transitionOrder/transitionReservation 逻辑，
 * 提取到此类避免重复代码和维护不一致风险。
 */
@Service
public class StateTransitionService {
    private final BizOrderMapper orderMapper;
    private final OrderStatusLogMapper orderStatusLogMapper;
    private final ReservationMapper reservationMapper;
    private final ReservationStatusLogMapper reservationStatusLogMapper;
    private final ReservationLifecycleService lifecycleService;
    private ReservationLifecycleChangePublisher reservationLifecycleChangePublisher;

    public StateTransitionService(BizOrderMapper orderMapper,
                                  OrderStatusLogMapper orderStatusLogMapper,
                                  ReservationMapper reservationMapper,
                                  ReservationStatusLogMapper reservationStatusLogMapper,
                                  ReservationLifecycleService lifecycleService) {
        this.orderMapper = orderMapper;
        this.orderStatusLogMapper = orderStatusLogMapper;
        this.reservationMapper = reservationMapper;
        this.reservationStatusLogMapper = reservationStatusLogMapper;
        this.lifecycleService = lifecycleService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReservationLifecycleChangePublisher(
        ReservationLifecycleChangePublisher reservationLifecycleChangePublisher
    ) {
        this.reservationLifecycleChangePublisher = reservationLifecycleChangePublisher;
    }

    /**
     * 转换订单状态并记录状态日志。
     * <p>通过 {@link OrderStatusTransitions#parseAndValidate} 校验迁移合法性，
     * 如果目标状态为 PAID，同时设置支付时间。</p>
     */
    public void transitionOrder(BizOrder order, String toStatus, String reason) {
        String from = order.status;
        OrderStatusTransitions.parseAndValidate(from, toStatus);
        order.status = toStatus;
        order.updateTime = LocalDateTime.now();
        if (OrderStatus.PAID.name().equals(toStatus)) {
            order.payTime = LocalDateTime.now();
        }
        orderMapper.updateById(order);
        writeOrderLog(order.id, from, toStatus, reason);
    }

    /**
     * 单独记录订单状态日志（不更新订单本身），用于订单初始创建等场景。
     */
    public OrderStatusLog writeOrderLog(Long orderId, String fromStatus, String toStatus, String reason) {
        OrderStatusLog log = new OrderStatusLog();
        log.orderId = orderId;
        log.fromStatus = fromStatus;
        log.toStatus = toStatus;
        log.operatorId = SecurityContext.currentUserId();
        log.reason = reason;
        log.createTime = LocalDateTime.now();
        orderStatusLogMapper.insert(log);
        return log;
    }

    /**
     * 转换预约状态并记录状态日志。
     * <p>通过 {@link ReservationStatusTransitions#parseAndValidate} 校验迁移合法性，
     * 确保所有通过本服务的预约状态变更与 {@code ReservationService.transition()} 遵循同一份合法迁移表，
     * 避免绕过状态机保护。</p>
     */
    public void transitionReservation(Reservation reservation, String toStatus, String reason, String source) {
        String from = reservation.status;
        ReservationStatusTransitions.parseAndValidate(from, toStatus);
        reservation.status = toStatus;
        reservation.updateTime = LocalDateTime.now();
        reservationMapper.updateById(reservation);
        ReservationStatusLog log = new ReservationStatusLog();
        log.reservationId = reservation.id;
        log.fromStatus = from;
        log.toStatus = toStatus;
        log.operatorId = SecurityContext.currentUserId();
        log.reason = reason;
        log.source = source;
        log.createTime = LocalDateTime.now();
        reservationStatusLogMapper.insert(log);
        if (reservationLifecycleChangePublisher != null) {
            if (log.id != null) {
                reservationLifecycleChangePublisher.publishStatusChange(reservation, log);
            } else {
                reservationLifecycleChangePublisher.publishStatusChange(reservation, from, toStatus, reason, source);
            }
        }
    }

    /**
     * 如果预约已签发凭证，则冻结凭证。
     * 凭证不存在时静默忽略（返回 NOT_FOUND 异常被吞掉）。
     */
    public void freezeCredentialIfPresent(Long reservationId, String reason) {
        if (reservationId == null) {
            return;
        }
        try {
            lifecycleService.freezeCredentialForReservation(reservationId, reason);
        } catch (BusinessException ex) {
            if (ex.getCode() != ResultCode.NOT_FOUND) {
                throw ex;
            }
        }
    }
}
