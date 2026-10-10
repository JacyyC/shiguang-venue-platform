package com.example.venue.order;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.StateTransitionService;
import com.example.venue.checkin.service.ReservationLifecycleService;
import com.example.venue.funnel.service.FunnelEventService;
import com.example.venue.member.service.MemberService;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.order.dto.PaymentCallbackRequest;
import com.example.venue.order.dto.RefundApplyRequest;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.entity.OrderStatusLog;
import com.example.venue.order.entity.RefundRecord;
import com.example.venue.order.enums.OrderStatus;
import com.example.venue.order.enums.RefundStatus;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.order.mapper.OrderStatusLogMapper;
import com.example.venue.order.mapper.PaymentCallbackLogMapper;
import com.example.venue.order.mapper.PaymentRecordMapper;
import com.example.venue.order.mapper.RefundRecordMapper;
import com.example.venue.order.service.OrderNumberGenerator;
import com.example.venue.order.service.OrderService;
import com.example.venue.order.service.OrderSubmissionPublisher;
import com.example.venue.payment.service.PaymentService;
import com.example.venue.payment.service.PaymentAttemptExpirationService;
import com.example.venue.payment.service.PaymentIntentPersistenceService;
import com.example.venue.promotion.service.CouponService;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.reservation.mapper.ReservationStatusLogMapper;
import com.example.venue.resource.service.ResourceAllocationService;
import com.example.venue.venue.service.VenueAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    @Mock
    private BizOrderMapper orderMapper;
    @Mock
    private OrderStatusLogMapper orderStatusLogMapper;
    @Mock
    private PaymentRecordMapper paymentRecordMapper;
    @Mock
    private PaymentCallbackLogMapper paymentCallbackLogMapper;
    @Mock
    private RefundRecordMapper refundRecordMapper;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private ReservationOccupancyMapper occupancyMapper;
    @Mock
    private ReservationStatusLogMapper reservationStatusLogMapper;
    @Mock
    private OrderNumberGenerator numberGenerator;
    @Mock
    private VenueAccessService venueAccessService;
    @Mock
    private NoticeService noticeService;
    @Mock
    private MemberService memberService;
    @Mock
    private CouponService couponService;
    @Mock
    private ReservationLifecycleService lifecycleService;
    @Mock
    private FunnelEventService funnelEventService;
    @Mock
    private PaymentService paymentService;
    @Mock
    private PaymentAttemptExpirationService paymentAttemptExpirationService;
    @Mock
    private PaymentIntentPersistenceService paymentIntentPersistenceService;
    @Mock
    private com.example.venue.promotion.service.PromotionPricingService promotionPricingService;
    @Mock
    private ResourceAllocationService resourceAllocationService;
    @Mock
    private OrderSubmissionPublisher orderSubmissionPublisher;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = newOrderService("");
        orderService.setOrderSubmissionPublisher(orderSubmissionPublisher);
        // A26-P0-03：OrderService 通过 setter 注入 PaymentIntentPersistenceService（避免循环依赖），
        // 测试中也通过 setter 注入 mock，使 syncPendingOrderForRescheduledReservation 在金额变化时
        // 能调到 closeActiveAttemptForRescheduledOrder。
        orderService.setPaymentIntentPersistenceService(paymentIntentPersistenceService);
        // A26-P2-04：OrderService 通过 setter 注入 PromotionPricingService 用于 expireOrder/markRefundSuccess
        // 终态时回补活动预算，测试中也通过 setter 注入 mock。
        orderService.setPromotionPricingService(promotionPricingService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(7L, "tester", Set.of("USER"), Set.of()), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createPendingOrderCopiesPromotionFields() {
        Reservation reservation = reservationWithCoupon();
        when(orderMapper.findByReservationId(99L)).thenReturn(null);
        when(numberGenerator.orderNo()).thenReturn("ORD-B3");

        orderService.createPendingOrder(reservation, 30);

        ArgumentCaptor<BizOrder> orderCaptor = ArgumentCaptor.forClass(BizOrder.class);
        verify(orderMapper).insert(orderCaptor.capture());
        BizOrder insertedOrder = orderCaptor.getValue();
        assertThat(insertedOrder.totalAmount).isEqualByComparingTo("85.00");
        assertThat(insertedOrder.couponDiscountAmount).isEqualByComparingTo("20.00");
        assertThat(insertedOrder.activityDiscountAmount).isEqualByComparingTo("10.00");
        assertThat(getB3Field(insertedOrder, "userCouponId")).isEqualTo(70L);
        assertThat(getB3Field(insertedOrder, "couponTemplateId")).isEqualTo(5L);
        assertThat(getB3Field(insertedOrder, "promotionCampaignId")).isEqualTo(8L);
        assertThat(getB3Field(insertedOrder, "priceRuleId")).isEqualTo(9L);
        assertThat(insertedOrder.couponLockToken).isEqualTo("reservation-99-token");
    }

    @Test
    void createPendingOrderRecordsOrderCreatedFunnelMilestone() {
        Reservation reservation = reservationWithCoupon();
        when(orderMapper.findByReservationId(99L)).thenReturn(null);
        when(numberGenerator.orderNo()).thenReturn("ORD-B5");
        when(orderMapper.insert(any(BizOrder.class))).thenAnswer(invocation -> {
            BizOrder order = invocation.getArgument(0);
            order.id = 12L;
            return 1;
        });

        BizOrder created = orderService.createPendingOrder(reservation, 30);

        verify(funnelEventService).recordMilestone(
            eq("ORDER_CREATED"),
            eq("ORDER"),
            eq(created.userId),
            eq(created.venueId),
            eq(created.reservationId),
            eq(created.id),
            eq(created.payableAmount),
            eq("SUCCESS"),
            isNull(),
            eq("order-created-" + created.id),
            argThat((Map<String, Object> metadata) -> "ORD-B5".equals(metadata.get("orderNo"))),
            any());
        verify(orderSubmissionPublisher).publish(created);
    }

    @Test
    void simulatePayDelegatesToPaymentService() {
        BizOrder order = pendingOrder();
        when(paymentService.legacySimulatePay(11L)).thenReturn(order);

        BizOrder paid = orderService.simulatePay(11L);

        assertThat(paid).isSameAs(order);
        verify(paymentService).legacySimulatePay(11L);
    }

    @Test
    void expireOrderReleasesLockedCoupon() {
        BizOrder order = pendingOrder();
        setB3Field(order, "userCouponId", 70L);
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        orderService.expireOrder(order);

        verify(couponService).releaseForReservation(99L, "支付超时");
        verify(paymentAttemptExpirationService).expireActiveAttemptAfterOrderCommit(order);
        verify(occupancyMapper).releaseByReservationId(99L);
    }

    @Test
    void expireOrderReleasesThroughUnifiedResourceServiceWhenAvailable() {
        BizOrder order = pendingOrder();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        orderService.setResourceAllocationService(resourceAllocationService);

        orderService.expireOrder(order);

        verify(resourceAllocationService).release(99L, "PAYMENT_TIMEOUT");
        verify(occupancyMapper, never()).releaseByReservationId(any());
    }

    @Test
    void expireOrderRecordsPaymentTimeoutResult() {
        BizOrder order = pendingOrder();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        orderService.expireOrder(order);

        InOrder inOrder = inOrder(orderMapper, reservationMapper, funnelEventService, noticeService);
        inOrder.verify(orderMapper).updateById(any(BizOrder.class));
        inOrder.verify(reservationMapper).selectById(99L);
        inOrder.verify(reservationMapper).updateById(any(Reservation.class));
        inOrder.verify(funnelEventService).recordMilestone(
            eq("PAYMENT_RESULT_RECORDED"),
            eq("PAYMENT"),
            eq(order.userId),
            eq(order.venueId),
            eq(order.reservationId),
            eq(order.id),
            eq(order.payableAmount),
            eq("TIMEOUT"),
            eq("PAYMENT_TIMEOUT"),
            eq("payment-result-timeout-" + order.id),
            any(),
            any());
        inOrder.verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "PAYMENT_TIMEOUT".equals(event.eventType())
                && "PAYMENT_TIMEOUT:11".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && "支付已超时".equals(event.inAppTitle())
                && "订单 ORD001 已超时，预约时段已释放。".equals(event.inAppContent())
                && "PAYMENT".equals(event.businessType())
                && Long.valueOf(11L).equals(event.businessId())
                && Map.of("orderNo", "ORD001").equals(event.templateModel())));
    }

    @Test
    void expireOrderPropagatesTimeoutFunnelFailureBeforeTimeoutNotice() {
        BizOrder order = pendingOrder();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        RuntimeException funnelFailure = new IllegalStateException("payment timeout funnel failed");
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(funnelEventService.recordMilestone(
            eq("PAYMENT_RESULT_RECORDED"),
            eq("PAYMENT"),
            eq(order.userId),
            eq(order.venueId),
            eq(order.reservationId),
            eq(order.id),
            eq(order.payableAmount),
            eq("TIMEOUT"),
            eq("PAYMENT_TIMEOUT"),
            eq("payment-result-timeout-" + order.id),
            any(),
            any()))
            .thenThrow(funnelFailure);

        assertThatThrownBy(() -> orderService.expireOrder(order))
            .isSameAs(funnelFailure);

        verify(noticeService, never()).createMessage(7L, "支付已超时", "订单 ORD001 已超时，预约时段已释放。",
            "PAYMENT", 11L);
        verify(noticeService, never()).createBusinessNotification(any(NotificationEvent.class));
    }

    @Test
    void expireOrderInvalidatesCheckinCredentialOnTimeout() {
        BizOrder order = pendingOrder();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        orderService.expireOrder(order);

        verify(lifecycleService).freezeCredentialForReservation(99L, "支付超时");
    }

    @Test
    void expireOrderReleasesCampaignBudgetForReservationWithCampaign() {
        // A26-P2-04：支付超时（PENDING_PAYMENT → EXPIRED）终态回补活动预算，与占用对称。
        // EXPIRED 是终态（仅可 → REFUNDING 用于迟到支付退款，迟到退款由 markRefundSuccess rescheduledLateRefund 分支处理，
        // 不重复回补此处）。
        BizOrder order = pendingOrder();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        reservation.promotionCampaignId = 8L;
        reservation.activityDiscountAmount = new BigDecimal("10.00");
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        orderService.expireOrder(order);

        // 验证回补委托：调用单点 public 方法（CAS+回补由 PromotionPricingServiceTest 覆盖）
        verify(promotionPricingService).releaseCampaignBudgetForReservation(any(Reservation.class));
        // 验证回补在占用释放与券释放之后
        InOrder inOrder = inOrder(occupancyMapper, couponService, promotionPricingService);
        inOrder.verify(occupancyMapper).releaseByReservationId(99L);
        inOrder.verify(couponService).releaseForReservation(99L, "支付超时");
        inOrder.verify(promotionPricingService).releaseCampaignBudgetForReservation(any(Reservation.class));
    }

    @Test
    void expireOrderSkipsCampaignBudgetReleaseWhenReservationHasNoCampaign() {
        // A26-P2-04：reservation 未绑定活动（campaignId=null）或无活动折扣（amount<=0）不应回补。
        BizOrder order = pendingOrder();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        // promotionCampaignId/activityDiscountAmount 默认 null，触发守卫跳过
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        orderService.expireOrder(order);

        // 无活动时 service 仍委托 public 方法（内部 no-op 由 PromotionPricingServiceTest 覆盖）
        verify(promotionPricingService).releaseCampaignBudgetForReservation(any(Reservation.class));
    }

    @Test
    void refundSuccessMarksUsedCouponRefunded() {
        RefundRecord refund = new RefundRecord();
        refund.id = 30L;
        refund.refundNo = "REF001";
        refund.orderId = 11L;
        refund.status = RefundStatus.REFUNDING.name();
        BizOrder order = paidOrder();
        order.status = OrderStatus.REFUNDING.name();
        setB3Field(order, "userCouponId", 70L);
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.REFUNDING.name();
        when(refundRecordMapper.selectByIdForUpdate(30L)).thenReturn(refund);
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        orderService.markRefundSuccess(30L);

        verify(couponService).markRefundedForOrder(11L, "退款到账");
        verify(memberService).reverseRefundedOrder(order, reservation);
        verify(occupancyMapper).releaseByReservationId(99L);
        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "REFUND_SUCCEEDED".equals(event.eventType())
                && "REFUND_SUCCEEDED:30".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && Map.of("refundNo", "REF001").equals(event.templateModel())));
    }

    @Test
    void applyRefundConfirmsUserInAppAndFansOutToOwnerAndOtherManagerAfterRefundExists() {
        BizOrder order = paidOrder();
        Reservation reservation = paidReservation();
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(numberGenerator.refundNo()).thenReturn("REF001");
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of(7L, 8L));
        doAnswer(invocation -> {
            RefundRecord refund = invocation.getArgument(0);
            refund.id = 30L;
            return 1;
        }).when(refundRecordMapper).insert(any(RefundRecord.class));
        RefundApplyRequest request = new RefundApplyRequest();
        request.reason = "临时有事";

        orderService.applyRefund(11L, request);

        InOrder inOrder = inOrder(refundRecordMapper, venueAccessService);
        inOrder.verify(refundRecordMapper).insert(any(RefundRecord.class));
        inOrder.verify(venueAccessService).managerUserIds(20L);
        verify(noticeService).createMessage(7L, "退款申请已提交",
            "订单 ORD001 的退款申请已提交，等待管理员审核。", "REFUND", 30L);
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(noticeService, times(2)).createBusinessNotification(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(NotificationEvent::recipientUserId)
            .containsExactly(7L, 8L);
        assertThat(eventCaptor.getAllValues()).allSatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("REFUND_REVIEW_REQUIRED");
            assertThat(event.eventKey()).isEqualTo("REFUND_REVIEW_REQUIRED:30");
            assertThat(event.recipientKind()).isEqualTo(NotificationRecipientKind.VENUE_ADMIN);
            assertThat(event.inAppTitle()).isEqualTo("待审核退款");
            assertThat(event.inAppContent()).isEqualTo("有新的退款申请需要审核。");
            assertThat(event.businessType()).isEqualTo("REFUND");
            assertThat(event.businessId()).isEqualTo(30L);
            assertThat(event.templateModel()).isEqualTo(Map.of("refundNo", "REF001"));
        });
    }

    @Test
    void applyRefundWithNoVenueManagersRecordsSafeExclusionWithoutReviewEvent() {
        BizOrder order = paidOrder();
        Reservation reservation = paidReservation();
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(numberGenerator.refundNo()).thenReturn("REF001");
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of());
        doAnswer(invocation -> {
            RefundRecord refund = invocation.getArgument(0);
            refund.id = 30L;
            return 1;
        }).when(refundRecordMapper).insert(any(RefundRecord.class));
        RefundApplyRequest request = new RefundApplyRequest();
        request.reason = "临时有事";

        orderService.applyRefund(11L, request);

        verify(noticeService).createMessage(7L, "退款申请已提交",
            "订单 ORD001 的退款申请已提交，等待管理员审核。", "REFUND", 30L);
        verify(noticeService, never()).createBusinessNotification(any(NotificationEvent.class));
        verify(noticeService).recordNoEligibleVenueAdminExclusion(
            "REFUND_REVIEW_REQUIRED", "REFUND_REVIEW_REQUIRED:30", "REFUND", 30L);
    }

    @Test
    void approveRefundCreatesExactApprovedBusinessEvent() {
        RefundRecord refund = new RefundRecord();
        refund.id = 30L;
        refund.refundNo = "REF001";
        refund.orderId = 11L;
        refund.status = RefundStatus.PENDING_REVIEW.name();
        BizOrder order = paidOrder();
        when(refundRecordMapper.selectByIdForUpdate(30L)).thenReturn(refund);
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);

        orderService.approveRefund(30L, true, "材料齐全");

        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "REFUND_REVIEW_APPROVED".equals(event.eventType())
                && "REFUND_REVIEW_APPROVED:30".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && Map.of("refundNo", "REF001").equals(event.templateModel())));
    }

    @Test
    void rejectRefundCreatesExactRejectedBusinessEventFromStoredAuditReason() {
        RefundRecord refund = new RefundRecord();
        refund.id = 30L;
        refund.refundNo = "REF001";
        refund.orderId = 11L;
        refund.status = RefundStatus.PENDING_REVIEW.name();
        BizOrder order = paidOrder();
        order.status = OrderStatus.REFUNDING.name();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.REFUNDING.name();
        when(refundRecordMapper.selectByIdForUpdate(30L)).thenReturn(refund);
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        orderService.approveRefund(30L, false, "凭证不完整");

        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "REFUND_REVIEW_REJECTED".equals(event.eventType())
                && "REFUND_REVIEW_REJECTED:30".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && "凭证不完整".equals(event.inAppContent())
                && Map.of("refundNo", "REF001", "reason", "凭证不完整").equals(event.templateModel())));
    }

    @Test
    void rejectRefundSanitizesPrivateAuditReasonBeforeUserNotification() {
        RefundRecord refund = new RefundRecord();
        refund.id = 30L;
        refund.refundNo = "REF001";
        refund.orderId = 11L;
        refund.status = RefundStatus.PENDING_REVIEW.name();
        BizOrder order = paidOrder();
        order.status = OrderStatus.REFUNDING.name();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.REFUNDING.name();
        when(refundRecordMapper.selectByIdForUpdate(30L)).thenReturn(refund);
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        String privateReason = "<i>凭证异常</i> 联系 13987654321 或 payer@example.test " + "补".repeat(520);

        orderService.approveRefund(30L, false, privateReason);

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(noticeService).createBusinessNotification(eventCaptor.capture());
        NotificationEvent event = eventCaptor.getValue();
        assertThat(refund.auditReason).isEqualTo(privateReason);
        assertThat(event.inAppContent())
            .hasSize(500)
            .contains("凭证异常")
            .contains("139****4321")
            .contains("[邮箱已隐藏]")
            .doesNotContain("<i>", "</i>", "13987654321", "payer@example.test");
        assertThat(event.templateModel().get("reason")).isEqualTo(event.inAppContent());
    }

    @Test
    void markRefundSuccessInvalidatesCheckinCredential() {
        RefundRecord refund = new RefundRecord();
        refund.id = 30L;
        refund.refundNo = "REF001";
        refund.orderId = 11L;
        refund.status = RefundStatus.REFUNDING.name();
        BizOrder order = paidOrder();
        order.status = OrderStatus.REFUNDING.name();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.REFUNDING.name();
        when(refundRecordMapper.selectByIdForUpdate(30L)).thenReturn(refund);
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        orderService.markRefundSuccess(30L);

        verify(lifecycleService).freezeCredentialForReservation(99L, "退款到账");
    }

    @Test
    void markRefundSuccessReleasesThroughUnifiedResourceServiceWithStableReason() {
        RefundRecord refund = new RefundRecord();
        refund.id = 30L;
        refund.refundNo = "REF001";
        refund.orderId = 11L;
        refund.status = RefundStatus.REFUNDING.name();
        BizOrder order = paidOrder();
        order.status = OrderStatus.REFUNDING.name();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.REFUNDING.name();
        when(refundRecordMapper.selectByIdForUpdate(30L)).thenReturn(refund);
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        orderService.setResourceAllocationService(resourceAllocationService);

        orderService.markRefundSuccess(30L);

        verify(resourceAllocationService).release(99L, "REFUND_SUCCEEDED");
        verify(occupancyMapper, never()).releaseByReservationId(any());
    }

    @Test
    void markRefundSuccessReleasesCampaignBudgetForRefundedReservationWithCampaign() {
        // A26-P2-04：退款到账（REFUNDING → REFUNDED）终态回补活动预算，与占用对称。
        // REFUNDED 是终态不可再迁移，无重复回补风险。
        RefundRecord refund = new RefundRecord();
        refund.id = 30L;
        refund.refundNo = "REF001";
        refund.orderId = 11L;
        refund.status = RefundStatus.REFUNDING.name();
        BizOrder order = paidOrder();
        order.status = OrderStatus.REFUNDING.name();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.REFUNDING.name();
        reservation.promotionCampaignId = 8L;
        reservation.activityDiscountAmount = new BigDecimal("10.00");
        when(refundRecordMapper.selectByIdForUpdate(30L)).thenReturn(refund);
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        orderService.markRefundSuccess(30L);

        // 验证回补委托：调用单点 public 方法（CAS+回补由 PromotionPricingServiceTest 覆盖）
        verify(promotionPricingService).releaseCampaignBudgetForReservation(any(Reservation.class));
        // 验证回补在 transitionReservation(REFUNDED) 与 occupancyMapper.releaseByReservationId 之后
        InOrder inOrder = inOrder(reservationMapper, occupancyMapper, promotionPricingService);
        inOrder.verify(reservationMapper).updateById(any(Reservation.class));
        inOrder.verify(occupancyMapper).releaseByReservationId(99L);
        inOrder.verify(promotionPricingService).releaseCampaignBudgetForReservation(any(Reservation.class));
    }

    @Test
    void markRefundSuccessForRescheduledLatePaymentRefundDoesNotReleaseCampaignBudget() {
        // A26-P2-04：改期迟到扣款退款（refund.failureCode=LATE_SUCCESS_REFUND_PENDING + order.status=PENDING_PAYMENT）
        // 不调 transitionReservation(REFUNDED) 也不回补活动预算——reservation 保持 PENDING_PAYMENT 不动，
        // 改期审核通过时已由 ReservationService.releaseCampaignBudgetForReservation 回补原活动预算，
        // 此处重复回补会双倍释放。rescheduledLateRefund=true 分支跳过 releaseCampaignBudgetForReservation。
        RefundRecord refund = new RefundRecord();
        refund.id = 30L;
        refund.refundNo = "REF001";
        refund.orderId = 11L;
        refund.status = RefundStatus.REFUNDING.name();
        refund.failureCode = com.example.venue.common.RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING;
        BizOrder order = paidOrder();
        order.status = OrderStatus.PENDING_PAYMENT.name();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        reservation.promotionCampaignId = 8L;
        reservation.activityDiscountAmount = new BigDecimal("10.00");
        when(refundRecordMapper.selectByIdForUpdate(30L)).thenReturn(refund);
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        orderService.markRefundSuccess(30L);

        // rescheduledLateRefund 分支不调回补（已由改期路径回补）
        verify(promotionPricingService, never()).releaseCampaignBudgetForReservation(any(Reservation.class));
        // reservation 保持 PENDING_PAYMENT 不动（不调 transitionReservation）
        verify(reservationMapper, never()).updateById(any(Reservation.class));
    }

    @Test
    void markRefundSuccessSkipsCampaignBudgetReleaseWhenReservationHasNoCampaign() {
        // A26-P2-04：reservation 未绑定活动（campaignId=null）或无活动折扣（amount<=0）不应回补。
        RefundRecord refund = new RefundRecord();
        refund.id = 30L;
        refund.refundNo = "REF001";
        refund.orderId = 11L;
        refund.status = RefundStatus.REFUNDING.name();
        BizOrder order = paidOrder();
        order.status = OrderStatus.REFUNDING.name();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.REFUNDING.name();
        // promotionCampaignId/activityDiscountAmount 默认 null，触发守卫跳过
        when(refundRecordMapper.selectByIdForUpdate(30L)).thenReturn(refund);
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        orderService.markRefundSuccess(30L);

        // 无活动时 service 仍委托 public 方法（内部 no-op 由 PromotionPricingServiceTest 覆盖）
        verify(promotionPricingService).releaseCampaignBudgetForReservation(any(Reservation.class));
    }

    @Test
    void approveRefundRejectsRescheduledLatePaymentRefundKeepsOrderPendingPayment() {
        // A26-P0-03 补丁：改期迟到扣款退款（refund.failureCode=LATE_SUCCESS_REFUND_PENDING + order.status=PENDING_PAYMENT）
        // 审核拒绝时，订单/预约必须保持 PENDING_PAYMENT（用户用新金额重新发起支付），不调 transitionOrder/transitionReservation。
        // 修复前：isLateSuccessRefund 为 true 会调 transitionOrder(order, TIMEOUT) + transitionReservation(reservation, EXPIRED)，
        //   PENDING_PAYMENT → TIMEOUT 虽合法但语义错误（TIMEOUT 只能 → REFUNDING，用户无法重新支付），
        //   PENDING_PAYMENT → EXPIRED 会作废改期后的新预约。
        RefundRecord refund = new RefundRecord();
        refund.id = 30L;
        refund.refundNo = "REF001";
        refund.orderId = 11L;
        refund.status = RefundStatus.PENDING_REVIEW.name();
        refund.failureCode = "LATE_SUCCESS_REFUND_PENDING";
        BizOrder order = pendingOrder();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        when(refundRecordMapper.selectByIdForUpdate(30L)).thenReturn(refund);
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        orderService.approveRefund(30L, false, "旧扣款由人工对账");

        assertThat(refund.status).isEqualTo(RefundStatus.REJECTED.name());
        // 订单/预约保持 PENDING_PAYMENT，不调状态机
        assertThat(order.status).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
        assertThat(reservation.status).isEqualTo(ReservationStatus.PENDING_PAYMENT.name());
        verify(orderMapper, never()).updateById(any(BizOrder.class));
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        verify(refundRecordMapper).updateById(refund);
        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "REFUND_REVIEW_REJECTED".equals(event.eventType())
                && "REFUND_REVIEW_REJECTED:30".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && "旧扣款由人工对账".equals(event.inAppContent())
                && Map.of("refundNo", "REF001", "reason", "旧扣款由人工对账").equals(event.templateModel())));
    }

    @Test
    void markRefundSuccessForRescheduledLatePaymentRefundKeepsOrderPendingPayment() {
        // A26-P0-03 补丁：改期迟到扣款退款（refund.failureCode=LATE_SUCCESS_REFUND_PENDING + order.status=PENDING_PAYMENT）
        // 确认到账时，跳过 transitionOrder(REFUNDED)（PENDING_PAYMENT → REFUNDED 非法）、
        // transitionReservation(REFUNDED)（PENDING_PAYMENT → REFUNDED 非法）、
        // occupancyMapper.releaseByReservationId（会释放改期后的新时段占用，破坏新预约）。
        // 保留 freezeCredentialIfPresent（无凭证时静默忽略）、couponService.markRefundedForOrder（券未核销时静默忽略）、
        // 通知发送。refund.status 推进到 SUCCESS。
        RefundRecord refund = new RefundRecord();
        refund.id = 30L;
        refund.refundNo = "REF001";
        refund.orderId = 11L;
        refund.status = RefundStatus.REFUNDING.name();
        refund.failureCode = "LATE_SUCCESS_REFUND_PENDING";
        BizOrder order = pendingOrder();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        when(refundRecordMapper.selectByIdForUpdate(30L)).thenReturn(refund);
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        orderService.markRefundSuccess(30L);

        assertThat(refund.status).isEqualTo(RefundStatus.SUCCESS.name());
        // 订单/预约保持 PENDING_PAYMENT，不调状态机
        assertThat(order.status).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
        assertThat(reservation.status).isEqualTo(ReservationStatus.PENDING_PAYMENT.name());
        verify(orderMapper, never()).updateById(any(BizOrder.class));
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        verify(occupancyMapper, never()).releaseByReservationId(any());
        // 保留的调用
        verify(lifecycleService).freezeCredentialForReservation(99L, "退款到账");
        verify(couponService).markRefundedForOrder(11L, "退款到账");
        // 迟到退款不反向扣减会员成长值
        verify(memberService, never()).reverseRefundedOrder(any(), any());
        verify(refundRecordMapper).updateById(refund);
        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "REFUND_SUCCEEDED".equals(event.eventType())
                && "REFUND_SUCCEEDED:30".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && Map.of("refundNo", "REF001").equals(event.templateModel())));
    }

    @Test
    void applyRefundSetsNetAmountEqualToRefundAmountForFullRefund() {
        // A26-P1-02：全额退款时净额等于退款金额，使退款统计中的 -r.net_amount 能正确冲减营收。
        // 修复前：applyRefund 只设 refundNo/orderId/userId/refundAmount/refundReason/status/时间戳，
        //   MyBatis-Plus NOT_NULL 策略下 net_amount 不进 INSERT，落库为 schema 默认 '0.00'，
        //   三处统计 SQL 的 -r.net_amount 恒返回 0，退款永不冲减。
        BizOrder order = paidOrder();
        order.paidAmount = new BigDecimal("100.00");
        Reservation reservation = paidReservation();
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(numberGenerator.refundNo()).thenReturn("REF001");
        doAnswer(invocation -> {
            RefundRecord refund = invocation.getArgument(0);
            refund.id = 30L;
            return 1;
        }).when(refundRecordMapper).insert(any(RefundRecord.class));
        RefundApplyRequest request = new RefundApplyRequest();
        request.reason = "临时有事";

        orderService.applyRefund(11L, request);

        ArgumentCaptor<RefundRecord> refundCaptor = ArgumentCaptor.forClass(RefundRecord.class);
        verify(refundRecordMapper).insert(refundCaptor.capture());
        RefundRecord inserted = refundCaptor.getValue();
        assertThat(inserted.refundAmount).isEqualByComparingTo("100.00");
        // 净额等于退款金额（全额退款规则，applyRefund 当前不支持部分退款）
        assertThat(inserted.netAmount).isNotNull();
        assertThat(inserted.netAmount).isEqualByComparingTo(inserted.refundAmount);
        assertThat(inserted.netAmount).isEqualByComparingTo("100.00");
    }

    @Test
    void approveRefundDoesNotRewriteNetAmountSetByApplyRefund() {
        // A26-P1-02：approveRefund 只改状态（PENDING_REVIEW → REFUNDING），不重写 netAmount。
        // 修复后 applyRefund 已设 netAmount = refundAmount，approveRefund 走审核通过路径时
        //   不应触碰 netAmount，避免覆盖 applyRefund 已写入的净额。
        RefundRecord refund = new RefundRecord();
        refund.id = 30L;
        refund.refundNo = "REF001";
        refund.orderId = 11L;
        refund.refundAmount = new BigDecimal("100.00");
        refund.netAmount = new BigDecimal("100.00");
        refund.status = RefundStatus.PENDING_REVIEW.name();
        BizOrder order = paidOrder();
        when(refundRecordMapper.selectByIdForUpdate(30L)).thenReturn(refund);
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);

        orderService.approveRefund(30L, true, "材料齐全");

        ArgumentCaptor<RefundRecord> refundCaptor = ArgumentCaptor.forClass(RefundRecord.class);
        verify(refundRecordMapper).updateById(refundCaptor.capture());
        RefundRecord updated = refundCaptor.getValue();
        assertThat(updated.status).isEqualTo(RefundStatus.REFUNDING.name());
        // netAmount 保持 applyRefund 设的值，未被 approveRefund 重写
        assertThat(updated.netAmount).isEqualByComparingTo("100.00");
    }

    @Test
    void markRefundSuccessDoesNotRewriteNetAmountSetByApplyRefund() {
        // A26-P1-02：markRefundSuccess 只改状态（REFUNDING → SUCCESS），不重写 netAmount。
        // refund.netAmount 由 applyRefund 写入后，审核通过、确认到账两步状态变更均不触碰 netAmount，
        //   保证三处统计 SQL 读到的 r.net_amount 始终是 applyRefund 写入的退款净额。
        RefundRecord refund = new RefundRecord();
        refund.id = 30L;
        refund.refundNo = "REF001";
        refund.orderId = 11L;
        refund.refundAmount = new BigDecimal("100.00");
        refund.netAmount = new BigDecimal("100.00");
        refund.status = RefundStatus.REFUNDING.name();
        BizOrder order = paidOrder();
        order.status = OrderStatus.REFUNDING.name();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.REFUNDING.name();
        when(refundRecordMapper.selectByIdForUpdate(30L)).thenReturn(refund);
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        orderService.markRefundSuccess(30L);

        ArgumentCaptor<RefundRecord> refundCaptor = ArgumentCaptor.forClass(RefundRecord.class);
        verify(refundRecordMapper).updateById(refundCaptor.capture());
        RefundRecord updated = refundCaptor.getValue();
        assertThat(updated.status).isEqualTo(RefundStatus.SUCCESS.name());
        // netAmount 保持 applyRefund 设的值，未被 markRefundSuccess 重写
        assertThat(updated.netAmount).isEqualByComparingTo("100.00");
    }

    @Test
    void applyRefundRejectsCompletedReservation() {
        BizOrder order = paidOrder();
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.COMPLETED.name();
        reservation.endTime = LocalDateTime.now().minusHours(1);
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        RefundApplyRequest request = new RefundApplyRequest();
        request.reason = "临时有事";

        assertThatThrownBy(() -> orderService.applyRefund(11L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("预约已完成，不能申请退款");

        verify(refundRecordMapper, never()).insert(any(RefundRecord.class));
        verify(orderMapper, never()).updateById(any(BizOrder.class));
    }

    @Test
    void closeCanceledPaidReservationOrderCreatesRefundAndMovesToRefunding() {
        BizOrder order = paidOrder();
        when(orderMapper.findByReservationId(99L)).thenReturn(order);
        when(refundRecordMapper.selectCount(any())).thenReturn(0L);
        when(numberGenerator.refundNo()).thenReturn("REF001");

        orderService.closeOrderForCanceledReservation(99L, "用户取消");

        ArgumentCaptor<RefundRecord> refundCaptor = ArgumentCaptor.forClass(RefundRecord.class);
        verify(refundRecordMapper).insert(refundCaptor.capture());
        assertThat(refundCaptor.getValue().refundNo).isEqualTo("REF001");
        assertThat(refundCaptor.getValue().status).isEqualTo(RefundStatus.REFUNDING.name());
        assertThat(refundCaptor.getValue().refundReason).isEqualTo("用户取消");
        // A26-P1-02（审计回流）：预约取消自动退款路径 netAmount 必须等于 refundAmount（与 applyRefund 对称）。
        assertThat(refundCaptor.getValue().netAmount)
            .isEqualByComparingTo(refundCaptor.getValue().refundAmount);

        ArgumentCaptor<BizOrder> orderCaptor = ArgumentCaptor.forClass(BizOrder.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        assertThat(orderCaptor.getValue().status).isEqualTo(OrderStatus.REFUNDING.name());
        verify(orderStatusLogMapper).insert(any(OrderStatusLog.class));
    }

    @Test
    void closeCanceledPaidReservationOrderRefundNetAmountEqualsRefundAmount() {
        // A26-P1-02（审计回流范围补全）：PAID 订单因预约取消自动退款时，refund_record.net_amount
        // 必须等于 refund_amount（与 applyRefund 对称），非 schema 默认 0.00，使
        // 退款统计中的 -r.net_amount 能正确冲减营收。原 createCancellationRefundIfAbsent 遗漏赋值，
        // net_amount 落库为 schema 默认 0.00，统计恒不冲减。
        BizOrder order = paidOrder();
        order.paidAmount = new BigDecimal("100.00");
        when(orderMapper.findByReservationId(99L)).thenReturn(order);
        when(refundRecordMapper.selectCount(any())).thenReturn(0L);
        when(numberGenerator.refundNo()).thenReturn("REF001");

        orderService.closeOrderForCanceledReservation(99L, "用户取消");

        ArgumentCaptor<RefundRecord> refundCaptor = ArgumentCaptor.forClass(RefundRecord.class);
        verify(refundRecordMapper).insert(refundCaptor.capture());
        assertThat(refundCaptor.getValue().refundAmount)
            .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(refundCaptor.getValue().netAmount)
            .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(refundCaptor.getValue().netAmount)
            .isEqualByComparingTo(refundCaptor.getValue().refundAmount);
    }

    @Test
    void closeOrderForCanceledReservationInvalidatesCredentialWhenCancelingPaidReservation() {
        BizOrder order = paidOrder();
        when(orderMapper.findByReservationId(99L)).thenReturn(order);
        when(refundRecordMapper.selectCount(any())).thenReturn(0L);
        when(numberGenerator.refundNo()).thenReturn("REF001");

        orderService.closeOrderForCanceledReservation(99L, "用户取消");

        verify(lifecycleService).freezeCredentialForReservation(99L, "用户取消");
    }

    @Test
    void closeCompletedPaidReservationOrderMovesToClosed() {
        BizOrder order = paidOrder();
        when(orderMapper.findByReservationId(99L)).thenReturn(order);

        orderService.closePaidOrderForCompletedReservation(99L, "预约已完成，订单关闭");

        ArgumentCaptor<BizOrder> orderCaptor = ArgumentCaptor.forClass(BizOrder.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        assertThat(orderCaptor.getValue().status).isEqualTo(OrderStatus.CLOSED.name());
    }

    @Test
    void createPendingOrderCopiesAmountBreakdownFromReservation() {
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        reservation.venueId = 20L;
        reservation.amount = new BigDecimal("999.00");
        reservation.originalAmount = new BigDecimal("120.00");
        reservation.memberDiscountAmount = new BigDecimal("10.00");
        reservation.couponDiscountAmount = new BigDecimal("5.00");
        reservation.activityDiscountAmount = new BigDecimal("0.00");
        reservation.payableAmount = new BigDecimal("105.00");
        reservation.paidAmount = new BigDecimal("0.00");
        reservation.feeAmount = new BigDecimal("0.00");
        reservation.netAmount = new BigDecimal("0.00");
        reservation.pricingSnapshotJson = "{\"pricingVersion\":\"B0_5_BASE_V1\",\"payableAmount\":105.00}";
        when(orderMapper.findByReservationId(99L)).thenReturn(null);
        when(numberGenerator.orderNo()).thenReturn("ORD002");

        orderService.createPendingOrder(reservation, 30);

        ArgumentCaptor<BizOrder> orderCaptor = ArgumentCaptor.forClass(BizOrder.class);
        verify(orderMapper).insert(orderCaptor.capture());
        BizOrder insertedOrder = orderCaptor.getValue();
        assertThat(insertedOrder.totalAmount).isEqualByComparingTo("105.00");
        assertThat(insertedOrder.originalAmount).isEqualByComparingTo("120.00");
        assertThat(insertedOrder.memberDiscountAmount).isEqualByComparingTo("10.00");
        assertThat(insertedOrder.couponDiscountAmount).isEqualByComparingTo("5.00");
        assertThat(insertedOrder.activityDiscountAmount).isEqualByComparingTo("0.00");
        assertThat(insertedOrder.payableAmount).isEqualByComparingTo("105.00");
        assertThat(insertedOrder.paidAmount).isEqualByComparingTo("0.00");
        assertThat(insertedOrder.feeAmount).isEqualByComparingTo("0.00");
        assertThat(insertedOrder.netAmount).isEqualByComparingTo("0.00");
        assertThat(insertedOrder.pricingSnapshotJson).isEqualTo(reservation.pricingSnapshotJson);
        verify(orderStatusLogMapper).insert(any(OrderStatusLog.class));
    }

    @Test
    void syncPendingOrderForRescheduledReservationCopiesPricingFields() {
        Reservation reservation = reservationWithCoupon();
        reservation.amount = new BigDecimal("95.00");
        reservation.originalAmount = new BigDecimal("140.00");
        reservation.memberDiscountAmount = new BigDecimal("10.00");
        reservation.couponDiscountAmount = new BigDecimal("25.00");
        reservation.activityDiscountAmount = new BigDecimal("10.00");
        reservation.payableAmount = new BigDecimal("95.00");
        reservation.pricingSnapshotJson = "{\"pricingVersion\":\"B3_PROMOTION_PRICING_V1\",\"payableAmount\":95.00}";
        reservation.promotionCampaignId = 18L;
        reservation.priceRuleId = 19L;
        reservation.couponTemplateId = 15L;
        reservation.userCouponId = 70L;
        reservation.couponLockToken = "reservation-99-token";
        BizOrder order = pendingOrder();
        order.originalAmount = new BigDecimal("100.00");
        order.couponDiscountAmount = BigDecimal.ZERO;
        order.payableAmount = new BigDecimal("100.00");
        order.totalAmount = new BigDecimal("100.00");
        when(orderMapper.findByReservationId(99L)).thenReturn(order);

        orderService.syncPendingOrderForRescheduledReservation(reservation);

        ArgumentCaptor<BizOrder> orderCaptor = ArgumentCaptor.forClass(BizOrder.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        BizOrder synced = orderCaptor.getValue();
        assertThat(synced.status).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
        assertThat(synced.totalAmount).isEqualByComparingTo("95.00");
        assertThat(synced.originalAmount).isEqualByComparingTo("140.00");
        assertThat(synced.memberDiscountAmount).isEqualByComparingTo("10.00");
        assertThat(synced.couponDiscountAmount).isEqualByComparingTo("25.00");
        assertThat(synced.activityDiscountAmount).isEqualByComparingTo("10.00");
        assertThat(synced.payableAmount).isEqualByComparingTo("95.00");
        assertThat(synced.pricingSnapshotJson).isEqualTo(reservation.pricingSnapshotJson);
        assertThat(synced.promotionCampaignId).isEqualTo(18L);
        assertThat(synced.priceRuleId).isEqualTo(19L);
        assertThat(synced.couponTemplateId).isEqualTo(15L);
        assertThat(synced.userCouponId).isEqualTo(70L);
        assertThat(synced.couponLockToken).isEqualTo("reservation-99-token");
        verify(orderStatusLogMapper, never()).insert(any(OrderStatusLog.class));
    }

    @Test
    void syncPendingOrderForRescheduledReservationIgnoresNonPendingOrder() {
        Reservation reservation = reservationWithCoupon();
        BizOrder order = paidOrder();
        when(orderMapper.findByReservationId(99L)).thenReturn(order);

        orderService.syncPendingOrderForRescheduledReservation(reservation);

        verify(orderMapper, never()).updateById(any(BizOrder.class));
    }

    @Test
    void syncPendingOrderForRescheduledReservationClosesActiveAttemptWhenPayableChanged() {
        // A26-P0-03：改期重定价使订单应付从 100 变 95，应调 closeActiveAttemptForRescheduledOrder
        // 失效旧 attempt，订单保持 PENDING_PAYMENT（用户用新金额重新发起支付）。
        Reservation reservation = reservationWithCoupon();
        reservation.amount = new BigDecimal("95.00");
        reservation.payableAmount = new BigDecimal("95.00");
        BizOrder order = pendingOrder();
        order.payableAmount = new BigDecimal("100.00");
        order.totalAmount = new BigDecimal("100.00");
        when(orderMapper.findByReservationId(99L)).thenReturn(order);

        orderService.syncPendingOrderForRescheduledReservation(reservation);

        verify(paymentIntentPersistenceService).closeActiveAttemptForRescheduledOrder(
            eq(11L), eq("SUPERSEDED_BY_RESCHEDULE"),
            eq("改期重定价后旧支付意图失效，请重新发起支付"));
        // 订单保持 PENDING_PAYMENT，不转状态机。
        assertThat(order.status).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
    }

    @Test
    void syncPendingOrderForRescheduledReservationKeepsAttemptWhenPayableUnchanged() {
        // A26-P0-03：改期但应付金额未变（如仅换场次、价格不变），不应失效 attempt。
        Reservation reservation = reservationWithCoupon();
        reservation.amount = new BigDecimal("100.00");
        reservation.originalAmount = new BigDecimal("100.00");
        reservation.memberDiscountAmount = BigDecimal.ZERO;
        reservation.couponDiscountAmount = BigDecimal.ZERO;
        reservation.activityDiscountAmount = BigDecimal.ZERO;
        reservation.payableAmount = new BigDecimal("100.00");
        reservation.pricingSnapshotJson = "{\"pricingVersion\":\"STABLE\",\"payableAmount\":100.00}";
        reservation.promotionCampaignId = null;
        reservation.priceRuleId = null;
        reservation.couponTemplateId = null;
        reservation.userCouponId = null;
        reservation.couponLockToken = null;
        BizOrder order = pendingOrder();
        order.payableAmount = new BigDecimal("100.00");
        order.totalAmount = new BigDecimal("100.00");
        when(orderMapper.findByReservationId(99L)).thenReturn(order);

        orderService.syncPendingOrderForRescheduledReservation(reservation);

        verify(paymentIntentPersistenceService, never()).closeActiveAttemptForRescheduledOrder(
            any(), any(), any());
    }

    @Test
    void paymentCallbackDelegatesToPaymentService() {
        PaymentCallbackRequest request = callbackRequest();

        orderService.handlePaymentCallback("DEV_PAYMENT", request);

        verify(paymentService).handleProviderCallback("DEV_PAYMENT", request);
    }

    private OrderService newOrderService(String paymentCallbackSecret) {
        StateTransitionService stateTransitionService = new StateTransitionService(
            orderMapper, orderStatusLogMapper, reservationMapper, reservationStatusLogMapper, lifecycleService);
        return new OrderService(orderMapper, orderStatusLogMapper, paymentRecordMapper, paymentCallbackLogMapper,
            refundRecordMapper, reservationMapper, occupancyMapper, reservationStatusLogMapper, numberGenerator,
            venueAccessService, noticeService, memberService, couponService, funnelEventService,
            paymentService, stateTransitionService, paymentCallbackSecret, paymentAttemptExpirationService);
    }

    private Reservation reservationWithCoupon() {
        Reservation reservation = paidReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        reservation.venueId = 20L;
        reservation.amount = new BigDecimal("85.00");
        reservation.originalAmount = new BigDecimal("120.00");
        reservation.memberDiscountAmount = new BigDecimal("5.00");
        reservation.couponDiscountAmount = new BigDecimal("20.00");
        reservation.activityDiscountAmount = new BigDecimal("10.00");
        reservation.payableAmount = new BigDecimal("85.00");
        reservation.paidAmount = new BigDecimal("0.00");
        reservation.feeAmount = new BigDecimal("0.00");
        reservation.netAmount = new BigDecimal("0.00");
        reservation.pricingSnapshotJson = "{\"pricingVersion\":\"B3_PROMOTION_PRICING_V1\",\"payableAmount\":85.00}";
        reservation.couponLockToken = "reservation-99-token";
        setB3Field(reservation, "userCouponId", 70L);
        setB3Field(reservation, "couponTemplateId", 5L);
        setB3Field(reservation, "promotionCampaignId", 8L);
        setB3Field(reservation, "priceRuleId", 9L);
        return reservation;
    }

    private BizOrder paidOrder() {
        BizOrder order = new BizOrder();
        order.id = 11L;
        order.orderNo = "ORD001";
        order.reservationId = 99L;
        order.userId = 7L;
        order.venueId = 20L;
        order.totalAmount = BigDecimal.valueOf(100);
        order.originalAmount = new BigDecimal("100.00");
        order.memberDiscountAmount = new BigDecimal("0.00");
        order.couponDiscountAmount = new BigDecimal("0.00");
        order.activityDiscountAmount = new BigDecimal("0.00");
        order.payableAmount = new BigDecimal("100.00");
        order.paidAmount = new BigDecimal("0.00");
        order.feeAmount = new BigDecimal("0.00");
        order.netAmount = new BigDecimal("0.00");
        order.status = OrderStatus.PAID.name();
        return order;
    }

    private BizOrder pendingOrder() {
        BizOrder order = paidOrder();
        order.status = OrderStatus.PENDING_PAYMENT.name();
        return order;
    }

    private Reservation paidReservation() {
        Reservation reservation = new Reservation();
        reservation.id = 99L;
        reservation.userId = 7L;
        reservation.status = ReservationStatus.PAID.name();
        reservation.endTime = LocalDateTime.now().plusDays(1);
        reservation.deleted = 0;
        return reservation;
    }

    @Test
    void myOrdersAttachesUserSafeRefundSummary() {
        BizOrder order = paidOrder(501L);
        Page<BizOrder> page = new Page<>(1, 10);
        page.setRecords(List.of(order));
        when(orderMapper.selectPage(any(), any())).thenReturn(page);
        RefundRecord pending = refund(9001L, order.id, RefundStatus.PENDING_REVIEW.name(),
            "行程调整，无法按时到场", null);
        when(refundRecordMapper.selectList(any())).thenReturn(List.of(pending));

        PageResult<BizOrder> result = orderService.myOrders(new PageQuery());

        assertThat(result.getRecords()).hasSize(1);
        assertThat(order.refundSummary).isNotNull();
        assertThat(order.refundSummary.refundId()).isEqualTo(9001L);
        assertThat(order.refundSummary.refundNo()).isEqualTo("RF-TEST-9001");
        assertThat(order.refundSummary.refundAmount()).isEqualByComparingTo("80.00");
        assertThat(order.refundSummary.status()).isEqualTo("PENDING_REVIEW");
        assertThat(order.refundSummary.userVisibleReason()).isEqualTo("行程调整，无法按时到场");
    }

    @Test
    void myOrdersMasksAuditReasonOnRejectedRefund() {
        BizOrder order = paidOrder(502L);
        Page<BizOrder> page = new Page<>(1, 10);
        page.setRecords(List.of(order));
        when(orderMapper.selectPage(any(), any())).thenReturn(page);
        RefundRecord rejected = refund(9002L, order.id, RefundStatus.REJECTED.name(),
            "行程调整", "内部核实：用户邮箱 user@example.com 提供的凭证不足");
        when(refundRecordMapper.selectList(any())).thenReturn(List.of(rejected));

        orderService.myOrders(new PageQuery());

        assertThat(order.refundSummary).isNotNull();
        assertThat(order.refundSummary.userVisibleReason())
            .isEqualTo("内部核实：用户邮箱 [邮箱已隐藏] 提供的凭证不足");
    }

    @Test
    void myOrdersLeavesRefundSummaryAbsentWithoutRefunds() {
        BizOrder order = paidOrder(503L);
        Page<BizOrder> page = new Page<>(1, 10);
        page.setRecords(List.of(order));
        when(orderMapper.selectPage(any(), any())).thenReturn(page);
        when(refundRecordMapper.selectList(any())).thenReturn(List.of());

        orderService.myOrders(new PageQuery());

        assertThat(order.refundSummary).isNull();
    }

    @Test
    void myOrdersKeepsLatestRefundWhenMultipleExist() {
        BizOrder order = paidOrder(504L);
        Page<BizOrder> page = new Page<>(1, 10);
        page.setRecords(List.of(order));
        when(orderMapper.selectPage(any(), any())).thenReturn(page);
        RefundRecord older = refund(9100L, order.id, RefundStatus.REJECTED.name(), "旧申请", "旧审核意见");
        RefundRecord latest = refund(9200L, order.id, RefundStatus.REFUNDING.name(), "最新申请", null);
        when(refundRecordMapper.selectList(any())).thenReturn(List.of(latest, older));

        orderService.myOrders(new PageQuery());

        assertThat(order.refundSummary.refundId()).isEqualTo(9200L);
        assertThat(order.refundSummary.status()).isEqualTo("REFUNDING");
        assertThat(order.refundSummary.userVisibleReason()).isEqualTo("最新申请");
    }

    private BizOrder paidOrder(Long id) {
        BizOrder order = new BizOrder();
        order.id = id;
        order.orderNo = "ORD-" + id;
        order.reservationId = 99L;
        order.userId = 7L;
        order.status = OrderStatus.PAID.name();
        order.totalAmount = BigDecimal.valueOf(80);
        order.paidAmount = BigDecimal.valueOf(80);
        return order;
    }

    private RefundRecord refund(Long id, Long orderId, String status, String refundReason, String auditReason) {
        RefundRecord record = new RefundRecord();
        record.id = id;
        record.refundNo = "RF-TEST-" + id;
        record.orderId = orderId;
        record.userId = 7L;
        record.refundAmount = BigDecimal.valueOf(80);
        record.status = status;
        record.refundReason = refundReason;
        record.auditReason = auditReason;
        return record;
    }

    private void setB3Field(Object target, String fieldName, Object value) {
        try {
            target.getClass().getField(fieldName).set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Missing B3 field: " + target.getClass().getSimpleName() + "." + fieldName, ex);
        }
    }

    private Object getB3Field(Object target, String fieldName) {
        try {
            return target.getClass().getField(fieldName).get(target);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Missing B3 field: " + target.getClass().getSimpleName() + "." + fieldName, ex);
        }
    }

    private PaymentCallbackRequest callbackRequest() {
        PaymentCallbackRequest request = new PaymentCallbackRequest();
        request.orderNo = "ORD001";
        request.transactionId = "TXN001";
        request.amount = BigDecimal.valueOf(100);
        request.channel = "MOCK";
        return request;
    }

}
