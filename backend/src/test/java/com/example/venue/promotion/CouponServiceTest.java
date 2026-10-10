package com.example.venue.promotion;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.promotion.controller.CouponController;
import com.example.venue.promotion.dto.CouponPricingView;
import com.example.venue.promotion.dto.UserCouponResponse;
import com.example.venue.promotion.entity.CouponStatusLog;
import com.example.venue.promotion.entity.CouponTemplate;
import com.example.venue.promotion.entity.UserCoupon;
import com.example.venue.promotion.mapper.CouponStatusLogMapper;
import com.example.venue.promotion.mapper.CouponTemplateMapper;
import com.example.venue.promotion.mapper.UserCouponMapper;
import com.example.venue.promotion.service.CouponService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {
    @Mock
    private CouponTemplateMapper templateMapper;
    @Mock
    private UserCouponMapper userCouponMapper;
    @Mock
    private CouponStatusLogMapper statusLogMapper;
    @Mock
    private BusinessEventService businessEventService;
    @Mock
    private NoticeService noticeService;

    private CouponService service;

    @BeforeEach
    void setUp() {
        service = new CouponService(templateMapper, userCouponMapper, statusLogMapper, businessEventService, noticeService, new ObjectMapper().findAndRegisterModules());
        authenticate(new CurrentUser(7L, "coupon-user", Set.of("USER"), Set.of("coupon:me")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void claimRejectsInactiveOrExceededTemplate() {
        CouponTemplate inactive = template(5L);
        inactive.status = "PAUSED";
        when(templateMapper.selectById(5L)).thenReturn(inactive);
        when(templateMapper.selectByIdForUpdate(5L)).thenReturn(inactive);

        assertThatThrownBy(() -> service.claim(5L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("优惠券不可领取");

        CouponTemplate active = template(6L);
        active.perUserLimit = 1;
        when(templateMapper.selectById(6L)).thenReturn(active);
        when(templateMapper.selectByIdForUpdate(6L)).thenReturn(active);
        when(userCouponMapper.countUserCouponsForTemplate(6L, 7L)).thenReturn(1L);

        assertThatThrownBy(() -> service.claim(6L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已达到每人限领数量");
    }

    @Test
    void claimIsIdempotentAgainstConcurrentIssuedCount() {
        CouponTemplate template = template(5L);
        when(templateMapper.selectById(5L)).thenReturn(template);
        when(templateMapper.selectByIdForUpdate(5L)).thenReturn(template);
        when(userCouponMapper.countUserCouponsForTemplate(5L, 7L)).thenReturn(0L, 0L);
        when(templateMapper.incrementIssuedIfAvailableForUser(5L, 7L, 1)).thenReturn(0);

        assertThatThrownBy(() -> service.claim(5L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("优惠券已领完");

        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
        verify(statusLogMapper, never()).insert(any(CouponStatusLog.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void claimRejectsConcurrentPerUserOverClaimWhenGuardDeniesClaim() {
        CouponTemplate template = template(5L);
        template.perUserLimit = 1;
        when(templateMapper.selectById(5L)).thenReturn(template);
        when(templateMapper.selectByIdForUpdate(5L)).thenReturn(template);
        when(userCouponMapper.countUserCouponsForTemplate(5L, 7L)).thenReturn(0L, 1L);
        when(templateMapper.incrementIssuedIfAvailableForUser(5L, 7L, 1)).thenReturn(0);

        assertThatThrownBy(() -> service.claim(5L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已达到每人限领数量");

        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
        verify(statusLogMapper, never()).insert(any(CouponStatusLog.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void couponTemplateMapperDefinesDbBackedPerUserClaimGuard() throws Exception {
        Method method = CouponTemplateMapper.class.getDeclaredMethod(
            "incrementIssuedIfAvailableForUser", Long.class, Long.class, Integer.class);
        org.apache.ibatis.annotations.Update update = method.getAnnotation(org.apache.ibatis.annotations.Update.class);

        assertThat(update).isNotNull();
        assertThat(String.join("\n", update.value()))
            .contains("issued_quantity = issued_quantity + 1")
            .contains("total_quantity")
            .contains("user_coupon")
            .contains("template_id = #{id}")
            .contains("user_id = #{userId}")
            .contains("< #{perUserLimit}");
    }

    @Test
    void couponForPricingRejectsWrongUserExpiredTemplateAndThresholdMiss() {
        LocalDateTime start = LocalDateTime.now().plusDays(1);
        LocalDateTime end = start.plusHours(2);
        assertThat(service.couponForPricing(7L, null, new BigDecimal("108.00"), 1L, 10L, start, end).unavailableReason)
            .isNull();

        // AUD-P2-05：放宽定价校验后不再要求 currentUserId == userId，
        // 但仍校验券归属于 userId（coupon.userId != userId 时拒绝）。
        // 当前操作人 7 传 userId=8 + 券属于 7，应当被 L161 拒绝。
        UserCoupon othersCoupon = coupon(70L, 5L, 7L, "CLAIMED");
        when(userCouponMapper.selectById(70L)).thenReturn(othersCoupon);

        assertThatThrownBy(() -> service.couponForPricing(8L, 70L, new BigDecimal("108.00"), 1L, 10L, start, end))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("只能使用自己的优惠券");

        UserCoupon expired = coupon(70L, 5L, 7L, "CLAIMED");
        expired.validEndTime = LocalDateTime.now().minusMinutes(1);
        when(userCouponMapper.selectById(70L)).thenReturn(expired);

        CouponPricingView expiredView = service.couponForPricing(7L, 70L, new BigDecimal("108.00"), 1L, 10L, start, end);
        assertThat(expiredView.unavailableReason).contains("优惠券已过期");

        UserCoupon claimed = coupon(71L, 5L, 7L, "CLAIMED");
        CouponTemplate template = template(5L);
        setTemplateField(template, "thresholdAmount", new BigDecimal("120.00"));
        when(userCouponMapper.selectById(71L)).thenReturn(claimed);
        when(templateMapper.selectById(5L)).thenReturn(template);

        CouponPricingView thresholdMiss = service.couponForPricing(7L, 71L, new BigDecimal("108.00"), 1L, 10L, start, end);
        assertThat(thresholdMiss.unavailableReason).contains("未达到优惠券使用门槛");

        setTemplateField(template, "thresholdAmount", new BigDecimal("100.00"));
        setTemplateField(template, "discountValue", new BigDecimal("20.00"));
        CouponPricingView view = service.couponForPricing(7L, 71L, new BigDecimal("108.00"), 1L, 10L, start, end);
        assertThat(view.discountAmount).isEqualByComparingTo("20.00");
        assertThat(view.templateCode).isEqualTo("B3_CASH_20");
    }

    @Test
    void couponForPricingAllowsPricingForReservationOwnerWithoutCurrentUserMatch() {
        // AUD-P2-05：放宽定价校验——预约 owner=券所有者即可，不要求当前操作人=券所有者。
        // 模拟改期审核场景：当前操作人是场馆管理员（userId=7），传入 reservation.userId=8（券所有者），
        // 券属于 8，应正常返回可用 view（不抛 forbidden）。
        LocalDateTime start = LocalDateTime.now().plusDays(1);
        LocalDateTime end = start.plusHours(2);
        UserCoupon claimed = coupon(80L, 5L, 8L, "CLAIMED");
        CouponTemplate template = template(5L);
        setTemplateField(template, "thresholdAmount", new BigDecimal("100.00"));
        setTemplateField(template, "discountValue", new BigDecimal("20.00"));
        when(userCouponMapper.selectById(80L)).thenReturn(claimed);
        when(templateMapper.selectById(5L)).thenReturn(template);

        CouponPricingView view = service.couponForPricing(8L, 80L, new BigDecimal("108.00"), 1L, 10L, start, end);

        assertThat(view.unavailableReason).isNull();
        assertThat(view.discountAmount).isEqualByComparingTo("20.00");
    }

    @Test
    void couponForPricingTreatsLockedCouponForOwnReservationAsAvailable() {
        // AUD-P2-05：LOCKED 券对该预约视为可用——券已锁在预约上，改期审核时正常计算折扣。
        // 原 L166 把 LOCKED 券无条件判为不可用，导致含已锁券改期审核稳定失败；
        // 修复后 LOCKED 状态视为可用（与 CLAIMED 等价），其他非 CLAIMED 状态仍拒绝。
        LocalDateTime start = LocalDateTime.now().plusDays(1);
        LocalDateTime end = start.plusHours(2);
        UserCoupon locked = coupon(81L, 5L, 7L, "LOCKED");
        locked.reservationId = 99L;
        locked.lockToken = "reservation-99-token";
        locked.lockedAt = LocalDateTime.now().minusMinutes(5);
        CouponTemplate template = template(5L);
        setTemplateField(template, "thresholdAmount", new BigDecimal("100.00"));
        setTemplateField(template, "discountValue", new BigDecimal("20.00"));
        when(userCouponMapper.selectById(81L)).thenReturn(locked);
        when(templateMapper.selectById(5L)).thenReturn(template);

        CouponPricingView view = service.couponForPricing(7L, 81L, new BigDecimal("108.00"), 1L, 10L, start, end);

        assertThat(view.unavailableReason).isNull();
        assertThat(view.discountAmount).isEqualByComparingTo("20.00");
    }

    @Test
    void couponForPricingUsesPhysicalMaxDiscountAndActivityStackFields() {
        LocalDateTime start = LocalDateTime.now().plusDays(1);
        LocalDateTime end = start.plusHours(2);
        UserCoupon claimed = coupon(72L, 5L, 7L, "CLAIMED");
        CouponTemplate template = template(5L);
        setTemplateField(template, "discountValue", new BigDecimal("20.00"));
        setTemplateField(template, "thresholdAmount", new BigDecimal("100.00"));
        setTemplateField(template, "maxDiscountAmount", new BigDecimal("15.00"));
        setTemplateField(template, "allowActivityStack", true);
        when(userCouponMapper.selectById(72L)).thenReturn(claimed);
        when(templateMapper.selectById(5L)).thenReturn(template);

        CouponPricingView view = service.couponForPricing(7L, 72L, new BigDecimal("108.00"), 1L, 10L, start, end);

        assertThat(view.discountAmount).isEqualByComparingTo("15.00");
        assertThat(view.allowActivityStack).isTrue();
    }

    @Test
    void markUsedForOrderRejectsAlreadyUsedSameOrderWhenDiscountDiffers() {
        UserCoupon used = coupon(70L, 5L, 7L, "USED");
        used.orderId = 99L;
        used.discountAmount = new BigDecimal("20.00");
        when(userCouponMapper.selectById(70L)).thenReturn(used, used);

        assertThatThrownBy(() -> service.markUsedForOrder(99L, 70L, new BigDecimal("25.00")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("优惠券无法核销");

        verify(templateMapper, never()).incrementUsed(any());
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void releaseForReservationReturnsCouponToClaimedAndReusablePricingState() throws Exception {
        UserCoupon locked = coupon(73L, 5L, 7L, "LOCKED");
        locked.reservationId = 188L;
        locked.lockToken = "lock-188";
        locked.lockedAt = LocalDateTime.now().minusMinutes(5);
        CouponTemplate template = template(5L);
        when(userCouponMapper.selectOne(any(QueryWrapper.class))).thenReturn(locked);
        when(templateMapper.selectById(5L)).thenReturn(template);
        when(userCouponMapper.releaseIfLocked(73L, "超时未支付")).thenReturn(1);

        service.releaseForReservation(188L, "超时未支付");

        ArgumentCaptor<CouponStatusLog> logCaptor = ArgumentCaptor.forClass(CouponStatusLog.class);
        verify(statusLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().fromStatus).isEqualTo("LOCKED");
        assertThat(logCaptor.getValue().toStatus).isEqualTo("CLAIMED");
        assertThat(logCaptor.getValue().reservationId).isEqualTo(188L);
        verify(businessEventService).record(argThat(req -> req.eventType == BusinessEventType.COUPON_RELEASED));
        assertReleaseMapperReturnsClaimedAndClearsLockState();

        UserCoupon reusable = coupon(73L, 5L, 7L, "CLAIMED");
        when(userCouponMapper.selectById(73L)).thenReturn(reusable);

        CouponPricingView view = service.couponForPricing(
            7L, 73L, new BigDecimal("108.00"), 1L, 10L,
            LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(2));

        assertThat(view.unavailableReason).isNull();
        assertThat(view.discountAmount).isEqualByComparingTo("20.00");
    }

    @Test
    void refundForOrderKeepsCouponInRefundedTerminalState() {
        UserCoupon used = coupon(74L, 5L, 7L, "USED");
        used.orderId = 199L;
        CouponTemplate template = template(5L);
        when(userCouponMapper.selectOne(any(QueryWrapper.class))).thenReturn(used);
        when(templateMapper.selectById(5L)).thenReturn(template);
        when(userCouponMapper.markRefundedIfUsed(74L, "退款成功")).thenReturn(1);

        service.markRefundedForOrder(199L, "退款成功");

        ArgumentCaptor<CouponStatusLog> logCaptor = ArgumentCaptor.forClass(CouponStatusLog.class);
        verify(statusLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().fromStatus).isEqualTo("USED");
        assertThat(logCaptor.getValue().toStatus).isEqualTo("REFUNDED");
        verify(businessEventService).record(argThat(req -> req.eventType == BusinessEventType.COUPON_REFUNDED));

        UserCoupon refunded = coupon(74L, 5L, 7L, "REFUNDED");
        when(userCouponMapper.selectById(74L)).thenReturn(refunded);

        CouponPricingView view = service.couponForPricing(
            7L, 74L, new BigDecimal("108.00"), 1L, 10L,
            LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(2));

        assertThat(view.unavailableReason).isEqualTo("优惠券不可用");
    }

    @Test
    void lockReleaseUseAndRefundWriteStatusLogsAndBusinessEvents() {
        UserCoupon claimed = coupon(70L, 5L, 7L, "CLAIMED");
        CouponTemplate template = template(5L);
        when(userCouponMapper.selectById(70L)).thenReturn(claimed);
        when(templateMapper.selectById(5L)).thenReturn(template);
        when(userCouponMapper.lockIfClaimed(70L, 7L, 88L, "lock-88")).thenReturn(1);

        service.lockForReservation(7L, 70L, 88L, "lock-88");

        verify(userCouponMapper).lockIfClaimed(70L, 7L, 88L, "lock-88");
        verify(businessEventService).record(argThat(req -> req.eventType == BusinessEventType.COUPON_LOCKED));

        UserCoupon locked = coupon(70L, 5L, 7L, "LOCKED");
        locked.reservationId = 88L;
        when(userCouponMapper.selectOne(any(QueryWrapper.class))).thenReturn(locked);
        when(userCouponMapper.releaseIfLocked(70L, "用户取消")).thenReturn(1);

        service.releaseForReservation(88L, "用户取消");

        verify(userCouponMapper).releaseIfLocked(70L, "用户取消");
        verify(businessEventService).record(argThat(req -> req.eventType == BusinessEventType.COUPON_RELEASED));

        when(userCouponMapper.selectById(70L)).thenReturn(locked);
        when(userCouponMapper.useIfLocked(70L, 99L, new BigDecimal("20.00"))).thenReturn(1);

        service.markUsedForOrder(99L, 70L, new BigDecimal("20.00"));

        verify(userCouponMapper).useIfLocked(70L, 99L, new BigDecimal("20.00"));
        verify(templateMapper).incrementUsed(5L);
        verify(businessEventService).record(argThat(req -> req.eventType == BusinessEventType.COUPON_USED));

        UserCoupon used = coupon(70L, 5L, 7L, "USED");
        used.orderId = 99L;
        when(userCouponMapper.selectOne(any(QueryWrapper.class))).thenReturn(used);
        when(userCouponMapper.markRefundedIfUsed(70L, "退款成功")).thenReturn(1);

        service.markRefundedForOrder(99L, "退款成功");

        verify(userCouponMapper).markRefundedIfUsed(70L, "退款成功");
        verify(businessEventService).record(argThat(req -> req.eventType == BusinessEventType.COUPON_REFUNDED));

        ArgumentCaptor<CouponStatusLog> logCaptor = ArgumentCaptor.forClass(CouponStatusLog.class);
        verify(statusLogMapper, org.mockito.Mockito.atLeast(4)).insert(logCaptor.capture());
        assertThat(logCaptor.getAllValues()).extracting(log -> log.toStatus)
            .contains("LOCKED", "CLAIMED", "USED", "REFUNDED");
    }

    @Test
    void userApisOnlyUseCurrentUser() throws NoSuchMethodException {
        CouponTemplate template = template(5L);
        when(templateMapper.selectById(5L)).thenReturn(template);
        when(templateMapper.selectByIdForUpdate(5L)).thenReturn(template);
        when(userCouponMapper.countUserCouponsForTemplate(5L, 7L)).thenReturn(0L);
        when(templateMapper.incrementIssuedIfAvailableForUser(5L, 7L, 1)).thenReturn(1);
        when(userCouponMapper.insert(any(UserCoupon.class))).thenAnswer(invocation -> {
            UserCoupon coupon = invocation.getArgument(0);
            coupon.id = 70L;
            return 1;
        });

        UserCouponResponse claimed = service.claim(5L);

        assertThat(claimed.id).isEqualTo(70L);
        ArgumentCaptor<UserCoupon> couponCaptor = ArgumentCaptor.forClass(UserCoupon.class);
        verify(userCouponMapper).insert(couponCaptor.capture());
        assertThat(couponCaptor.getValue().userId).isEqualTo(7L);
        verify(businessEventService).record(argThat(req -> req.eventType == BusinessEventType.COUPON_CLAIMED));

        PageQuery query = new PageQuery();
        when(userCouponMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenAnswer(invocation -> {
            Page<UserCoupon> page = invocation.getArgument(0);
            page.setRecords(List.of(coupon(70L, 5L, 7L, "CLAIMED")));
            page.setTotal(1);
            return page;
        });
        when(templateMapper.selectBatchIds(any())).thenReturn(List.of(template));

        PageResult<UserCouponResponse> page = service.myCoupons(query, "CLAIMED");

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRecords()).extracting(item -> item.id).containsExactly(70L);

        assertControllerContract();
    }

    @Test
    void myCouponsExposesSourceTypeAndReleasedAt() {
        CouponTemplate template = template(5L);
        UserCoupon released = coupon(70L, 5L, 7L, "CLAIMED");
        released.sourceType = "MEMBER_REWARD";
        released.releasedAt = LocalDateTime.now().minusHours(2);
        when(userCouponMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenAnswer(invocation -> {
            Page<UserCoupon> page = invocation.getArgument(0);
            page.setRecords(List.of(released));
            page.setTotal(1);
            return page;
        });
        when(templateMapper.selectBatchIds(any())).thenReturn(List.of(template));

        PageResult<UserCouponResponse> page = service.myCoupons(new PageQuery(), null);

        UserCouponResponse row = page.getRecords().get(0);
        assertThat(row.sourceType).isEqualTo("MEMBER_REWARD");
        assertThat(row.releasedAt).isEqualTo(released.releasedAt);
    }

    @Test
    void claimNoticeUsesFixedPriceDescriptionForFixedCoupon() {
        // A26-P3-12：FIXED 券 discountValue 是券后一口价，通知文案必须是"券后一口价 X 元"，
        // 不能误用"立减 X 元"（200 元订单用 50 元 FIXED 券实际优惠 150 元，"立减 50 元"与计价语义相反）。
        CouponTemplate fixed = template(5L);
        fixed.templateCode = "B3_FIXED_50";
        fixed.templateName = "B3 固定价格50";
        fixed.couponType = "FIXED";
        setTemplateField(fixed, "discountValue", new BigDecimal("50.00"));
        when(templateMapper.selectById(5L)).thenReturn(fixed);
        when(templateMapper.selectByIdForUpdate(5L)).thenReturn(fixed);
        when(userCouponMapper.countUserCouponsForTemplate(5L, 7L)).thenReturn(0L);
        when(templateMapper.incrementIssuedIfAvailableForUser(5L, 7L, 1)).thenReturn(1);
        when(userCouponMapper.insert(any(UserCoupon.class))).thenAnswer(invocation -> {
            UserCoupon coupon = invocation.getArgument(0);
            coupon.id = 70L;
            return 1;
        });

        service.claim(5L);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(noticeService).createMessage(eq(7L), eq("优惠券领取成功"), contentCaptor.capture(),
            eq("COUPON"), eq(70L));
        String content = contentCaptor.getValue();
        assertThat(content).contains("券后一口价 50.00 元");
        assertThat(content).doesNotContain("立减 50.00 元");
        assertThat(content).doesNotContain("立减");
    }

    @Test
    void claimNoticeUsesFaceValueDescriptionForCashCouponAndPercentDescriptionForPercentCoupon() {
        // A26-P3-12：CASH/PERCENT 文案保持原语义；只 FIXED 文案需修正，
        // 同一测试覆盖三种券类型的文案分支，避免单点修改引入回归。
        CouponTemplate cash = template(5L);
        when(templateMapper.selectById(5L)).thenReturn(cash);
        when(templateMapper.selectByIdForUpdate(5L)).thenReturn(cash);
        when(userCouponMapper.countUserCouponsForTemplate(5L, 7L)).thenReturn(0L);
        when(templateMapper.incrementIssuedIfAvailableForUser(5L, 7L, 1)).thenReturn(1);
        when(userCouponMapper.insert(any(UserCoupon.class))).thenAnswer(invocation -> {
            UserCoupon coupon = invocation.getArgument(0);
            coupon.id = 70L;
            return 1;
        });

        service.claim(5L);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(noticeService).createMessage(eq(7L), eq("优惠券领取成功"), contentCaptor.capture(),
            eq("COUPON"), eq(70L));
        // 默认 template() 是 CASH + discountValue=20.00，应输出"面值 20.00 元"，不是"立减"。
        assertThat(contentCaptor.getValue()).contains("面值 20.00 元");
        assertThat(contentCaptor.getValue()).doesNotContain("立减");
    }

    @Test
    void refundMapperKeepsOrderEvidenceAndMovesCouponToRefundedTerminalState() throws Exception {
        Method method = UserCouponMapper.class.getDeclaredMethod("markRefundedIfUsed", Long.class, String.class);
        org.apache.ibatis.annotations.Update update = method.getAnnotation(org.apache.ibatis.annotations.Update.class);

        assertThat(update).isNotNull();
        assertThat(String.join("\n", update.value()))
            .contains("status = 'REFUNDED'")
            .contains("refunded_at")
            .doesNotContain("order_id = null");
    }

    private void assertControllerContract() throws NoSuchMethodException {
        var claimable = CouponController.class.getDeclaredMethod("claimable", Long.class, Long.class);
        assertThat(claimable.getAnnotation(GetMapping.class).value()).containsExactly("/api/coupons/claimable");
        assertThat(claimable.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('coupon:me')");

        var myCoupons = CouponController.class.getDeclaredMethod("myCoupons", PageQuery.class, String.class);
        assertThat(myCoupons.getAnnotation(GetMapping.class).value()).containsExactly("/api/coupons/my");
        assertThat(myCoupons.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('coupon:me')");

        var claim = CouponController.class.getDeclaredMethod("claim", Long.class);
        assertThat(claim.getAnnotation(PostMapping.class).value()).containsExactly("/api/coupons/{templateId}/claim");
        assertThat(claim.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('coupon:me')");
    }

    private void authenticate(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private void assertReleaseMapperReturnsClaimedAndClearsLockState() throws Exception {
        Method method = UserCouponMapper.class.getDeclaredMethod("releaseIfLocked", Long.class, String.class);
        org.apache.ibatis.annotations.Update update = method.getAnnotation(org.apache.ibatis.annotations.Update.class);
        assertThat(update).isNotNull();
        assertThat(String.join("\n", update.value()))
            .contains("status = 'CLAIMED'")
            .contains("reservation_id = null")
            .contains("lock_token = null")
            .contains("locked_at = null");
    }

    private CouponTemplate template(Long id) {
        CouponTemplate template = new CouponTemplate();
        template.id = id;
        template.templateCode = "B3_CASH_20";
        template.templateName = "B3 满百减二十";
        template.couponType = "CASH";
        template.venueId = 1L;
        template.spaceId = 10L;
        setTemplateField(template, "discountValue", new BigDecimal("20.00"));
        setTemplateField(template, "thresholdAmount", new BigDecimal("100.00"));
        setTemplateField(template, "maxDiscountAmount", null);
        setTemplateField(template, "totalQuantity", 100);
        setTemplateField(template, "issuedQuantity", 0);
        setTemplateField(template, "usedQuantity", 0);
        setTemplateField(template, "allowActivityStack", true);
        template.perUserLimit = 1;
        template.claimStartTime = LocalDateTime.now().minusDays(1);
        template.claimEndTime = LocalDateTime.now().plusDays(1);
        template.validStartTime = LocalDateTime.now().minusDays(1);
        template.validEndTime = LocalDateTime.now().plusDays(30);
        template.status = "ACTIVE";
        template.deleted = 0;
        return template;
    }

    private UserCoupon coupon(Long id, Long templateId, Long userId, String status) {
        UserCoupon coupon = new UserCoupon();
        coupon.id = id;
        coupon.templateId = templateId;
        coupon.userId = userId;
        coupon.status = status;
        coupon.claimTime = LocalDateTime.now().minusHours(1);
        coupon.validStartTime = LocalDateTime.now().minusDays(1);
        coupon.validEndTime = LocalDateTime.now().plusDays(30);
        coupon.discountAmount = new BigDecimal("0.00");
        coupon.sourceType = "USER";
        coupon.deleted = 0;
        return coupon;
    }

    private void setTemplateField(CouponTemplate template, String fieldName, Object value) {
        try {
            var field = CouponTemplate.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(template, value);
        } catch (NoSuchFieldException ex) {
            throw new AssertionError("CouponTemplate should define B3 field " + fieldName, ex);
        } catch (IllegalAccessException ex) {
            throw new AssertionError("Cannot set CouponTemplate field " + fieldName, ex);
        }
    }
}
