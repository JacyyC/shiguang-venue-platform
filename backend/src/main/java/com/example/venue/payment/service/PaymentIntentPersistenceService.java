package com.example.venue.payment.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.MoneyUtils;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.enums.OrderStatus;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.payment.dto.PaymentAttemptStatus;
import com.example.venue.payment.dto.PaymentCreateResult;
import com.example.venue.payment.entity.PaymentAttempt;
import com.example.venue.payment.mapper.PaymentAttemptMapper;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Keeps payment-intent database work in short transactions so a Provider call never owns an order row lock.
 */
@Service
public class PaymentIntentPersistenceService {
    private final BizOrderMapper orderMapper;
    private final PaymentAttemptMapper paymentAttemptMapper;

    public PaymentIntentPersistenceService(BizOrderMapper orderMapper, PaymentAttemptMapper paymentAttemptMapper) {
        this.orderMapper = orderMapper;
        this.paymentAttemptMapper = paymentAttemptMapper;
    }

    @Transactional
    public PreparedIntent prepare(Long orderId, Long userId, PaymentProviderDescriptor defaultDescriptor,
                                  String paymentNo, String idempotencyKey) {
        BizOrder order = orderMapper.selectByIdForUpdate(orderId);
        if (order == null || !Objects.equals(order.userId, userId)) {
            throw BusinessException.notFound("订单不存在");
        }
        if (!OrderStatus.PENDING_PAYMENT.name().equals(order.status)) {
            throw BusinessException.badRequest("订单当前状态不可支付");
        }
        if (order.expireTime != null && order.expireTime.isBefore(LocalDateTime.now())) {
            return PreparedIntent.expired(order);
        }

        PaymentAttempt active = paymentAttemptMapper.findActiveByOrderId(orderId);
        if (active != null) {
            // A26-P0-03 兜底防护：旧 attempt 仍标记为 CREATED/PENDING，但其 payableAmount 与
            // 当前订单应付不一致（改期重定价后 sync 漏关或并发竞态），不可复用。视为无活跃 attempt，
            // 继续走新建分支生成新 attempt，避免再次撞回调金额校验。
            BigDecimal orderPayable = MoneyUtils.amountOrDefault(order.payableAmount, order.totalAmount);
            if (active.payableAmount == null
                || active.payableAmount.compareTo(orderPayable) != 0) {
                active = null;
            } else {
                return new PreparedIntent(order, active, false, false);
            }
        }
        if (defaultDescriptor == null) {
            throw BusinessException.conflict("PAYMENT_PROVIDER_NOT_CONFIGURED: 默认支付 Provider 不可用");
        }

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.paymentNo = paymentNo;
        attempt.orderId = order.id;
        attempt.orderNo = order.orderNo;
        attempt.userId = order.userId;
        attempt.venueId = order.venueId;
        attempt.reservationId = order.reservationId;
        attempt.providerEnv = defaultDescriptor.providerEnv();
        attempt.providerName = defaultDescriptor.providerName();
        attempt.channel = defaultDescriptor.providerName();
        attempt.status = PaymentAttemptStatus.CREATED.name();
        attempt.payableAmount = MoneyUtils.amountOrDefault(order.payableAmount, order.totalAmount);
        attempt.paidAmount = MoneyUtils.ZERO_AMOUNT;
        attempt.feeAmount = MoneyUtils.ZERO_AMOUNT;
        attempt.netAmount = MoneyUtils.ZERO_AMOUNT;
        attempt.currency = "CNY";
        attempt.idempotencyKey = idempotencyKey;
        attempt.configVersion = defaultDescriptor.configVersion();
        attempt.expireTime = order.expireTime;
        attempt.createTime = LocalDateTime.now();
        attempt.updateTime = attempt.createTime;
        attempt.deleted = 0;
        paymentAttemptMapper.insert(attempt);
        return new PreparedIntent(order, attempt, true, false);
    }

    /**
     * A26-P0-03：改期重定价后让订单当前活跃 attempt 失效。
     *
     * <p>调用方应在订单行锁事务内调用：原子把 status in (CREATED, PENDING) 的 attempt 置为 CLOSED，
     * 写入 SUPERSEDED_BY_RESCHEDULE 失效证据。返回被关闭的 attempt（用于上游通知用户重新发起支付），
     * 若该订单当前无活跃 attempt（尚未创建支付意图或已被关闭/超时），返回 null。
     *
     * <p>不在此处调用 Provider 关闭交易：改期重定价后旧支付页可能仍会扣款，需保留渠道回调路径，
     * 由 PaymentService 的迟到扣款退款处置兜底。Provider 侧 trade 由既有支付超时关闭链路（
     * {@link PaymentAttemptExpirationService#expireActiveAttemptAfterOrderCommit}）异步处理。
     */
    @Transactional
    public PaymentAttempt closeActiveAttemptForRescheduledOrder(Long orderId, String failureCode,
                                                                String failureMessage) {
        if (orderId == null) {
            return null;
        }
        PaymentAttempt active = paymentAttemptMapper.findActiveByOrderId(orderId);
        if (active == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = paymentAttemptMapper.closeActiveByOrderId(orderId, failureCode, failureMessage, now);
        if (updated == 0) {
            return null;
        }
        active.status = PaymentAttemptStatus.CLOSED.name();
        active.failureCode = failureCode;
        active.failureMessage = failureMessage;
        active.paidAmount = MoneyUtils.ZERO_AMOUNT;
        active.feeAmount = MoneyUtils.ZERO_AMOUNT;
        active.netAmount = MoneyUtils.ZERO_AMOUNT;
        active.updateTime = now;
        return active;
    }

    @Transactional
    public PaymentAttempt markCreateSucceeded(PaymentAttempt attempt, PaymentCreateResult result) {
        PaymentAttempt current = requireCurrentAttempt(attempt);
        if (!isActive(current)) {
            return current;
        }
        // A26-P3-06：原子条件更新，避免检查后写非原子导致并发回调已结算为 SUCCEEDED 时被覆盖回 PENDING。
        // WHERE status IN ('CREATED','PENDING') 确保只更新活跃 attempt；返回 0 表示已被并发推进，重查返回最新状态。
        LocalDateTime now = LocalDateTime.now();
        int updated = paymentAttemptMapper.markCreateSucceededIfActive(
            current.id,
            result.providerEnv(),
            result.providerName(),
            result.providerName(),
            result.status(),
            result.currency(),
            result.configVersion(),
            result.requestSummaryJson(),
            result.responseSummaryJson(),
            now);
        if (updated == 0) {
            PaymentAttempt refreshed = paymentAttemptMapper.findByPaymentNo(current.paymentNo);
            return refreshed == null ? current : refreshed;
        }
        current.providerEnv = result.providerEnv();
        current.providerName = result.providerName();
        current.channel = result.providerName();
        current.status = result.status();
        current.currency = result.currency();
        current.configVersion = result.configVersion();
        current.requestSummaryJson = result.requestSummaryJson();
        current.responseSummaryJson = result.responseSummaryJson();
        current.failureCode = null;
        current.failureMessage = null;
        current.updateTime = now;
        return current;
    }

    @Transactional
    public void markInitialCreateFailed(PaymentAttempt attempt, String failureCode, String failureMessage,
                                        String requestSummaryJson, String responseSummaryJson) {
        PaymentAttempt current = requireCurrentAttempt(attempt);
        if (!PaymentAttemptStatus.CREATED.name().equals(current.status)) {
            return;
        }
        current.status = PaymentAttemptStatus.FAILED.name();
        current.failureCode = failureCode;
        current.failureMessage = failureMessage;
        current.requestSummaryJson = requestSummaryJson;
        current.responseSummaryJson = responseSummaryJson;
        current.paidAmount = MoneyUtils.ZERO_AMOUNT;
        current.feeAmount = MoneyUtils.ZERO_AMOUNT;
        current.netAmount = MoneyUtils.ZERO_AMOUNT;
        current.updateTime = LocalDateTime.now();
        paymentAttemptMapper.updateById(current);
    }

    private PaymentAttempt requireCurrentAttempt(PaymentAttempt attempt) {
        if (attempt == null || attempt.paymentNo == null) {
            throw BusinessException.notFound("支付意图不存在");
        }
        PaymentAttempt current = paymentAttemptMapper.findByPaymentNo(attempt.paymentNo);
        return current == null ? attempt : current;
    }

    private boolean isActive(PaymentAttempt attempt) {
        return PaymentAttemptStatus.CREATED.name().equals(attempt.status)
            || PaymentAttemptStatus.PENDING.name().equals(attempt.status);
    }

    public record PreparedIntent(BizOrder order, PaymentAttempt attempt, boolean newlyCreated, boolean expired) {
        static PreparedIntent expired(BizOrder order) {
            return new PreparedIntent(order, null, false, true);
        }

        public BigDecimal payableAmount() {
            return attempt == null ? MoneyUtils.ZERO_AMOUNT : attempt.payableAmount;
        }
    }
}
