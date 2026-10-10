package com.example.venue.order.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.constant.BusinessDomainConst;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.MoneyUtils;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.RefundFailureCodes;
import com.example.venue.common.SensitiveDataMasker;
import com.example.venue.common.StateTransitionService;
import com.example.venue.funnel.service.FunnelEventService;
import com.example.venue.funnel.service.FunnelSessionContext;
import com.example.venue.member.service.MemberService;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.order.dto.OrderRefundSummary;
import com.example.venue.order.dto.PaymentCallbackRequest;
import com.example.venue.order.dto.RefundApplyRequest;
import com.example.venue.order.entity.*;
import com.example.venue.order.enums.OrderStatus;
import com.example.venue.order.enums.RefundStatus;
import com.example.venue.order.mapper.*;
import com.example.venue.payment.service.PaymentAttemptExpirationService;
import com.example.venue.payment.service.PaymentIntentPersistenceService;
import com.example.venue.payment.service.PaymentService;
import com.example.venue.storedvalue.service.StoredValueOrderPaymentService;
import com.example.venue.promotion.service.CouponService;
import com.example.venue.promotion.service.PromotionPricingService;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.dto.ReservationVenueName;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.reservation.mapper.ReservationStatusLogMapper;
import com.example.venue.resource.service.ResourceAllocationService;
import com.example.venue.security.SecurityContext;
import com.example.venue.venue.service.VenueAccessService;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final int MAX_USER_VISIBLE_REASON_LENGTH = 500;
    private static final Pattern USER_VISIBLE_EMAIL = Pattern.compile(
        "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private final BizOrderMapper orderMapper;
    private final OrderStatusLogMapper orderStatusLogMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final PaymentCallbackLogMapper paymentCallbackLogMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final ReservationMapper reservationMapper;
    private final ReservationOccupancyMapper occupancyMapper;
    private final ReservationStatusLogMapper reservationStatusLogMapper;
    private final OrderNumberGenerator numberGenerator;
    private final VenueAccessService venueAccessService;
    private final NoticeService noticeService;
    private final MemberService memberService;
    private final CouponService couponService;
    private final FunnelEventService funnelEventService;
    private final PaymentService paymentService;
    private final PaymentAttemptExpirationService paymentAttemptExpirationService;
    private final StateTransitionService stateTransitionService;
    private final String paymentCallbackSecret;
    private OrderSubmissionPublisher orderSubmissionPublisher;
    private BusinessEventService businessEventService;
    private PaymentIntentPersistenceService paymentIntentPersistenceService;
    private StoredValueOrderPaymentService storedValueOrderPaymentService;
    // A26-P2-04：通过 setter 注入 PromotionPricingService 用于 expireOrder/markRefundSuccess
    // 终态时回补活动预算，与 ReservationService.cancel/approve 的回补路径共同覆盖所有终态。
    // 不通过构造器注入以保留现有构造器签名（向后兼容旧测试与 OrderServiceTest 的 newOrderService 工厂）。
    private PromotionPricingService promotionPricingService;
    private ResourceAllocationService resourceAllocationService;

    public OrderService(BizOrderMapper orderMapper, OrderStatusLogMapper orderStatusLogMapper,
                        PaymentRecordMapper paymentRecordMapper, PaymentCallbackLogMapper paymentCallbackLogMapper,
                        RefundRecordMapper refundRecordMapper, ReservationMapper reservationMapper,
                        ReservationOccupancyMapper occupancyMapper, ReservationStatusLogMapper reservationStatusLogMapper,
                        OrderNumberGenerator numberGenerator, VenueAccessService venueAccessService, NoticeService noticeService,
                        MemberService memberService, CouponService couponService,
                        FunnelEventService funnelEventService,
                        PaymentService paymentService,
                        StateTransitionService stateTransitionService,
                        @Value("${app.payment.callback-secret:}") String paymentCallbackSecret) {
        this(orderMapper, orderStatusLogMapper, paymentRecordMapper, paymentCallbackLogMapper, refundRecordMapper,
            reservationMapper, occupancyMapper, reservationStatusLogMapper, numberGenerator, venueAccessService,
            noticeService, memberService, couponService, funnelEventService, paymentService, stateTransitionService,
            paymentCallbackSecret, null);
    }

    @Autowired
    void setBusinessEventService(BusinessEventService businessEventService) {
        this.businessEventService = businessEventService;
    }

    @Autowired(required = false)
    public void setPaymentIntentPersistenceService(PaymentIntentPersistenceService paymentIntentPersistenceService) {
        this.paymentIntentPersistenceService = paymentIntentPersistenceService;
    }

    @Autowired(required = false)
    public void setStoredValueOrderPaymentService(StoredValueOrderPaymentService storedValueOrderPaymentService) {
        this.storedValueOrderPaymentService = storedValueOrderPaymentService;
    }

    @Autowired
    public void setPromotionPricingService(PromotionPricingService promotionPricingService) {
        this.promotionPricingService = promotionPricingService;
    }

    @Autowired
    public void setResourceAllocationService(ResourceAllocationService resourceAllocationService) {
        this.resourceAllocationService = resourceAllocationService;
    }

    @Autowired(required = false)
    public void setOrderSubmissionPublisher(OrderSubmissionPublisher orderSubmissionPublisher) {
        this.orderSubmissionPublisher = orderSubmissionPublisher;
    }

    @Autowired
    public OrderService(BizOrderMapper orderMapper, OrderStatusLogMapper orderStatusLogMapper,
                        PaymentRecordMapper paymentRecordMapper, PaymentCallbackLogMapper paymentCallbackLogMapper,
                        RefundRecordMapper refundRecordMapper, ReservationMapper reservationMapper,
                        ReservationOccupancyMapper occupancyMapper, ReservationStatusLogMapper reservationStatusLogMapper,
                        OrderNumberGenerator numberGenerator, VenueAccessService venueAccessService, NoticeService noticeService,
                        MemberService memberService, CouponService couponService,
                        FunnelEventService funnelEventService,
                        PaymentService paymentService,
                        StateTransitionService stateTransitionService,
                        @Value("${app.payment.callback-secret:}") String paymentCallbackSecret,
                        PaymentAttemptExpirationService paymentAttemptExpirationService) {
        this.orderMapper = orderMapper;
        this.orderStatusLogMapper = orderStatusLogMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.paymentCallbackLogMapper = paymentCallbackLogMapper;
        this.refundRecordMapper = refundRecordMapper;
        this.reservationMapper = reservationMapper;
        this.occupancyMapper = occupancyMapper;
        this.reservationStatusLogMapper = reservationStatusLogMapper;
        this.numberGenerator = numberGenerator;
        this.venueAccessService = venueAccessService;
        this.noticeService = noticeService;
        this.memberService = memberService;
        this.couponService = couponService;
        this.funnelEventService = funnelEventService;
        this.paymentService = paymentService;
        this.paymentAttemptExpirationService = paymentAttemptExpirationService;
        this.stateTransitionService = stateTransitionService;
        this.paymentCallbackSecret = paymentCallbackSecret;
    }

    @Transactional
    public BizOrder createPendingOrder(Reservation reservation, int payTimeoutMinutes) {
        BizOrder existing = orderMapper.findByReservationId(reservation.id);
        if (existing != null) {
            return existing;
        }
        BizOrder order = new BizOrder();
        order.orderNo = numberGenerator.orderNo();
        order.reservationId = reservation.id;
        order.userId = reservation.userId;
        order.venueId = reservation.venueId;
        copyReservationPricing(order, reservation);
        order.status = OrderStatus.PENDING_PAYMENT.name();
        order.expireTime = LocalDateTime.now().plusMinutes(payTimeoutMinutes);
        order.createTime = LocalDateTime.now();
        order.updateTime = order.createTime;
        orderMapper.insert(order);
        recordOrderCreatedEvent(order);
        funnelEventService.recordMilestone(
            "ORDER_CREATED",
            BusinessDomainConst.ORDER,
            order.userId,
            order.venueId,
            order.reservationId,
            order.id,
            MoneyUtils.amountOrDefault(order.payableAmount, order.totalAmount),
            "SUCCESS",
            null,
            "order-created-" + order.id,
            Map.of("orderNo", order.orderNo),
            // AC-07 补修：FunnelEventService 会优先按 reservationId 继承最早的
            // RESERVATION_SUBMITTED session，避免管理员审核请求的 session 污染用户漏斗。
            FunnelSessionContext.current());
        OrderStatusLog orderStatusLog = stateTransitionService.writeOrderLog(
            order.id, null, order.status, "预约审核通过生成待支付订单");
        if (orderSubmissionPublisher != null) {
            if (orderStatusLog != null && orderStatusLog.id != null) {
                orderSubmissionPublisher.publish(order, orderStatusLog);
            } else {
                orderSubmissionPublisher.publish(order);
            }
        }
        return order;
    }

    @Transactional
    public void syncPendingOrderForRescheduledReservation(Reservation reservation) {
        BizOrder order = orderMapper.findByReservationId(reservation.id);
        if (order == null || !OrderStatus.PENDING_PAYMENT.name().equals(order.status)) {
            return;
        }
        // A26-P0-03：改期重定价后，订单应付金额可能变化。若变化则需把当前活跃 payment_attempt
        // 失效（标 CLOSED + SUPERSEDED_BY_RESCHEDULE），避免用户在已打开的旧支付页按旧金额扣款后
        // 回调撞金额校验、连迟到退款兜底都走不到。订单保持 PENDING_PAYMENT，用户用新金额重新发起支付。
        BigDecimal oldPayable = MoneyUtils.amountOrDefault(order.payableAmount, order.totalAmount);
        copyReservationPricing(order, reservation);
        BigDecimal newPayable = MoneyUtils.amountOrDefault(order.payableAmount, order.totalAmount);
        order.updateTime = LocalDateTime.now();
        orderMapper.updateById(order);
        if (newPayable.compareTo(oldPayable) != 0
            && paymentIntentPersistenceService != null) {
            paymentIntentPersistenceService.closeActiveAttemptForRescheduledOrder(
                order.id, "SUPERSEDED_BY_RESCHEDULE",
                "改期重定价后旧支付意图失效，请重新发起支付");
        }
    }

    public PageResult<BizOrder> myOrders(PageQuery query) {
        Long userId = SecurityContext.currentUserId();
        Page<BizOrder> page = orderMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()),
            new QueryWrapper<BizOrder>().eq("user_id", userId).orderByDesc("id"));
        enrichVenueNames(page.getRecords());
        enrichRefundSummaries(page.getRecords());
        return PageResult.of(page.getRecords(), page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /**
     * G-T1：为用户自己的订单列表附带最近一条退款概要，供前端在交易面展示退款单号/金额/状态/原因。
     * 仅本方法富化（列表已被 user_id 过滤）；REJECTED 时用既有用户可见口径脱敏 auditReason，
     * 其余状态展示用户自己填写的 refundReason。管理端列表不富化，内部字段照旧走 /api/admin/refunds。
     */
    private void enrichRefundSummaries(List<BizOrder> orders) {
        if (orders == null || orders.isEmpty()) return;
        Set<Long> orderIds = new HashSet<>();
        for (BizOrder order : orders) if (order.id != null) orderIds.add(order.id);
        if (orderIds.isEmpty()) return;
        List<RefundRecord> refunds = refundRecordMapper.selectList(new QueryWrapper<RefundRecord>()
            .in("order_id", orderIds).orderByDesc("id"));
        if (refunds == null || refunds.isEmpty()) return;
        Map<Long, RefundRecord> latestByOrder = new HashMap<>();
        for (RefundRecord refund : refunds) {
            latestByOrder.putIfAbsent(refund.orderId, refund);
        }
        for (BizOrder order : orders) {
            RefundRecord refund = latestByOrder.get(order.id);
            if (refund == null) continue;
            String visibleReason = RefundStatus.REJECTED.name().equals(refund.status)
                ? sanitizeUserVisibleReason(refund.auditReason, "退款申请已被拒绝。")
                : refund.refundReason;
            order.refundSummary = new OrderRefundSummary(refund.id, refund.refundNo, refund.refundAmount,
                refund.status, visibleReason);
        }
    }

    public Map<Long, Long> findOrderIdsByReservationIds(Collection<Long> reservationIds) {
        if (reservationIds == null || reservationIds.isEmpty()) return Map.of();
        List<BizOrder> orders = orderMapper.selectList(new QueryWrapper<BizOrder>().in("reservation_id", new HashSet<>(reservationIds)));
        Map<Long, Long> result = new HashMap<>();
        for (BizOrder order : orders) {
            if (order.reservationId != null && order.id != null) result.put(order.reservationId, order.id);
        }
        return result;
    }

    private void enrichVenueNames(List<BizOrder> orders) {
        if (orders == null || orders.isEmpty()) return;
        Set<Long> reservationIds = new HashSet<>();
        for (BizOrder order : orders) if (order.reservationId != null) reservationIds.add(order.reservationId);
        if (reservationIds.isEmpty()) return;
        Map<Long, String> names = new HashMap<>();
        List<ReservationVenueName> projections = reservationMapper.findVenueNamesByReservationIds(reservationIds);
        if (projections != null) {
            for (ReservationVenueName projection : projections) names.put(projection.reservationId, projection.venueName);
        }
        for (BizOrder order : orders) order.venueName = names.get(order.reservationId);
    }

    public PageResult<BizOrder> adminOrders(PageQuery query, Long venueId) {
        QueryWrapper<BizOrder> wrapper = new QueryWrapper<BizOrder>();
        if (venueId != null) {
            venueAccessService.requireVenueManageAccess(venueId);
            wrapper.eq("venue_id", venueId);
        } else {
            List<Long> manageableVenueIds = venueAccessService.manageableVenueIds();
            if (manageableVenueIds != null) {
                if (manageableVenueIds.isEmpty()) {
                    return PageResult.of(List.of(), 0, query.getPageNum(), query.getPageSize());
                }
                wrapper.in("venue_id", manageableVenueIds);
            }
        }
        Page<BizOrder> page = orderMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    public PageResult<RefundRecord> adminRefunds(PageQuery query, Long venueId, String status) {
        String normalizedStatus = normalizeRefundStatus(status);
        List<Long> venueIds = null;
        if (venueId != null) {
            venueAccessService.requireVenueManageAccess(venueId);
        } else {
            venueIds = venueAccessService.manageableVenueIds();
            if (venueIds != null && venueIds.isEmpty()) {
                return PageResult.of(List.of(), 0, query.getPageNum(), query.getPageSize());
            }
        }
        Page<RefundRecord> page = new Page<>(query.getPageNum(), query.getPageSize());
        var result = refundRecordMapper.selectAdminPage(page, venueId, venueIds, normalizedStatus);
        return PageResult.of(result.getRecords(), result.getTotal(), query.getPageNum(), query.getPageSize());
    }

    public BizOrder simulatePay(Long orderId) {
        return paymentService.legacySimulatePay(orderId);
    }

    @Transactional
    public void handlePaymentCallback(String channel, PaymentCallbackRequest request) {
        paymentService.handleProviderCallback(channel, request);
    }

    @Transactional
    public RefundRecord applyRefund(Long orderId, RefundApplyRequest request) {
        Long userId = SecurityContext.currentUserId();
        BizOrder order = orderMapper.selectByIdForUpdate(orderId);
        if (order == null || !Objects.equals(order.userId, userId)) {
            throw BusinessException.notFound("订单不存在");
        }
        if (!OrderStatus.PAID.name().equals(order.status)) {
            throw BusinessException.badRequest("只有已支付订单可以申请退款");
        }
        Reservation reservation = requireReservationForOrder(order);
        ensureReservationRefundable(reservation);
        if (refundRecordMapper.selectCount(new QueryWrapper<RefundRecord>().eq("order_id", orderId).ne("status", RefundStatus.REJECTED.name())) > 0) {
            throw BusinessException.conflict("该订单已有退款申请");
        }
        RefundRecord refund = new RefundRecord();
        refund.refundNo = numberGenerator.refundNo();
        refund.orderId = order.id;
        refund.userId = userId;
        refund.refundAmount = MoneyUtils.amountOrDefault(order.paidAmount, order.totalAmount);
        // A26-P1-02：全额退款时净额等于退款金额，使退款统计中的 -r.net_amount 能正确冲减营收。
        // 当前 applyRefund 仅支持全额退款（直接取 order.paidAmount 或 totalAmount 作为退款金额，
        // 无部分退款比例逻辑），故 netAmount = refundAmount；如后续支持部分退款，按比例重算此处。
        // approveRefund / markRefundSuccess 仅改状态，不重写 netAmount。
        refund.netAmount = refund.refundAmount;
        refund.refundReason = request.reason;
        refund.status = RefundStatus.PENDING_REVIEW.name();
        refund.createTime = LocalDateTime.now();
        refund.updateTime = refund.createTime;
        refundRecordMapper.insert(refund);
        recordRefundEvent(BusinessEventType.REFUND_REQUESTED, refund, order, "PENDING_REVIEW", "用户申请退款");
        stateTransitionService.transitionOrder(order, OrderStatus.REFUNDING.name(), "用户申请退款");
        stateTransitionService.transitionReservation(reservation, ReservationStatus.REFUNDING.name(), "用户申请退款", "USER");
        noticeService.createMessage(userId, "退款申请已提交", "订单 " + order.orderNo + " 的退款申请已提交，等待管理员审核。", "REFUND", refund.id);
        notifyVenueManagersForRefundReview(order, refund);
        return refund;
    }

    @Transactional
    public void approveRefund(Long refundId, boolean approve, String reason) {
        RefundRecord refund = refundRecordMapper.selectByIdForUpdate(refundId);
        if (refund == null) {
            throw BusinessException.notFound("退款记录不存在");
        }
        BizOrder order = orderMapper.selectByIdForUpdate(refund.orderId);
        venueAccessService.requireVenueManageAccess(order.venueId);
        if (!RefundStatus.PENDING_REVIEW.name().equals(refund.status)) {
            throw BusinessException.badRequest("退款记录当前状态不可审核");
        }
        refund.auditReason = reason;
        refund.updateTime = LocalDateTime.now();
        if (!approve) {
            refund.status = RefundStatus.REJECTED.name();
            refundRecordMapper.updateById(refund);
            recordRefundEvent(BusinessEventType.REFUND_REJECTED, refund, order, refund.status, "退款审核拒绝");
            Reservation reservation = reservationMapper.selectById(order.reservationId);
            // A26-P0-03 补丁：改期迟到扣款退款（order 保持 PENDING_PAYMENT，用户用新金额重新发起支付）。
            // 不调 transitionOrder/transitionReservation 以免把改期后的新预约作废：
            //   PENDING_PAYMENT → TIMEOUT 虽合法但语义错误（TIMEOUT 只能 → REFUNDING，用户无法重新支付）；
            //   PENDING_PAYMENT → EXPIRED 会作废改期后的新预约。旧扣款由人工对账。
            boolean rescheduledLateRefund = isLateSuccessRefund(refund)
                && OrderStatus.PENDING_PAYMENT.name().equals(order.status);
            if (!rescheduledLateRefund && isLateSuccessRefund(refund)) {
                // 正常迟到支付退款（订单 TIMEOUT → REFUNDING）：拒绝时订单回退到超时。
                // A26-P2-06：预约在 settleLateSuccessfulPayment 中保持 EXPIRED（不再推回 REFUNDING），
                // 拒绝时无需再调 transitionReservation(EXPIRED)，预约已是 EXPIRED 终态。
                stateTransitionService.transitionOrder(order, OrderStatus.TIMEOUT.name(), "迟到退款审核拒绝");
            } else if (!rescheduledLateRefund) {
                // 用户主动申请退款（订单 PAID → REFUNDING）：拒绝时恢复为已支付
                stateTransitionService.transitionOrder(order, OrderStatus.PAID.name(), "退款审核拒绝");
                if (reservation != null) {
                    stateTransitionService.transitionReservation(reservation, ReservationStatus.PAID.name(),
                        "退款审核拒绝", "ADMIN");
                }
            }
            // rescheduledLateRefund=true 时：订单/预约保持 PENDING_PAYMENT，不调状态机
            String userVisibleReason = sanitizeUserVisibleReason(
                refund.auditReason, "退款申请已被拒绝。");
            noticeService.createBusinessNotification(new NotificationEvent(
                "REFUND_REVIEW_REJECTED",
                "REFUND_REVIEW_REJECTED:" + refund.id,
                order.userId,
                NotificationRecipientKind.USER,
                "退款审核未通过",
                userVisibleReason,
                "REFUND",
                refund.id,
                Map.of("refundNo", refund.refundNo, "reason", userVisibleReason)
            ));
            return;
        }
        refund.status = RefundStatus.REFUNDING.name();
        refundRecordMapper.updateById(refund);
        recordRefundEvent(BusinessEventType.REFUND_APPROVED, refund, order, refund.status, "退款审核通过");
        noticeService.createBusinessNotification(new NotificationEvent(
            "REFUND_REVIEW_APPROVED",
            "REFUND_REVIEW_APPROVED:" + refund.id,
            order.userId,
            NotificationRecipientKind.USER,
            "退款审核已通过",
            "退款申请审核通过，等待到账。",
            "REFUND",
            refund.id,
            Map.of("refundNo", refund.refundNo)
        ));
    }

    @Transactional
    public void markRefundSuccess(Long refundId) {
        RefundRecord refund = refundRecordMapper.selectByIdForUpdate(refundId);
        if (refund == null) {
            throw BusinessException.notFound("退款记录不存在");
        }
        BizOrder order = orderMapper.selectByIdForUpdate(refund.orderId);
        if (order == null) {
            throw BusinessException.notFound("订单不存在");
        }
        venueAccessService.requireVenueManageAccess(order.venueId);
        if (!RefundStatus.REFUNDING.name().equals(refund.status)) {
            throw BusinessException.badRequest("只有退款中的记录可以确认到账");
        }
        if ("STORED_VALUE".equals(order.paymentToolType)) {
            if (storedValueOrderPaymentService == null) {
                throw BusinessException.conflict("储值退款服务不可用");
            }
            storedValueOrderPaymentService.refund(order.id, refund.id,
                "ORDER_REFUND:" + refund.id, refund.refundReason);
        }
        // A26-P0-03 补丁：改期迟到扣款退款（order 保持 PENDING_PAYMENT，用户用新金额重新发起支付）。
        // 跳过 transitionOrder(REFUNDED)（PENDING_PAYMENT → REFUNDED 在 OrderStatusTransitions 无此路径，非法）；
        // 跳过 transitionReservation(REFUNDED)（PENDING_PAYMENT → REFUNDED 在 ReservationStatusTransitions 无此路径，非法）；
        // 跳过 occupancyMapper.releaseByReservationId（会释放改期后的新时段占用，破坏新预约）。
        // 保留 freezeCredentialIfPresent（改期迟到扣款未签发凭证，无凭证时静默忽略，调用安全）；
        // 保留 couponService.markRefundedForOrder（券未核销时静默忽略，无副作用——见 CouponService.markRefundedForOrder 守卫）。
        boolean rescheduledLateRefund = isLateSuccessRefund(refund)
            && OrderStatus.PENDING_PAYMENT.name().equals(order.status);
        refund.status = RefundStatus.SUCCESS.name();
        refund.updateTime = LocalDateTime.now();
        refundRecordMapper.updateById(refund);
        recordRefundEvent(BusinessEventType.REFUND_SUCCEEDED, refund, order, refund.status, "退款到账");
        if (!rescheduledLateRefund) {
            stateTransitionService.transitionOrder(order, OrderStatus.REFUNDED.name(), "退款到账");
        }
        Reservation reservation = reservationMapper.selectById(order.reservationId);
        if (reservation == null) {
            throw BusinessException.notFound("关联预约不存在");
        }
        if (!rescheduledLateRefund) {
            stateTransitionService.transitionReservation(reservation, ReservationStatus.REFUNDED.name(), "退款到账", "PAYMENT");
            releaseOccupancy(reservation.id, "REFUND_SUCCEEDED");
            // A26-P2-04：退款到账终态回补活动预算，与占用对称（create 时 consume）。
            // CAS 幂等（reservation.budget_released）防 expireOrder+markRefundSuccess 双终态路径对同一
            // reservation 重复回补；rescheduledLateRefund 分支（reservation 保持 PENDING_PAYMENT）已由改期
            // 审核通过时回补，CAS 返回 0 幂等跳过。
            promotionPricingService.releaseCampaignBudgetForReservation(reservation);
        }
        stateTransitionService.freezeCredentialIfPresent(reservation.id, "退款到账");
        couponService.markRefundedForOrder(order.id, "退款到账");
        // 迟到退款从未调用 recordPaidOrder 记录会员成长值，退款时不应反向扣减
        if (!isLateSuccessRefund(refund)) {
            memberService.reverseRefundedOrder(order, reservation);
        }
        noticeService.createBusinessNotification(new NotificationEvent(
            "REFUND_SUCCEEDED",
            "REFUND_SUCCEEDED:" + refund.id,
            order.userId,
            NotificationRecipientKind.USER,
            "退款已到账",
            "订单 " + order.orderNo + " 已完成退款。",
            "REFUND",
            refund.id,
            Map.of("refundNo", refund.refundNo)
        ));
    }

    /** 判断是否迟到支付退款（预约已释放，审核拒绝时回退超时/过期，退款到账时不反向会员成长值） */
    private boolean isLateSuccessRefund(RefundRecord refund) {
        return refund != null && RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING.equals(refund.failureCode);
    }

    private void recordOrderCreatedEvent(BizOrder order) {
        if (businessEventService == null) {
            return;
        }
        businessEventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.ORDER_CREATED, BusinessDomain.ORDER, "ORDER", order.id,
                BusinessSourceType.SYSTEM, "SUCCESS")
            .relatedEntity("RESERVATION", order.reservationId)
            .toStatus(order.status)
            .idempotencyKey("ORDER_CREATED:" + order.id)
            .metadata("orderNo", order.orderNo)
            .metadata("payableAmount", order.payableAmount)
            .metadata("promotionCampaignId", order.promotionCampaignId));
    }

    private void recordRefundEvent(BusinessEventType type, RefundRecord refund, BizOrder order,
                                   String status, String reason) {
        if (businessEventService == null) {
            return;
        }
        businessEventService.record(BusinessEventRecordRequest.of(
                type, BusinessDomain.REFUND, "REFUND", refund.id, BusinessSourceType.USER, "SUCCESS")
            .relatedEntity("ORDER", order.id)
            .toStatus(status)
            .reasonText(reason)
            .idempotencyKey(type.name() + ":" + refund.id)
            .metadata("refundAmount", refund.refundAmount)
            .metadata("orderId", order.id));
    }

    /**
     * 订单超时过期的资源释放链路。
     * <p>执行顺序：订单状态流转至 TIMEOUT → 关联预约状态流转至 EXPIRED →
     * 冻结已签发凭证 → 释放占用记录 → 释放优惠券锁定 → 记录漏斗事件 → 发送通知。</p>
     * <p>关键决策点：由调度器定时触发，仅处理已超时且未支付的订单；
     * 资源释放必须完整，避免占用残留导致其他用户无法预约。</p>
     * <p>副作用：更新 biz_order、更新 reservation、更新 voucher、
     * 更新 reservation_occupancy、更新 user_coupon、写入 funnel_event、发送通知。</p>
     */
    @Transactional
    public void expireOrder(BizOrder order) {
        if (!OrderStatus.PENDING_PAYMENT.name().equals(order.status)) {
            return;
        }
        stateTransitionService.transitionOrder(order, OrderStatus.TIMEOUT.name(), "支付超时");
        if (paymentAttemptExpirationService != null) {
            paymentAttemptExpirationService.expireActiveAttemptAfterOrderCommit(order);
        }
        Reservation reservation = reservationMapper.selectById(order.reservationId);
        if (reservation == null) {
            log.warn("订单 {} 关联预约 {} 不存在，跳过过期处理", order.id, order.reservationId);
            return;
        }
        stateTransitionService.transitionReservation(reservation, ReservationStatus.EXPIRED.name(), "支付超时释放占用", "SYSTEM");
        stateTransitionService.freezeCredentialIfPresent(reservation.id, "支付超时");
        releaseOccupancy(reservation.id, "PAYMENT_TIMEOUT");
        couponService.releaseForReservation(order.reservationId, "支付超时");
        // A26-P2-04：支付超时终态回补活动预算，与占用对称（create 时 consume）。
        // CAS 幂等（reservation.budget_released）防迟到退款到账 markRefundSuccess 对同一 reservation 重复回补；
        // 此处为首次终态释放（CAS 占位成功），迟到退款到账时 CAS 返回 0 跳过。
        promotionPricingService.releaseCampaignBudgetForReservation(reservation);
        funnelEventService.recordMilestone(
            "PAYMENT_RESULT_RECORDED",
            BusinessDomainConst.PAYMENT,
            order.userId,
            order.venueId,
            order.reservationId,
            order.id,
            MoneyUtils.amountOrDefault(order.payableAmount, order.totalAmount),
            "TIMEOUT",
            "PAYMENT_TIMEOUT",
            "payment-result-timeout-" + order.id,
            Map.of(),
            // A26-P1-03：支付超时定时任务路径，无浏览器 sessionKey 上下文，传 null 由 recordInternal
            // 记 WARN 并保留 server-<uuid> 兜底（超时事件不在漏斗 cohort 转化主路径上）。
            FunnelSessionContext.current());
        noticeService.createBusinessNotification(new NotificationEvent(
            "PAYMENT_TIMEOUT",
            "PAYMENT_TIMEOUT:" + order.id,
            order.userId,
            NotificationRecipientKind.USER,
            "支付已超时",
            "订单 " + order.orderNo + " 已超时，预约时段已释放。",
            "PAYMENT",
            order.id,
            Map.of("orderNo", order.orderNo)
        ));
    }

    private void notifyVenueManagersForRefundReview(BizOrder order, RefundRecord refund) {
        List<Long> managerIds = venueAccessService.managerUserIds(order.venueId);
        if (managerIds == null || managerIds.isEmpty()) {
            noticeService.recordNoEligibleVenueAdminExclusion(
                "REFUND_REVIEW_REQUIRED",
                "REFUND_REVIEW_REQUIRED:" + refund.id,
                "REFUND",
                refund.id);
            return;
        }
        for (Long managerId : managerIds) {
            noticeService.createBusinessNotification(new NotificationEvent(
                "REFUND_REVIEW_REQUIRED",
                "REFUND_REVIEW_REQUIRED:" + refund.id,
                managerId,
                NotificationRecipientKind.VENUE_ADMIN,
                "待审核退款",
                "有新的退款申请需要审核。",
                "REFUND",
                refund.id,
                Map.of("refundNo", refund.refundNo)
            ));
        }
    }

    private String sanitizeUserVisibleReason(String privateReason, String fallback) {
        String htmlStripped = privateReason == null
            ? null
            : Jsoup.clean(privateReason, Safelist.none()).trim();
        String masked = SensitiveDataMasker.mask(htmlStripped);
        String sanitized = masked == null
            ? null
            : USER_VISIBLE_EMAIL.matcher(masked).replaceAll("[邮箱已隐藏]").replace("***@***", "[邮箱已隐藏]");
        if (sanitized == null || sanitized.isBlank()) {
            sanitized = fallback;
        }
        return sanitized.length() <= MAX_USER_VISIBLE_REASON_LENGTH
            ? sanitized
            : sanitized.substring(0, MAX_USER_VISIBLE_REASON_LENGTH);
    }

    @Transactional
    public void closePendingOrderForReservation(Long reservationId, String reason) {
        BizOrder order = orderMapper.findByReservationId(reservationId);
        if (order == null || !OrderStatus.PENDING_PAYMENT.name().equals(order.status)) {
            return;
        }
        stateTransitionService.transitionOrder(order, OrderStatus.CLOSED.name(), reason == null || reason.isBlank() ? "预约已取消" : reason);
    }

    @Transactional
    public void closeOrderForCanceledReservation(Long reservationId, String reason) {
        BizOrder order = orderMapper.findByReservationId(reservationId);
        if (order == null) {
            return;
        }
        String normalizedReason = reason == null || reason.isBlank() ? "预约已取消" : reason;
        if (OrderStatus.PENDING_PAYMENT.name().equals(order.status)) {
            stateTransitionService.transitionOrder(order, OrderStatus.CLOSED.name(), normalizedReason);
            return;
        }
        if (OrderStatus.PAID.name().equals(order.status)) {
            createCancellationRefundIfAbsent(order, normalizedReason);
            stateTransitionService.freezeCredentialIfPresent(reservationId, normalizedReason);
            stateTransitionService.transitionOrder(order, OrderStatus.REFUNDING.name(), "预约取消，退款处理中");
        }
    }

    @Transactional
    public void closePaidOrderForCompletedReservation(Long reservationId, String reason) {
        BizOrder order = orderMapper.findByReservationId(reservationId);
        if (order == null || !OrderStatus.PAID.name().equals(order.status)) {
            return;
        }
        stateTransitionService.transitionOrder(order, OrderStatus.CLOSED.name(), reason == null || reason.isBlank() ? "预约已完成，订单关闭" : reason);
    }

    private void releaseOccupancy(Long reservationId, String reason) {
        if (resourceAllocationService == null) {
            occupancyMapper.releaseByReservationId(reservationId);
        } else {
            resourceAllocationService.release(reservationId, reason);
        }
    }

    private Reservation requireReservationForOrder(BizOrder order) {
        Reservation reservation = reservationMapper.selectById(order.reservationId);
        if (reservation == null || reservation.deleted != null && reservation.deleted == 1) {
            throw BusinessException.badRequest("关联预约不存在");
        }
        return reservation;
    }

    private void ensureReservationRefundable(Reservation reservation) {
        if (ReservationStatus.COMPLETED.name().equals(reservation.status)
            || reservation.endTime != null && !reservation.endTime.isAfter(LocalDateTime.now())) {
            throw BusinessException.badRequest("预约已完成，不能申请退款");
        }
        if (!ReservationStatus.PAID.name().equals(reservation.status)) {
            throw BusinessException.badRequest("当前预约状态不可申请退款");
        }
    }

    // AUD-P2-04：活动预算回补已抽取到 PromotionPricingService.releaseCampaignBudgetForReservation 单点 public
    // 方法（含 CAS 幂等防护）。expireOrder/markRefundSuccess 终态直接调用，CAS 幂等防双终态重复回补。

    private void createCancellationRefundIfAbsent(BizOrder order, String reason) {
        Long existingRefunds = refundRecordMapper.selectCount(new QueryWrapper<RefundRecord>()
            .eq("order_id", order.id)
            .ne("status", RefundStatus.REJECTED.name()));
        if (existingRefunds != null && existingRefunds > 0) {
            return;
        }
        RefundRecord refund = new RefundRecord();
        refund.refundNo = numberGenerator.refundNo();
        refund.orderId = order.id;
        refund.userId = order.userId;
        refund.refundAmount = MoneyUtils.amountOrDefault(order.paidAmount, order.totalAmount);
        // A26-P1-02（审计回流范围补全）：与 applyRefund 对称设 netAmount = refundAmount。
        // 全额退款时净额等于退款金额，使退款统计中的 -r.net_amount 能正确冲减营收。
        // 当前仅支持全额退款（直接取 order.paidAmount 或 totalAmount 作为退款金额），
        // 无部分退款比例逻辑；如后续支持部分退款，按比例重算此处与 applyRefund 同步。
        // approveRefund / markRefundSuccess 仅改状态，不重写 netAmount。
        refund.netAmount = refund.refundAmount;
        refund.refundReason = reason;
        refund.status = RefundStatus.REFUNDING.name();
        refund.auditReason = "预约取消自动退款";
        refund.createTime = LocalDateTime.now();
        refund.updateTime = refund.createTime;
        refundRecordMapper.insert(refund);
    }

    private void copyReservationPricing(BizOrder order, Reservation reservation) {
        order.originalAmount = MoneyUtils.amountOrDefault(reservation.originalAmount, reservation.amount);
        order.memberDiscountAmount = MoneyUtils.zeroIfNull(reservation.memberDiscountAmount);
        order.couponDiscountAmount = MoneyUtils.zeroIfNull(reservation.couponDiscountAmount);
        order.activityDiscountAmount = MoneyUtils.zeroIfNull(reservation.activityDiscountAmount);
        order.payableAmount = MoneyUtils.amountOrDefault(reservation.payableAmount, reservation.amount);
        order.paidAmount = MoneyUtils.zeroIfNull(reservation.paidAmount);
        order.feeAmount = MoneyUtils.zeroIfNull(reservation.feeAmount);
        order.netAmount = MoneyUtils.zeroIfNull(reservation.netAmount);
        order.pricingSnapshotJson = reservation.pricingSnapshotJson;
        order.promotionCampaignId = reservation.promotionCampaignId;
        order.priceRuleId = reservation.priceRuleId;
        order.couponTemplateId = reservation.couponTemplateId;
        order.userCouponId = reservation.userCouponId;
        order.couponLockToken = reservation.couponLockToken;
        order.totalAmount = order.payableAmount;
    }

    private String normalizeRefundStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return RefundStatus.valueOf(status.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("退款状态不合法");
        }
    }

}
