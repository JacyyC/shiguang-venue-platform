package com.example.venue.payment.service;

import com.example.venue.checkin.dto.ReservationLifecycleResponse;
import com.example.venue.checkin.service.ReservationLifecycleService;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.enums.ProviderEnv;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.MoneyUtils;
import com.example.venue.common.StateTransitionService;
import com.example.venue.funnel.service.FunnelEventService;
import com.example.venue.funnel.service.FunnelSessionContext;
import com.example.venue.member.service.MemberService;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.entity.PaymentRecord;
import com.example.venue.order.enums.OrderStatus;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.order.mapper.PaymentRecordMapper;
import com.example.venue.payment.dto.PaymentAttemptStatus;
import com.example.venue.payment.dto.PaymentCallbackResult;
import com.example.venue.payment.entity.PaymentAttempt;
import com.example.venue.payment.mapper.PaymentAttemptMapper;
import com.example.venue.promotion.service.CouponService;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.mapper.ReservationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Local order effects after a trusted payment fact exists.
 *
 * <p>This service deliberately does not create Provider attempts, call a Provider,
 * debit stored value, or decide late-payment dispositions. Those responsibilities
 * remain in their owning orchestrators.</p>
 */
@Service
public class OrderLocalSettlementService {
    private static final Logger log = LoggerFactory.getLogger(OrderLocalSettlementService.class);
    public static final String EXTERNAL_PROVIDER_TOOL = "EXTERNAL_PROVIDER";
    public static final String STORED_VALUE_TOOL = "STORED_VALUE";

    private final BizOrderMapper orderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final PaymentAttemptMapper paymentAttemptMapper;
    private final ReservationMapper reservationMapper;
    private final StateTransitionService stateTransitionService;
    private final ReservationLifecycleService lifecycleService;
    private final CouponService couponService;
    private final MemberService memberService;
    private final FunnelEventService funnelEventService;
    private final NoticeService noticeService;
    private BusinessEventService businessEventService;

    public OrderLocalSettlementService(BizOrderMapper orderMapper,
                                       PaymentRecordMapper paymentRecordMapper,
                                       PaymentAttemptMapper paymentAttemptMapper,
                                       ReservationMapper reservationMapper,
                                       StateTransitionService stateTransitionService,
                                       ReservationLifecycleService lifecycleService,
                                       CouponService couponService,
                                       MemberService memberService,
                                       FunnelEventService funnelEventService,
                                       NoticeService noticeService) {
        this(orderMapper, paymentRecordMapper, paymentAttemptMapper, reservationMapper,
            stateTransitionService, lifecycleService, couponService, memberService,
            funnelEventService, noticeService, null);
    }

    @Autowired
    public OrderLocalSettlementService(BizOrderMapper orderMapper,
                                       PaymentRecordMapper paymentRecordMapper,
                                       PaymentAttemptMapper paymentAttemptMapper,
                                       ReservationMapper reservationMapper,
                                       StateTransitionService stateTransitionService,
                                       ReservationLifecycleService lifecycleService,
                                       CouponService couponService,
                                       MemberService memberService,
                                       FunnelEventService funnelEventService,
                                       NoticeService noticeService,
                                       BusinessEventService businessEventService) {
        this.orderMapper = orderMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.paymentAttemptMapper = paymentAttemptMapper;
        this.reservationMapper = reservationMapper;
        this.stateTransitionService = stateTransitionService;
        this.lifecycleService = lifecycleService;
        this.couponService = couponService;
        this.memberService = memberService;
        this.funnelEventService = funnelEventService;
        this.noticeService = noticeService;
        this.businessEventService = businessEventService;
    }

    @Autowired(required = false)
    void setBusinessEventService(BusinessEventService businessEventService) {
        this.businessEventService = businessEventService;
    }

    /**
     * Applies the existing payment-success side effects exactly once after the
     * caller has acquired the order row lock.
     */
    @Transactional
    public ReservationLifecycleResponse settle(PaidOrderFact fact) {
        validate(fact);
        BizOrder order = fact.order();
        String existingTool = blankToNull(order.paymentToolType);
        if (existingTool != null && !existingTool.equals(fact.paymentToolType())) {
            throw BusinessException.conflict("订单已经由其他支付工具结算");
        }
        if (!OrderStatus.PENDING_PAYMENT.name().equals(order.status)) {
            throw BusinessException.badRequest("订单当前状态不可支付");
        }

        if (fact.payment() != null) {
            paymentRecordMapper.insert(fact.payment());
        }
        if (fact.attempt() != null) {
            updateSuccessfulAttempt(fact.attempt(), fact);
        }
        recordPaymentEvent(fact);

        order.paidAmount = fact.paidAmount();
        order.feeAmount = MoneyUtils.zeroIfNull(fact.feeAmount());
        order.netAmount = MoneyUtils.zeroIfNull(fact.netAmount());
        order.payType = fact.payType();
        order.paymentToolType = fact.paymentToolType();
        order.paymentFactId = fact.paymentFactId();
        order.paymentCurrency = "CNY";
        funnelEventService.recordMilestone(
            "PAYMENT_RESULT_RECORDED",
            "PAYMENT",
            order.userId,
            order.venueId,
            order.reservationId,
            order.id,
            fact.paidAmount(),
            "SUCCESS",
            null,
            "payment-result-success-" + order.id + "-" + fact.paymentFactId(),
            fact.attempt() == null
                ? Map.of("channel", fact.channel(), "paymentFactId", fact.paymentFactId())
                : Map.of("channel", fact.channel(), "paymentNo", fact.attempt().paymentNo),
            FunnelSessionContext.current());
        stateTransitionService.transitionOrder(order, OrderStatus.PAID.name(), "支付成功");

        Reservation reservation = reservationMapper.selectById(order.reservationId);
        if (reservation == null) {
            throw BusinessException.notFound("关联预约不存在");
        }
        stateTransitionService.transitionReservation(reservation, ReservationStatus.PAID.name(), "支付成功", "PAYMENT");
        ReservationLifecycleResponse credentialResponse =
            lifecycleService.issueCredentialForPaidReservation(order, reservation);

        if (order.userCouponId != null
            && order.couponDiscountAmount != null
            && order.couponDiscountAmount.compareTo(BigDecimal.ZERO) > 0) {
            couponService.markUsedForOrder(order.id, order.userCouponId,
                MoneyUtils.zeroIfNull(order.couponDiscountAmount));
        }
        memberService.recordPaidOrder(order, reservation);
        try {
            noticeService.createBusinessNotification(new NotificationEvent(
                "PAYMENT_SUCCEEDED",
                "PAYMENT_SUCCEEDED:" + order.id,
                order.userId,
                NotificationRecipientKind.USER,
                "支付成功",
                "订单 " + order.orderNo + " 已支付成功。",
                "PAYMENT",
                order.id,
                Map.of("orderNo", order.orderNo)
            ));
        } catch (Exception ex) {
            // Keep the historical contract: notification failure does not roll back a paid order.
            log.warn("支付成功通知发送失败，订单={}, eventId=PAYMENT_SUCCEEDED:{}", order.id, order.id, ex);
        }
        return credentialResponse;
    }

    /** Convenience adapter for the existing external callback path. */
    public ReservationLifecycleResponse settleExternal(PaymentAttempt attempt, BizOrder order,
                                                        PaymentRecord payment,
                                                        PaymentCallbackResult callback) {
        return settle(new PaidOrderFact(order, attempt, payment, EXTERNAL_PROVIDER_TOOL,
            callback.externalRef(),
            attempt == null ? "PROVIDER" : attempt.channel,
            attempt == null ? "PROVIDER" : attempt.providerName,
            "PROVIDER", callback.paidAmount(), callback.feeAmount(), callback.netAmount(), LocalDateTime.now()));
    }

    private void validate(PaidOrderFact fact) {
        if (fact == null || fact.order() == null || fact.paymentToolType() == null
            || fact.paymentToolType().isBlank() || fact.paymentFactId() == null
            || fact.paymentFactId().isBlank() || fact.paidAmount() == null
            || fact.paidAmount().signum() <= 0 || fact.channel() == null || fact.channel().isBlank()
            || fact.payType() == null || fact.payType().isBlank()) {
            throw BusinessException.badRequest("订单支付事实不完整");
        }
        BigDecimal payable = MoneyUtils.amountOrDefault(fact.order().payableAmount, fact.order().totalAmount);
        // The legacy order/payment tables have no currency column; their only supported currency is CNY.
        if (payable.compareTo(fact.paidAmount()) != 0) {
            throw BusinessException.conflict("支付金额与订单应付金额不一致");
        }
        if (fact.feeAmount() != null && fact.feeAmount().signum() < 0
            || fact.netAmount() != null && fact.netAmount().signum() < 0) {
            throw BusinessException.badRequest("支付手续费或净额不合法");
        }
    }

    private void updateSuccessfulAttempt(PaymentAttempt attempt, PaidOrderFact fact) {
        attempt.status = PaymentAttemptStatus.SUCCEEDED.name();
        attempt.externalRef = fact.payment() == null ? fact.paymentFactId() : fact.payment().externalRef;
        attempt.paidAmount = fact.paidAmount();
        attempt.feeAmount = MoneyUtils.zeroIfNull(fact.feeAmount());
        attempt.netAmount = MoneyUtils.zeroIfNull(fact.netAmount());
        attempt.paidTime = fact.paidTime() == null ? LocalDateTime.now() : fact.paidTime();
        attempt.updateTime = attempt.paidTime;
        if (fact.payment() != null) {
            attempt.requestSummaryJson = fact.payment().requestSummaryJson;
            attempt.responseSummaryJson = fact.payment().responseSummaryJson;
        }
        paymentAttemptMapper.updateById(attempt);
    }

    private void recordPaymentEvent(PaidOrderFact fact) {
        if (businessEventService == null) {
            return;
        }
        PaymentAttempt attempt = fact.attempt();
        if (attempt != null) {
            ProviderEnv providerEnv;
            try {
                providerEnv = attempt.providerEnv == null ? null : ProviderEnv.valueOf(attempt.providerEnv);
            } catch (IllegalArgumentException ignored) {
                providerEnv = null;
            }
            businessEventService.record(BusinessEventRecordRequest.of(
                    BusinessEventType.PAYMENT_SUCCEEDED, BusinessDomain.PAYMENT,
                    "PAYMENT_ATTEMPT", attempt.id, BusinessSourceType.PROVIDER, "SUCCESS")
                .relatedEntity("ORDER", attempt.orderId)
                .provider(providerEnv, attempt.providerName)
                .externalRef(attempt.externalRef)
                .idempotencyKey(BusinessEventType.PAYMENT_SUCCEEDED.name() + ":" + attempt.id)
                .metadata("paymentNo", attempt.paymentNo)
                .metadata("payableAmount", attempt.payableAmount));
            return;
        }
        businessEventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.PAYMENT_SUCCEEDED, BusinessDomain.PAYMENT,
                "ORDER_PAYMENT", fact.order().id, BusinessSourceType.SYSTEM, "SUCCESS")
            .relatedEntity("ORDER", fact.order().id)
            .idempotencyKey("STORED_VALUE_PAYMENT:" + fact.order().id)
            .metadata("paymentFactId", fact.paymentFactId())
            .metadata("amount", fact.paidAmount()));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
