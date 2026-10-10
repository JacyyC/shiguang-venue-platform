package com.example.venue.reservation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.checkin.dto.CompletionDecision;
import com.example.venue.checkin.dto.RescheduleRequest;
import com.example.venue.checkin.dto.ReservationRescheduleResponse;
import com.example.venue.checkin.entity.ReservationRescheduleRequest;
import com.example.venue.checkin.service.ReservationLifecycleService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.common.PageResult;
import com.example.venue.funnel.service.FunnelEventService;
import com.example.venue.inventory.dto.BookabilityDecision;
import com.example.venue.inventory.dto.InventorySlot;
import com.example.venue.inventory.service.VenueInventoryService;
import com.example.venue.member.dto.MemberAccountResponse;
import com.example.venue.member.dto.MemberLevelResponse;
import com.example.venue.member.dto.MemberPricingContext;
import com.example.venue.member.dto.PricingPreviewResponse;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.order.service.OrderService;
import com.example.venue.reservation.dto.ReservationQuery;
import com.example.venue.pricing.AmountBreakdown;
import com.example.venue.promotion.dto.CouponPricingView;
import com.example.venue.promotion.dto.PriceRuleResponse;
import com.example.venue.promotion.dto.PromotionCampaignResponse;
import com.example.venue.promotion.dto.PromotionPricingContext;
import com.example.venue.promotion.service.CouponService;
import com.example.venue.promotion.service.PromotionPricingService;
import com.example.venue.reservation.dto.AvailabilitySlot;
import com.example.venue.reservation.dto.CreateReservationRequest;
import com.example.venue.reservation.dto.VenueAvailabilitySummary;
import com.example.venue.reservation.dto.ReservationQuoteRequest;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.entity.ReservationOccupancy;
import com.example.venue.reservation.entity.ReservationStatusLog;
import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.mapper.ReservationCreateCommandMapper;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.reservation.mapper.ReservationStatusLogMapper;
import com.example.venue.reservation.service.ReservationCreateIdempotencyService;
import com.example.venue.reservation.service.ReservationService;
import com.example.venue.reservation.service.ReservationLifecycleChangePublisher;
import com.example.venue.resource.enums.AllocationConflictCode;
import com.example.venue.resource.exception.ResourceAllocationConflictException;
import com.example.venue.resource.service.ResourceAllocationService;
import com.example.venue.system.entity.SysConfig;
import com.example.venue.system.mapper.SysConfigMapper;
import com.example.venue.system.service.SysConfigService;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.mapper.VenueMapper;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.example.venue.venue.service.VenueAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.venue.pricing.PricingSnapshotFactory;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private ReservationOccupancyMapper occupancyMapper;
    @Mock
    private ReservationStatusLogMapper statusLogMapper;
    @Mock
    private VenueMapper venueMapper;
    @Mock
    private VenueSpaceMapper spaceMapper;
    @Mock
    private VenueAccessService venueAccessService;
    @Mock
    private SysConfigMapper configMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private OrderService orderService;
    @Mock
    private NoticeService noticeService;
    @Mock
    private BusinessEventService businessEventService;
    @Mock
    private FunnelEventService funnelEventService;
    @Mock
    private VenueInventoryService inventoryService;
    @Mock
    private PromotionPricingService promotionPricingService;
    @Mock
    private CouponService couponService;
    @Mock
    private ReservationLifecycleService lifecycleService;
    @Mock
    private ResourceAllocationService resourceAllocationService;
    @Mock
    private ReservationLifecycleChangePublisher lifecycleChangePublisher;
    @Mock
    private ReservationCreateCommandMapper createCommandMapper;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() throws Exception {
        SysConfigService sysConfigService = new SysConfigService(configMapper);
        // DC-03：用真实 EntityExistenceService 注入 mock mapper，复用下方 selectByIdForUpdate→selectById 委托 mock；
        // userMapper 传 null（ReservationService 不调 requireActiveUser）
        EntityExistenceService entityExistenceService = new EntityExistenceService(null, venueMapper, spaceMapper);
        reservationService = new ReservationService(reservationMapper, occupancyMapper, statusLogMapper, venueMapper,
            spaceMapper, entityExistenceService, venueAccessService, sysConfigService, redisTemplate, orderService, lifecycleService, noticeService,
            businessEventService, funnelEventService, inventoryService, promotionPricingService, couponService,
            new PricingSnapshotFactory(new ObjectMapper().findAndRegisterModules()),
            // 请求身份的真实约束由数据库唯一索引保证，集成测试覆盖；此处只保留归一化与重放分支所需的实例。
            new ReservationCreateIdempotencyService(createCommandMapper), null);
        // Lee 新增 @Lazy 自注入 self，逐条自动完成走 self 代理调用以启用独立事务；
        // 单元测试中将其指向真实实例，使 completeStartedReservationsBatch 调用走真实方法语义。
        java.lang.reflect.Field selfField = ReservationService.class.getDeclaredField("self");
        selfField.setAccessible(true);
        selfField.set(reservationService, reservationService);
        reservationService.setLifecycleChangePublisher(lifecycleChangePublisher);
        lenient().when(reservationMapper.selectByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(invocation -> reservationMapper.selectById(invocation.getArgument(0)));
        lenient().when(spaceMapper.selectByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(invocation -> spaceMapper.selectById(invocation.getArgument(0)));
        lenient().when(venueMapper.selectByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(invocation -> venueMapper.selectById(invocation.getArgument(0)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(7L, "tester", Set.of("USER"), Set.of()), null, List.of()));
        lenient().when(configMapper.findByKey("reservation.max_slots_per_booking")).thenReturn(config("1"));
        lenient().when(configMapper.findByKey("reservation.advance_days")).thenReturn(config("7"));
        lenient().when(configMapper.findByKey("reservation.free_cancel_hours")).thenReturn(config("24"));
        lenient().when(configMapper.findByKey("reservation.lock_seconds")).thenReturn(config("20"));
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        lenient().when(promotionPricingService.previewForReservation(any(Long.class), any(Long.class), any(Long.class),
                any(LocalDateTime.class), any(LocalDateTime.class), any(BigDecimal.class), org.mockito.ArgumentMatchers.<Long>any()))
            .thenAnswer(invocation -> promotionPricingContext(
                invocation.getArgument(3),
                invocation.getArgument(4),
                invocation.getArgument(5),
                "100.00",
                "0.00",
                "0.00",
                "0.00",
                "100.00"
            ));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void myReservationsEnrichesVenueAndOrderInBatch() {
        Reservation first = reservation(101L, 20L, "PENDING_PAYMENT");
        Reservation second = reservation(102L, 21L, "PAID");
        Page<Reservation> page = new Page<>(1, 20);
        page.setRecords(List.of(first, second));
        page.setTotal(2);
        when(reservationMapper.selectPage(any(Page.class), any())).thenReturn(page);
        Venue venueA = activeVenue();
        venueA.id = 20L;
        venueA.name = "拾光球馆";
        Venue venueB = activeVenue();
        venueB.id = 21L;
        venueB.name = "拾光自习室";
        when(venueMapper.selectBatchIds(any())).thenReturn(List.of(venueA, venueB));
        when(orderService.findOrderIdsByReservationIds(any())).thenReturn(Map.of(101L, 501L));

        PageResult<Reservation> result = reservationService.myReservations(new ReservationQuery());

        assertThat(result.getRecords()).extracting(item -> item.venueName)
            .containsExactly("拾光球馆", "拾光自习室");
        assertThat(result.getRecords()).extracting(item -> item.orderId)
            .containsExactly(501L, null);
        verify(venueMapper).selectBatchIds(argThat(ids -> ids.containsAll(List.of(20L, 21L)) && ids.size() == 2));
        verify(orderService).findOrderIdsByReservationIds(argThat(ids -> ids.containsAll(List.of(101L, 102L)) && ids.size() == 2));
    }

    @Test
    void pricingPreviewUsesPromotionPricingWithoutLockingCouponOrWritingReservation() {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);
        CreateReservationRequest request = createRequest(start, end);
        setB3Field(request, "userCouponId", 70L);
        when(configMapper.findByKey("reservation.max_slots_per_booking")).thenReturn(config("2"));
        VenueSpace space = activeSpace();
        space.pricePerHour = new BigDecimal("60.00");
        when(spaceMapper.selectById(10L)).thenReturn(space);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookable(10L, start, end)).thenReturn(BookabilityDecision.ok());
        when(promotionPricingService.previewForReservation(7L, 20L, 10L, start, end, new BigDecimal("60.00"), 70L))
            .thenReturn(promotionPricingContext(start, end));

        PricingPreviewResponse response = reservationService.pricingPreview(request);

        assertThat(response.originalAmount).isEqualByComparingTo("120.00");
        assertThat(response.memberDiscountAmount).isEqualByComparingTo("5.00");
        assertThat(response.couponDiscountAmount).isEqualByComparingTo("20.00");
        assertThat(response.activityDiscountAmount).isEqualByComparingTo("10.00");
        assertThat(response.payableAmount).isEqualByComparingTo("85.00");
        assertThat(getB3Field(response, "pricingVersion")).isEqualTo("B3_PROMOTION_PRICING_V1");
        assertThat(getB3Field(response, "promotionCampaignId")).isEqualTo(8L);
        assertThat(getB3Field(response, "priceRuleId")).isEqualTo(9L);
        assertThat(getB3Field(response, "couponTemplateId")).isEqualTo(5L);
        assertThat(getB3Field(response, "userCouponId")).isEqualTo(70L);
        assertThat(getB3Field(response, "couponUnavailableReason")).isNull();
        assertThat(response.pricingSnapshotJson)
            .contains("\"pricingVersion\":\"B3_PROMOTION_PRICING_V1\"")
            .contains("\"coupon\"")
            .contains("\"payableAmount\":85.00");
        verify(promotionPricingService).previewForReservation(7L, 20L, 10L, start, end, new BigDecimal("60.00"), 70L);
        verify(couponService, never()).lockForReservation(any(), any(), any(), anyString());
        verify(reservationMapper, never()).insert(any(Reservation.class));
    }

    @Test
    void quotePricesFromSelectionFactsWithoutContactAndWithoutReservationSideEffects() {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);
        ReservationQuoteRequest request = quoteRequest(start, end);
        request.userCouponId = 70L;
        when(configMapper.findByKey("reservation.max_slots_per_booking")).thenReturn(config("2"));
        VenueSpace space = activeSpace();
        space.pricePerHour = new BigDecimal("60.00");
        when(spaceMapper.selectById(10L)).thenReturn(space);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookable(10L, start, end)).thenReturn(BookabilityDecision.ok());
        when(promotionPricingService.previewForReservation(7L, 20L, 10L, start, end, new BigDecimal("60.00"), 70L))
            .thenReturn(promotionPricingContext(start, end));

        PricingPreviewResponse response = reservationService.quote(request);

        assertThat(response.originalAmount).isEqualByComparingTo("120.00");
        assertThat(response.payableAmount).isEqualByComparingTo("85.00");
        verify(promotionPricingService).previewForReservation(7L, 20L, 10L, start, end, new BigDecimal("60.00"), 70L);
        verify(couponService, never()).lockForReservation(any(), any(), any(), anyString());
        verify(orderService, never()).createPendingOrder(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(reservationMapper, never()).insert(any(Reservation.class));
        verify(occupancyMapper, never()).insert(any(ReservationOccupancy.class));
    }

    @Test
    void quoteSurfacesResourceOverlapAsConflictAndOtherUnsellableAsBadRequest() {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);
        when(configMapper.findByKey("reservation.max_slots_per_booking")).thenReturn(config("2"));
        VenueSpace space = activeSpace();
        when(spaceMapper.selectById(10L)).thenReturn(space);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());

        when(inventoryService.checkBookable(10L, start, end))
            .thenReturn(BookabilityDecision.rejected("RESERVED", "EXCLUSIVE_OVERLAP", "该时段已被整空间预约"));
        assertThatThrownBy(() -> reservationService.quote(quoteRequest(start, end)))
            .isInstanceOfSatisfying(ResourceAllocationConflictException.class,
                exception -> assertThat(exception.getConflictCode()).isEqualTo(AllocationConflictCode.EXCLUSIVE_OVERLAP))
            .hasMessage("该时段已被整空间预约");

        when(inventoryService.checkBookable(10L, start, end))
            .thenReturn(BookabilityDecision.rejected("CLOSED", "VENUE_CLOSED", "该时段场馆不营业"));
        assertThatThrownBy(() -> reservationService.quote(quoteRequest(start, end)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("该时段场馆不营业");
    }

    @Test
    void myReservationReadsOwnRecordWithEnrichedNamesAndRejectsOtherUsers() {
        Reservation owned = reservation(99L, "PENDING_REVIEW");
        when(reservationMapper.selectById(99L)).thenReturn(owned);
        Venue venue = activeVenue();
        venue.name = "拾光球馆";
        when(venueMapper.selectBatchIds(any())).thenReturn(List.of(venue));
        when(orderService.findOrderIdsByReservationIds(any())).thenReturn(Map.of(99L, 501L));

        Reservation result = reservationService.myReservation(99L);

        assertThat(result.venueName).isEqualTo("拾光球馆");
        assertThat(result.orderId).isEqualTo(501L);

        owned.userId = 8L;
        assertThatThrownBy(() -> reservationService.myReservation(99L))
            .isInstanceOf(BusinessException.class)
            .hasMessage("不能查看他人的预约");

        when(reservationMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> reservationService.myReservation(404L))
            .isInstanceOf(BusinessException.class)
            .hasMessage("预约不存在");
    }

    @Test
    void createReservationLocksCouponAndStoresB3SnapshotAndPromotionFields() {
        LocalDateTime startTime = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateReservationRequest request = createRequest(startTime, startTime.plusHours(2));
        setB3Field(request, "userCouponId", 70L);
        when(configMapper.findByKey("reservation.max_slots_per_booking")).thenReturn(config("2"));
        VenueSpace space = activeSpace();
        space.pricePerHour = new BigDecimal("60.00");
        when(spaceMapper.selectById(10L)).thenReturn(space);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookable(10L, startTime, startTime.plusHours(2))).thenReturn(BookabilityDecision.ok());
        when(promotionPricingService.previewForReservation(7L, 20L, 10L, startTime, startTime.plusHours(2),
                new BigDecimal("60.00"), 70L))
            .thenReturn(promotionPricingContext(startTime, startTime.plusHours(2)));
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of());
        doAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.id = 99L;
            return 1;
        }).when(reservationMapper).insert(any(Reservation.class));

        Reservation created = reservationService.create(request);

        ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationMapper).insert(reservationCaptor.capture());
        Reservation insertedReservation = reservationCaptor.getValue();
        assertThat(insertedReservation.id).isEqualTo(99L);
        assertThat(created.id).isEqualTo(99L);
        assertThat(insertedReservation.amount).isEqualByComparingTo("85.00");
        assertThat(insertedReservation.originalAmount).isEqualByComparingTo("120.00");
        assertThat(insertedReservation.memberDiscountAmount).isEqualByComparingTo("5.00");
        assertThat(insertedReservation.couponDiscountAmount).isEqualByComparingTo("20.00");
        assertThat(insertedReservation.activityDiscountAmount).isEqualByComparingTo("10.00");
        assertThat(insertedReservation.payableAmount).isEqualByComparingTo("85.00");
        assertThat(getB3Field(insertedReservation, "userCouponId")).isEqualTo(70L);
        assertThat(getB3Field(insertedReservation, "couponTemplateId")).isEqualTo(5L);
        assertThat(getB3Field(insertedReservation, "promotionCampaignId")).isEqualTo(8L);
        assertThat(getB3Field(insertedReservation, "priceRuleId")).isEqualTo(9L);
        assertThat(insertedReservation.pricingSnapshotJson)
            .contains("\"pricingVersion\":\"B3_PROMOTION_PRICING_V1\"")
            .contains("\"coupon\"")
            .contains("\"payableAmount\":85.00");
        verify(reservationMapper).updateById(org.mockito.ArgumentMatchers.<Reservation>argThat(reservation ->
            reservation.id.equals(99L)
                && reservation.couponLockToken != null
                && reservation.couponLockToken.startsWith("reservation-99-")));
        verify(couponService).lockForReservation(eq(7L), eq(70L), eq(99L), org.mockito.ArgumentMatchers.startsWith("reservation-99-"));
        verify(spaceMapper).selectByIdForUpdate(10L);
        verify(lifecycleChangePublisher).publishSubmitted(argThat(reservation ->
            reservation.id.equals(99L) && reservation.userId.equals(7L)
                && ReservationStatus.PENDING_REVIEW.name().equals(reservation.status)));
    }

    @Test
    void createAcquiresSpaceAndVenueRowLockBeforeInventoryCheck() {
        // A26-P2-09：create 首条 SQL 必须是 FOR UPDATE（场地行锁），避免普通 selectById 提前建立
        // RR 读视图导致后续 ensureBookableByInventory→checkBookableInternal 的日历/预约/块/维护
        // 查询看不到加锁等待期间并发提交的不可售块/闭馆/停用，栅栏失效。
        // 修复后场地行锁与场馆行锁都在库存检查之前获取。
        LocalDateTime startTime = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateReservationRequest request = createRequest(startTime, startTime.plusHours(1));
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookable(10L, startTime, startTime.plusHours(1))).thenReturn(BookabilityDecision.ok());
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of());
        doAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.id = 99L;
            return 1;
        }).when(reservationMapper).insert(any(Reservation.class));

        reservationService.create(request);

        InOrder inOrder = inOrder(spaceMapper, venueMapper, inventoryService);
        inOrder.verify(spaceMapper).selectByIdForUpdate(10L);
        inOrder.verify(venueMapper).selectByIdForUpdate(20L);
        inOrder.verify(inventoryService).checkBookable(10L, startTime, startTime.plusHours(1));
    }

    @Test
    void createKeepsOpaqueRedisOwnerLockUntilTransactionCompletion() {
        LocalDateTime startTime = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateReservationRequest request = createRequest(startTime, startTime.plusHours(1));
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookable(10L, startTime, startTime.plusHours(1))).thenReturn(BookabilityDecision.ok());
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of());
        doAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.id = 99L;
            return 1;
        }).when(reservationMapper).insert(any(Reservation.class));

        TransactionSynchronizationManager.initSynchronization();
        try {
            reservationService.create(request);

            ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOperations).setIfAbsent(anyString(), tokenCaptor.capture(), any(Duration.class));
            assertThat(tokenCaptor.getValue()).isNotEqualTo("7");
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                assertThatCode(() -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED))
                    .doesNotThrowAnyException();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void rejectReservationReleasesLockedCoupon() {
        Reservation reservation = pendingReviewReservation();
        setB3Field(reservation, "userCouponId", 70L);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        reservationService.approve(99L, false, "资料不完整");

        verify(reservationMapper).selectByIdForUpdate(99L);
        verify(couponService).releaseForReservation(99L, "资料不完整");
        verify(occupancyMapper).releaseByReservationId(99L);
        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "RESERVATION_REJECTED".equals(event.eventType())
                && "RESERVATION_REJECTED:99".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && "预约审核未通过".equals(event.inAppTitle())
                && "资料不完整".equals(event.inAppContent())
                && "RESERVATION".equals(event.businessType())
                && Long.valueOf(99L).equals(event.businessId())
                && Map.of("reservationId", "99", "reason", "资料不完整").equals(event.templateModel())));
    }

    @Test
    void rejectReservationSanitizesPrivateReasonBeforeUserNotification() {
        Reservation reservation = pendingReviewReservation();
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        String privateReason = "<b>资料缺失</b> 联系 13812345678 或 user@example.test " + "补".repeat(520);

        reservationService.approve(99L, false, privateReason);

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(noticeService).createBusinessNotification(eventCaptor.capture());
        NotificationEvent event = eventCaptor.getValue();
        assertThat(reservation.rejectReason).isEqualTo(privateReason);
        assertThat(event.inAppContent())
            .hasSize(500)
            .contains("资料缺失")
            .contains("138****5678")
            .contains("[邮箱已隐藏]")
            .doesNotContain("<b>", "</b>", "13812345678", "user@example.test");
        assertThat(event.templateModel().get("reason")).isEqualTo(event.inAppContent());
    }

    @Test
    void cancelUnpaidReservationReleasesLockedCoupon() {
        Reservation reservation = pendingReviewReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        setB3Field(reservation, "userCouponId", 70L);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of());

        reservationService.cancel(99L, null, false);

        verify(couponService).releaseForReservation(99L, "预约取消");
        verify(orderService).closeOrderForCanceledReservation(99L, null);
        verify(occupancyMapper).releaseByReservationId(99L);
        verify(lifecycleService, never()).freezeCredentialForReservation(any(), anyString());
        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "RESERVATION_CANCELED".equals(event.eventType())
                && "RESERVATION_CANCELED:99:CANCELED".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && Map.of("reservationId", "99", "status", "CANCELED", "source", "USER")
                    .equals(event.templateModel())));
    }

    @Test
    void cancelReleasesThroughUnifiedResourceServiceWithStableReasonWhenAvailable() {
        Reservation reservation = pendingReviewReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of());
        reservationService.setResourceAllocationService(resourceAllocationService);

        reservationService.cancel(99L, "行程变化", false);

        verify(resourceAllocationService).release(99L, "RESERVATION_CANCELED");
        verify(occupancyMapper, never()).releaseByReservationId(any());
    }

    @Test
    void adminCancellationPassesServerDerivedSourceToCustomerTemplateModel() {
        Reservation reservation = pendingReviewReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        reservationService.cancel(99L, "管理员取消", true);

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "RESERVATION_CANCELED".equals(event.eventType())
                && "RESERVATION_CANCELED:99:CANCELED".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && Map.of("reservationId", "99", "status", "CANCELED", "source", "ADMIN")
                    .equals(event.templateModel())));
    }

    @Test
    void userCancellationKeepsManagerInformationInAppOnly() {
        Reservation reservation = pendingReviewReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of(8L));

        reservationService.cancel(99L, "行程变化", false);

        verify(noticeService).createMessage(8L, "用户取消预约", "行程变化", "RESERVATION", 99L);
        verify(noticeService, never()).createBusinessNotification(argThat((NotificationEvent event) ->
            Long.valueOf(8L).equals(event.recipientUserId())));
    }

    @Test
    void cancelPaidReservationWithUsedCouponDoesNotReleaseCouponAndStartsRefundFlow() {
        Reservation reservation = pendingReviewReservation();
        reservation.status = ReservationStatus.PAID.name();
        reservation.startTime = LocalDateTime.now().plusDays(3);
        reservation.endTime = reservation.startTime.plusHours(1);
        setB3Field(reservation, "userCouponId", 70L);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of());

        assertThatCode(() -> reservationService.cancel(99L, "用户取消", false))
            .doesNotThrowAnyException();

        verify(couponService, never()).releaseForReservation(any(), anyString());
        verify(orderService).closeOrderForCanceledReservation(99L, "用户取消");
        verify(occupancyMapper).releaseByReservationId(99L);
    }

    @Test
    void cancelPaidReservationDelegatesCredentialInvalidationReasonToOrderClosePath() {
        Reservation reservation = pendingReviewReservation();
        reservation.status = ReservationStatus.PAID.name();
        reservation.startTime = LocalDateTime.now().plusDays(3);
        reservation.endTime = reservation.startTime.plusHours(1);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of());

        reservationService.cancel(99L, "用户取消", false);

        verify(orderService).closeOrderForCanceledReservation(99L, "用户取消");
        verify(lifecycleService, never()).freezeCredentialForReservation(any(), anyString());
    }

    @Test
    void createRejectsSelectedUnavailableCouponBeforeInsertOrLock() {
        LocalDateTime startTime = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateReservationRequest request = createRequest(startTime, startTime.plusHours(2));
        setB3Field(request, "userCouponId", 70L);
        when(configMapper.findByKey("reservation.max_slots_per_booking")).thenReturn(config("2"));
        VenueSpace space = activeSpace();
        space.pricePerHour = new BigDecimal("60.00");
        when(spaceMapper.selectById(10L)).thenReturn(space);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookable(10L, startTime, startTime.plusHours(2))).thenReturn(BookabilityDecision.ok());
        when(promotionPricingService.previewForReservation(7L, 20L, 10L, startTime, startTime.plusHours(2),
                new BigDecimal("60.00"), 70L))
            .thenReturn(unavailableCouponPricingContext(startTime, startTime.plusHours(2)));

        assertThatThrownBy(() -> reservationService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("未达到优惠券使用门槛");

        verify(reservationMapper, never()).insert(any(Reservation.class));
        verify(couponService, never()).lockForReservation(any(), any(), any(), anyString());
        verify(lifecycleChangePublisher, never()).publishSubmitted(any(Reservation.class));
    }

    @Test
    void createWritesReservationCreatedBusinessEventAfterInitialStatusLogAndBeforeAutoApproval() {
        LocalDateTime startTime = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateReservationRequest request = createRequest(startTime, startTime.plusHours(1));
        when(configMapper.findByKey("reservation.auto_approval")).thenReturn(config("true"));
        when(configMapper.findByKey("payment.timeout_minutes")).thenReturn(config("30"));
        VenueSpace space = activeSpace();
        space.pricePerHour = new BigDecimal("120.00");
        when(spaceMapper.selectById(10L)).thenReturn(space);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookable(10L, startTime, startTime.plusHours(1))).thenReturn(BookabilityDecision.ok());
        when(promotionPricingService.previewForReservation(7L, 20L, 10L, startTime, startTime.plusHours(1),
                new BigDecimal("120.00"), null))
            .thenReturn(promotionPricingContext(startTime, startTime.plusHours(1), new BigDecimal("120.00"),
                "120.00", "12.00", "0.00", "0.00", "108.00"));
        doAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.id = 99L;
            return 1;
        }).when(reservationMapper).insert(any(Reservation.class));
        when(reservationMapper.selectById(99L)).thenAnswer(invocation -> {
            Reservation reservation = pendingReviewReservation();
            reservation.status = ReservationStatus.PENDING_PAYMENT.name();
            return reservation;
        });

        reservationService.create(request);

        ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationMapper).insert(reservationCaptor.capture());
        Reservation insertedReservation = reservationCaptor.getValue();
        assertThat(insertedReservation.amount).isEqualByComparingTo("108.00");
        assertThat(insertedReservation.originalAmount).isEqualByComparingTo("120.00");
        assertThat(insertedReservation.memberDiscountAmount).isEqualByComparingTo("12.00");
        assertThat(insertedReservation.couponDiscountAmount).isEqualByComparingTo("0.00");
        assertThat(insertedReservation.activityDiscountAmount).isEqualByComparingTo("0.00");
        assertThat(insertedReservation.payableAmount).isEqualByComparingTo("108.00");
        assertThat(insertedReservation.paidAmount).isEqualByComparingTo("0.00");
        assertThat(insertedReservation.feeAmount).isEqualByComparingTo("0.00");
        assertThat(insertedReservation.netAmount).isEqualByComparingTo("0.00");
        assertThat(insertedReservation.pricingSnapshotJson)
            .contains("\"pricingVersion\":\"B3_PROMOTION_PRICING_V1\"")
            .contains("\"venueId\":20")
            .contains("\"spaceId\":10")
            .contains("\"memberLevel\":{\"id\":3,\"levelCode\":\"GOLD\"")
            .contains("\"payableAmount\":108.00");

        ArgumentCaptor<BusinessEventRecordRequest> eventCaptor = ArgumentCaptor.forClass(BusinessEventRecordRequest.class);
        InOrder inOrder = inOrder(statusLogMapper, businessEventService, reservationMapper, orderService);
        inOrder.verify(statusLogMapper).insert(any(ReservationStatusLog.class));
        inOrder.verify(businessEventService).record(eventCaptor.capture());
        inOrder.verify(reservationMapper).updateById(any(Reservation.class));
        inOrder.verify(orderService).createPendingOrder(any(Reservation.class), eq(30));
        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "RESERVATION_APPROVED".equals(event.eventType())
                && "RESERVATION_APPROVED:99".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && Map.of("reservationId", "99").equals(event.templateModel())));
        BusinessEventRecordRequest event = eventCaptor.getValue();
        assertThat(event.eventType).isEqualTo(BusinessEventType.RESERVATION_CREATED);
        assertThat(event.businessDomain).isEqualTo(BusinessDomain.RESERVATION);
        assertThat(event.entityType).isEqualTo("RESERVATION");
        assertThat(event.entityId).isEqualTo(99L);
        assertThat(event.sourceType).isEqualTo(BusinessSourceType.USER);
        assertThat(event.providerEnv).isNull();
        assertThat(event.fromStatus).isNull();
        assertThat(event.toStatus).isEqualTo(ReservationStatus.PENDING_REVIEW.name());
        assertThat(event.resultStatus).isEqualTo("SUCCESS");
        assertThat(event.reasonText).isEqualTo("用户提交预约");
        assertThat(event.idempotencyKey).isEqualTo("RESERVATION_CREATED:99");
        assertThat(event.metadata)
            .containsEntry("venueId", 20L)
            .containsEntry("spaceId", 10L)
            .containsEntry("startTime", startTime)
            .containsEntry("endTime", startTime.plusHours(1))
            .containsEntry("amount", new BigDecimal("108.00"))
            .containsEntry("amountLegacy", new BigDecimal("108.00"))
            .containsEntry("memberLevel", "GOLD")
            .containsEntry("memberAccountId", 77L)
            .containsEntry("pricingSnapshotVersion", "B3_PROMOTION_PRICING_V1")
            .containsEntry("pricingSnapshotJson", insertedReservation.pricingSnapshotJson);
        assertThat(event.metadata.get("amountBreakdown")).isInstanceOf(AmountBreakdown.class);
        AmountBreakdown amountBreakdown = (AmountBreakdown) event.metadata.get("amountBreakdown");
        assertThat(amountBreakdown.payableAmount()).isEqualByComparingTo("108.00");
        assertThat(amountBreakdown.memberDiscountAmount()).isEqualByComparingTo("12.00");
        verifyNoMoreInteractions(businessEventService);
    }

    @Test
    void createWithNoVenueManagersRecordsSafeExclusionAfterUserConfirmation() {
        LocalDateTime startTime = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateReservationRequest request = createRequest(startTime, startTime.plusHours(1));
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookable(10L, startTime, startTime.plusHours(1))).thenReturn(BookabilityDecision.ok());
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of());
        doAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.id = 99L;
            return 1;
        }).when(reservationMapper).insert(any(Reservation.class));

        Reservation created = reservationService.create(request);

        InOrder inOrder = inOrder(businessEventService, funnelEventService, noticeService);
        inOrder.verify(businessEventService).record(any(BusinessEventRecordRequest.class));
        inOrder.verify(funnelEventService).recordMilestone(
            eq("RESERVATION_SUBMITTED"),
            eq("RESERVATION"),
            eq(created.userId),
            eq(created.venueId),
            eq(created.id),
            isNull(),
            eq(created.payableAmount),
            eq("SUCCESS"),
            isNull(),
            eq("reservation-submitted-" + created.id),
            argThat((Map<String, Object> metadata) ->
                Long.valueOf(10L).equals(metadata.get("spaceId"))
                    && ReservationStatus.PENDING_REVIEW.name().equals(metadata.get("status"))),
            any());
        inOrder.verify(noticeService).createMessage(7L, "预约已提交", "预约已提交，请等待场馆管理员审核。",
            "RESERVATION", 99L);
        verify(noticeService, never()).createBusinessNotification(any(NotificationEvent.class));
        verify(noticeService).recordNoEligibleVenueAdminExclusion(
            "RESERVATION_REVIEW_REQUIRED", "RESERVATION_REVIEW_REQUIRED:99", "RESERVATION", 99L);
    }

    @Test
    void createPropagatesFunnelReservationMilestoneFailureBeforeNotification() {
        LocalDateTime startTime = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateReservationRequest request = createRequest(startTime, startTime.plusHours(1));
        RuntimeException failure = new RuntimeException("funnel down");
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookable(10L, startTime, startTime.plusHours(1))).thenReturn(BookabilityDecision.ok());
        doAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.id = 99L;
            return 1;
        }).when(reservationMapper).insert(any(Reservation.class));
        doThrow(failure).when(funnelEventService).recordMilestone(anyString(), anyString(), any(), any(), any(),
            any(), any(), anyString(), any(), anyString(), any(), any());

        assertThatThrownBy(() -> reservationService.create(request))
            .isSameAs(failure);

        verify(noticeService, never()).createMessage(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void pricingPreviewUsesPromotionPricingWithoutWritingReservation() {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);
        CreateReservationRequest request = createRequest(start, end);
        when(configMapper.findByKey("reservation.max_slots_per_booking")).thenReturn(config("2"));
        VenueSpace space = activeSpace();
        space.pricePerHour = new BigDecimal("60.00");
        when(spaceMapper.selectById(10L)).thenReturn(space);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookable(10L, start, end)).thenReturn(BookabilityDecision.ok());
        when(promotionPricingService.previewForReservation(7L, 20L, 10L, start, end, new BigDecimal("60.00"), null))
            .thenReturn(promotionPricingContext(start, end, new BigDecimal("60.00"),
                "120.00", "12.00", "0.00", "0.00", "108.00"));

        PricingPreviewResponse response = reservationService.pricingPreview(request);

        assertThat(response.originalAmount).isEqualByComparingTo("120.00");
        assertThat(response.memberDiscountAmount).isEqualByComparingTo("12.00");
        assertThat(response.couponDiscountAmount).isEqualByComparingTo("0.00");
        assertThat(response.activityDiscountAmount).isEqualByComparingTo("0.00");
        assertThat(response.payableAmount).isEqualByComparingTo("108.00");
        assertThat(response.memberLevel.levelCode).isEqualTo("GOLD");
        assertThat(response.memberAccount.memberAccountId).isEqualTo(77L);
        assertThat(response.pricingSnapshotJson)
            .contains("\"pricingVersion\":\"B3_PROMOTION_PRICING_V1\"")
            .contains("\"payableAmount\":108.00");
        verify(inventoryService).checkBookable(10L, start, end);
        verify(promotionPricingService).previewForReservation(7L, 20L, 10L, start, end, new BigDecimal("60.00"), null);
        verify(couponService, never()).lockForReservation(any(), any(), any(), anyString());
        verify(reservationMapper, never()).insert(any(Reservation.class));
        verify(occupancyMapper, never()).insert(any(com.example.venue.reservation.entity.ReservationOccupancy.class));
        verify(statusLogMapper, never()).insert(any(ReservationStatusLog.class));
        verify(businessEventService, never()).record(any());
        verify(orderService, never()).createPendingOrder(any(), any(Integer.class));
        verify(noticeService, never()).createMessage(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void availabilityMapsInventorySlotsToLegacyAvailabilitySlots() {
        LocalDate date = LocalDate.now().plusDays(2);
        InventorySlot inventorySlot = new InventorySlot();
        inventorySlot.venueId = 20L;
        inventorySlot.spaceId = 10L;
        inventorySlot.startTime = date.atTime(10, 0);
        inventorySlot.endTime = date.atTime(11, 0);
        inventorySlot.status = "MANUAL_HOLD";
        inventorySlot.label = "人工保留";
        inventorySlot.price = new BigDecimal("100.00");
        inventorySlot.reasonCode = "MANUAL_HOLD";
        inventorySlot.reasonText = "校队训练预留";
        inventorySlot.blockType = "MANUAL_HOLD";
        inventorySlot.blockId = 44L;
        inventorySlot.reservationId = 55L;
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(inventoryService.dailyInventory(20L, 10L, date, false)).thenReturn(List.of(inventorySlot));

        List<AvailabilitySlot> slots = reservationService.availability(10L, date);

        assertThat(slots).hasSize(1);
        AvailabilitySlot slot = slots.get(0);
        assertThat(slot.spaceId).isEqualTo(10L);
        assertThat(slot.startTime).isEqualTo(date.atTime(10, 0));
        assertThat(slot.endTime).isEqualTo(date.atTime(11, 0));
        assertThat(slot.status).isEqualTo("MANUAL_HOLD");
        assertThat(slot.inventoryStatus).isEqualTo("MANUAL_HOLD");
        assertThat(slot.label).isEqualTo("人工保留");
        assertThat(slot.price).isEqualByComparingTo("100.00");
        assertThat(slot.reason).isEqualTo("校队训练预留");
        assertThat(slot.reasonCode).isEqualTo("MANUAL_HOLD");
        assertThat(slot.blockType).isEqualTo("MANUAL_HOLD");
        assertThat(slot.blockId).isEqualTo(44L);
        assertThat(slot.reservationId).isEqualTo(55L);
    }

    @Test
    void rescheduleSlotsUsesInventoryThatExcludesOwnedUnpaidReservation() {
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        LocalDate date = reservation.startTime.toLocalDate();
        InventorySlot inventorySlot = new InventorySlot();
        inventorySlot.spaceId = 10L;
        inventorySlot.startTime = reservation.startTime;
        inventorySlot.endTime = reservation.endTime;
        inventorySlot.status = "AVAILABLE";
        inventorySlot.label = "可预约";
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(inventoryService.dailyInventory(20L, 10L, date, false, 99L))
            .thenReturn(List.of(inventorySlot));

        List<AvailabilitySlot> slots = reservationService.rescheduleSlots(99L, date);

        assertThat(slots).singleElement().satisfies(slot -> {
            assertThat(slot.status).isEqualTo("AVAILABLE");
            assertThat(slot.startTime).isEqualTo(reservation.startTime);
        });
        verify(inventoryService).dailyInventory(20L, 10L, date, false, 99L);
    }

    @Test
    void createRejectsUnavailableDecisionFromInventoryServiceBeforeInsert() {
        LocalDateTime startTime = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateReservationRequest request = createRequest(startTime, startTime.plusHours(1));
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        BookabilityDecision decision = BookabilityDecision.rejected("MANUAL_HOLD", "MANUAL_HOLD", "校队训练预留");
        when(inventoryService.checkBookable(10L, startTime, startTime.plusHours(1))).thenReturn(decision);

        assertThatThrownBy(() -> reservationService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("校队训练预留");

        verify(reservationMapper, never()).insert(any(Reservation.class));
        verify(reservationMapper, never()).findActiveOverlaps(any(), any(), any());
    }

    @Test
    void createRejectsPastStartTime() {
        LocalDateTime startTime = LocalDate.now().minusDays(1).atTime(23, 30);
        CreateReservationRequest request = createRequest(startTime, LocalDate.now().atTime(0, 30));
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());

        assertThatThrownBy(() -> reservationService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("不能预约过去时段");

        verify(reservationMapper, never()).insert(any(Reservation.class));
    }

    @Test
    void createRejectsFutureCrossDayReservation() {
        LocalDateTime startTime = LocalDate.now().plusDays(1).atTime(23, 30);
        CreateReservationRequest request = createRequest(startTime, startTime.plusHours(1));
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());

        assertThatThrownBy(() -> reservationService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("暂不支持跨日预约");

        verify(reservationMapper, never()).insert(any(Reservation.class));
    }

    @Test
    void createRejectsDateBeyondAdvanceDays() {
        when(configMapper.findByKey("reservation.advance_days")).thenReturn(config("1"));
        LocalDateTime startTime = LocalDate.now().plusDays(2).atTime(10, 0);
        CreateReservationRequest request = createRequest(startTime, startTime.plusHours(1));
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());

        assertThatThrownBy(() -> reservationService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("预约日期超过可提前预约天数");

        verify(reservationMapper, never()).insert(any(Reservation.class));
    }

    @Test
    void approveRechecksCurrentSpaceStatus() {
        Reservation reservation = pendingReviewReservation();
        VenueSpace disabledSpace = activeSpace();
        disabledSpace.status = "MAINTENANCE";
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(spaceMapper.selectById(10L)).thenReturn(disabledSpace);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookableExcludingReservation(10L, reservation.startTime, reservation.endTime, 99L))
            .thenReturn(BookabilityDecision.rejected("CLOSED", "SPACE_DISABLED", "场地维护中或已停用，不能预约"));

        assertThatThrownBy(() -> reservationService.approve(99L, true, "通过"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("场地维护中或已停用，不能预约");

        verify(orderService, never()).createPendingOrder(any(Reservation.class), any(Integer.class));
        verify(reservationMapper, never()).updateById(any(Reservation.class));
    }

    @Test
    void approveRejectsUnavailableDecisionFromInventoryServiceBeforeOrderCreation() {
        Reservation reservation = pendingReviewReservation();
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        BookabilityDecision decision = BookabilityDecision.rejected("BUFFER", "CLEANING_BUFFER", "该时段处于清洁缓冲");
        when(inventoryService.checkBookableExcludingReservation(10L, reservation.startTime, reservation.endTime, 99L))
            .thenReturn(decision);

        assertThatThrownBy(() -> reservationService.approve(99L, true, "通过"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("该时段处于清洁缓冲");

        verify(orderService, never()).createPendingOrder(any(Reservation.class), any(Integer.class));
        verify(reservationMapper, never()).findActiveOverlaps(any(), any(), any());
        verify(reservationMapper, never()).updateById(any(Reservation.class));
    }

    @Test
    void approveUsesInventoryDecisionThatExcludesCurrentReservationSelfConflict() {
        Reservation reservation = pendingReviewReservation();
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookableExcludingReservation(10L, reservation.startTime, reservation.endTime, 99L))
            .thenReturn(BookabilityDecision.ok());
        when(configMapper.findByKey("payment.timeout_minutes")).thenReturn(config("30"));

        reservationService.approve(99L, true, "通过");

        verify(inventoryService).checkBookableExcludingReservation(10L, reservation.startTime, reservation.endTime, 99L);
        verify(orderService).createPendingOrder(any(Reservation.class), eq(30));
        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "RESERVATION_APPROVED".equals(event.eventType())
                && "RESERVATION_APPROVED:99".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && "预约审核通过".equals(event.inAppTitle())
                && "预约已通过审核，请在支付截止前完成支付。".equals(event.inAppContent())
                && Map.of("reservationId", "99").equals(event.templateModel())));
    }

    @Test
    void requestRescheduleRejectsPaidReservationInB4() {
        Reservation paid = reservation(99L, ReservationStatus.PAID.name());
        when(reservationMapper.selectByIdForUpdate(99L)).thenReturn(paid);
        clearInvocations(reservationMapper);
        RescheduleRequest request = rescheduleRequest(paid.startTime.plusDays(1), paid.endTime.plusDays(1));

        assertThatThrownBy(() -> reservationService.requestReschedule(99L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已支付预约暂不支持改期");

        verify(reservationMapper).selectByIdForUpdate(99L);
        verify(lifecycleService, never()).createRescheduleRequest(any(Reservation.class), any(RescheduleRequest.class));
    }

    @Test
    void requestRescheduleCreatesPendingRequestForPendingPayment() {
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        RescheduleRequest request = rescheduleRequest(reservation.startTime.plusDays(1), reservation.endTime.plusDays(1));
        ReservationRescheduleRequest pendingRequest = rescheduleEntity(301L, reservation, request);
        when(lifecycleService.createRescheduleRequest(reservation, request)).thenReturn(pendingRequest);

        ReservationRescheduleResponse created = reservationService.requestReschedule(99L, request);

        assertThat(created.id()).isEqualTo(pendingRequest.id);
        assertThat(created.status()).isEqualTo(pendingRequest.status);
        verify(lifecycleService).createRescheduleRequest(reservation, request);
    }

    @Test
    void approveRescheduleAppliesTimeAfterInventoryCheck() {
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        RescheduleRequest requestedTime = rescheduleRequest(reservation.startTime.plusDays(1), reservation.endTime.plusDays(1));
        ReservationRescheduleRequest pendingRequest = rescheduleEntity(301L, reservation, requestedTime);
        when(lifecycleService.requireRescheduleRequestForUpdate(301L)).thenReturn(pendingRequest);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookableExcludingReservation(10L, requestedTime.startTime, requestedTime.endTime, 99L))
            .thenReturn(BookabilityDecision.ok());

        reservationService.reviewReschedule(301L, true, "同意改期");

        verify(venueAccessService).requireVenueManageAccess(20L);
        InOrder inOrder = inOrder(inventoryService, occupancyMapper, reservationMapper, lifecycleService);
        inOrder.verify(inventoryService).checkBookableExcludingReservation(10L, requestedTime.startTime,
            requestedTime.endTime, 99L);
        inOrder.verify(occupancyMapper).releaseByReservationId(99L);
        inOrder.verify(occupancyMapper).insert(argThat((ReservationOccupancy occupancy) ->
            occupancy.reservationId.equals(99L)
                && occupancy.spaceId.equals(10L)
                && occupancy.slotDate.equals(requestedTime.startTime.toLocalDate())
                && occupancy.startTime.equals(requestedTime.startTime.toLocalTime())
                && occupancy.endTime.equals(requestedTime.endTime.toLocalTime())
                && occupancy.activeFlag == 1));
        inOrder.verify(reservationMapper).updateById(argThat((Reservation updated) ->
            updated.id.equals(99L)
                && updated.startTime.equals(requestedTime.startTime)
                && updated.endTime.equals(requestedTime.endTime)
                && updated.status.equals(ReservationStatus.PENDING_PAYMENT.name())));
        inOrder.verify(lifecycleService).approveRescheduleRequest(pendingRequest, "同意改期");
    }

    @Test
    void approveRescheduleRejectsExpiredRequestWhenOriginalTimeChanged() {
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        RescheduleRequest requestedTime = rescheduleRequest(reservation.startTime.plusDays(1), reservation.endTime.plusDays(1));
        ReservationRescheduleRequest pendingRequest = rescheduleEntity(301L, reservation, requestedTime);
        reservation.startTime = reservation.startTime.plusHours(1);
        reservation.endTime = reservation.endTime.plusHours(1);
        when(lifecycleService.requireRescheduleRequestForUpdate(301L)).thenReturn(pendingRequest);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        assertThatThrownBy(() -> reservationService.reviewReschedule(301L, true, "同意改期"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("改期申请已过期，原预约时段已变化");

        verify(reservationMapper).selectByIdForUpdate(99L);
        verify(occupancyMapper, never()).releaseByReservationId(any());
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        verify(lifecycleService, never()).approveRescheduleRequest(any(), anyString());
    }

    @Test
    void approvePendingPaymentRescheduleRepricesAndSyncsPendingOrder() {
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        reservation.amount = new BigDecimal("100.00");
        reservation.originalAmount = new BigDecimal("100.00");
        reservation.memberDiscountAmount = new BigDecimal("0.00");
        reservation.couponDiscountAmount = new BigDecimal("0.00");
        reservation.activityDiscountAmount = new BigDecimal("0.00");
        reservation.payableAmount = new BigDecimal("100.00");
        reservation.paidAmount = BigDecimal.ZERO;
        reservation.feeAmount = BigDecimal.ZERO;
        reservation.netAmount = BigDecimal.ZERO;
        reservation.pricingSnapshotJson = "{\"pricingVersion\":\"B3_PROMOTION_PRICING_V1\",\"payableAmount\":100.00}";
        reservation.userCouponId = 70L;
        reservation.couponTemplateId = 5L;
        reservation.promotionCampaignId = 1L;
        reservation.priceRuleId = 2L;
        reservation.couponLockToken = "reservation-99-token";
        RescheduleRequest requestedTime = rescheduleRequest(reservation.startTime.plusDays(1),
            reservation.endTime.plusDays(1));
        ReservationRescheduleRequest pendingRequest = rescheduleEntity(301L, reservation, requestedTime);
        PromotionPricingContext newPricing = promotionPricingContext(requestedTime.startTime, requestedTime.endTime);
        when(lifecycleService.requireRescheduleRequestForUpdate(301L)).thenReturn(pendingRequest);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        VenueSpace space = activeSpace();
        space.pricePerHour = new BigDecimal("60.00");
        when(spaceMapper.selectById(10L)).thenReturn(space);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookableExcludingReservation(10L, requestedTime.startTime, requestedTime.endTime, 99L))
            .thenReturn(BookabilityDecision.ok());
        when(promotionPricingService.previewForReservation(7L, 20L, 10L, requestedTime.startTime,
            requestedTime.endTime, new BigDecimal("60.00"), 70L)).thenReturn(newPricing);

        reservationService.reviewReschedule(301L, true, "同意改期");

        ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationMapper).updateById(reservationCaptor.capture());
        Reservation updated = reservationCaptor.getValue();
        assertThat(updated.startTime).isEqualTo(requestedTime.startTime);
        assertThat(updated.endTime).isEqualTo(requestedTime.endTime);
        assertThat(updated.amount).isEqualByComparingTo("85.00");
        assertThat(updated.originalAmount).isEqualByComparingTo("120.00");
        assertThat(updated.memberDiscountAmount).isEqualByComparingTo("5.00");
        assertThat(updated.couponDiscountAmount).isEqualByComparingTo("20.00");
        assertThat(updated.activityDiscountAmount).isEqualByComparingTo("10.00");
        assertThat(updated.payableAmount).isEqualByComparingTo("85.00");
        assertThat(updated.paidAmount).isEqualByComparingTo("0.00");
        assertThat(updated.feeAmount).isEqualByComparingTo("0.00");
        assertThat(updated.netAmount).isEqualByComparingTo("0.00");
        assertThat(updated.pricingSnapshotJson)
            .contains("\"pricingVersion\":\"B3_PROMOTION_PRICING_V1\"")
            .contains("\"payableAmount\":85.00");
        assertThat(updated.promotionCampaignId).isEqualTo(8L);
        assertThat(updated.priceRuleId).isEqualTo(9L);
        assertThat(updated.couponTemplateId).isEqualTo(5L);
        assertThat(updated.userCouponId).isEqualTo(70L);
        assertThat(updated.couponLockToken).isEqualTo("reservation-99-token");
        verify(orderService).syncPendingOrderForRescheduledReservation(argThat((Reservation synced) ->
            synced.id.equals(99L)
                && synced.payableAmount.compareTo(new BigDecimal("85.00")) == 0
                && synced.userCouponId.equals(70L)
                && synced.couponLockToken.equals("reservation-99-token")));
        verify(couponService, never()).releaseForReservation(any(), anyString());
        verify(couponService, never()).lockForReservation(any(), any(), any(), anyString());
    }

    @Test
    void approveRescheduleRejectsUnavailableLockedCouponForNewTime() {
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        reservation.userCouponId = 70L;
        reservation.couponTemplateId = 5L;
        reservation.couponLockToken = "reservation-99-token";
        RescheduleRequest requestedTime = rescheduleRequest(reservation.startTime.plusDays(1),
            reservation.endTime.plusDays(1));
        ReservationRescheduleRequest pendingRequest = rescheduleEntity(301L, reservation, requestedTime);
        when(lifecycleService.requireRescheduleRequestForUpdate(301L)).thenReturn(pendingRequest);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        VenueSpace space = activeSpace();
        space.pricePerHour = new BigDecimal("60.00");
        when(spaceMapper.selectById(10L)).thenReturn(space);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookableExcludingReservation(10L, requestedTime.startTime, requestedTime.endTime, 99L))
            .thenReturn(BookabilityDecision.ok());
        when(promotionPricingService.previewForReservation(7L, 20L, 10L, requestedTime.startTime,
            requestedTime.endTime, new BigDecimal("60.00"), 70L))
            .thenReturn(unavailableCouponPricingContext(requestedTime.startTime, requestedTime.endTime));

        assertThatThrownBy(() -> reservationService.reviewReschedule(301L, true, "同意改期"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未达到优惠券使用门槛");

        verify(occupancyMapper, never()).releaseByReservationId(any());
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        verify(orderService, never()).syncPendingOrderForRescheduledReservation(any(Reservation.class));
        verify(lifecycleService, never()).approveRescheduleRequest(any(), anyString());
        verify(couponService, never()).releaseForReservation(any(), anyString());
        verify(couponService, never()).lockForReservation(any(), any(), any(), anyString());
    }

    @Test
    void approveRescheduleConsumesNewCampaignBudgetWhenActivityDiscountApplies() {
        // AUD-P2-04：改期审核通过时占用新活动预算，与 create 保持一致。
        // 原 reviewReschedule 调用 previewPromotionPricing 后未占用新活动预算，
        // 可把非活动预约改到已耗尽活动而继续获得折扣；修复后通过 reserveCampaignBudgetForCreation 占用。
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        reservation.userCouponId = 70L;
        reservation.couponTemplateId = 5L;
        reservation.couponLockToken = "reservation-99-token";
        RescheduleRequest requestedTime = rescheduleRequest(reservation.startTime.plusDays(1),
            reservation.endTime.plusDays(1));
        ReservationRescheduleRequest pendingRequest = rescheduleEntity(301L, reservation, requestedTime);
        PromotionPricingContext newPricing = promotionPricingContext(requestedTime.startTime, requestedTime.endTime);
        newPricing.campaign.budgetAmount = new BigDecimal("100.00"); // 活动有预算，触发占用
        when(lifecycleService.requireRescheduleRequestForUpdate(301L)).thenReturn(pendingRequest);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        VenueSpace space = activeSpace();
        space.pricePerHour = new BigDecimal("60.00");
        when(spaceMapper.selectById(10L)).thenReturn(space);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookableExcludingReservation(10L, requestedTime.startTime, requestedTime.endTime, 99L))
            .thenReturn(BookabilityDecision.ok());
        when(promotionPricingService.previewForReservation(7L, 20L, 10L, requestedTime.startTime,
            requestedTime.endTime, new BigDecimal("60.00"), 70L)).thenReturn(newPricing);
        when(promotionPricingService.consumeCampaignBudget(eq(8L),
            org.mockito.ArgumentMatchers.argThat((BigDecimal amount) -> amount.compareTo(new BigDecimal("10.00")) == 0)))
            .thenReturn(true);

        reservationService.reviewReschedule(301L, true, "同意改期");

        // 验证占用新活动预算：campaignId=8L, amount=10.00（与 activityDiscountAmount 一致）
        verify(promotionPricingService).consumeCampaignBudget(eq(8L),
            org.mockito.ArgumentMatchers.argThat((BigDecimal amount) -> amount.compareTo(new BigDecimal("10.00")) == 0));
        verify(lifecycleService).approveRescheduleRequest(pendingRequest, "同意改期");
    }

    @Test
    void approveRescheduleReleasesOriginalCampaignBudgetBeforeConsumingNew() {
        // AUD-P2-04 回补返修：改期审核通过时，原预约若绑定活动并占用预算，必须先回补原活动预算，
        // 再占用新活动预算，与 create 占用对称，避免活动预算虚耗。
        // 原 reviewReschedule 只占用新预算不回补原预算，长期造成原活动 budget_used_amount 累计虚高。
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        reservation.promotionCampaignId = 1L; // 原预约绑定活动 1
        reservation.activityDiscountAmount = new BigDecimal("10.00"); // 原占用 10 元
        reservation.userCouponId = 70L;
        reservation.couponTemplateId = 5L;
        reservation.couponLockToken = "reservation-99-token";
        RescheduleRequest requestedTime = rescheduleRequest(reservation.startTime.plusDays(1),
            reservation.endTime.plusDays(1));
        ReservationRescheduleRequest pendingRequest = rescheduleEntity(301L, reservation, requestedTime);
        PromotionPricingContext newPricing = promotionPricingContext(requestedTime.startTime, requestedTime.endTime);
        newPricing.campaign.budgetAmount = new BigDecimal("100.00"); // 新活动有预算，触发占用
        when(lifecycleService.requireRescheduleRequestForUpdate(301L)).thenReturn(pendingRequest);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        VenueSpace space = activeSpace();
        space.pricePerHour = new BigDecimal("60.00");
        when(spaceMapper.selectById(10L)).thenReturn(space);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookableExcludingReservation(10L, requestedTime.startTime, requestedTime.endTime, 99L))
            .thenReturn(BookabilityDecision.ok());
        when(promotionPricingService.previewForReservation(7L, 20L, 10L, requestedTime.startTime,
            requestedTime.endTime, new BigDecimal("60.00"), 70L)).thenReturn(newPricing);
        when(promotionPricingService.consumeCampaignBudget(eq(8L),
            org.mockito.ArgumentMatchers.argThat((BigDecimal amount) -> amount.compareTo(new BigDecimal("10.00")) == 0)))
            .thenReturn(true);

        reservationService.reviewReschedule(301L, true, "同意改期");

        // 验证回补原活动预算：调用单点 public 方法（CAS+回补由 PromotionPricingServiceTest 覆盖）
        verify(promotionPricingService).releaseCampaignBudgetForReservation(any(Reservation.class));
        // 验证占用新活动预算：campaignId=8L（新活动），amount=10.00（新 activityDiscountAmount）
        verify(promotionPricingService).consumeCampaignBudget(eq(8L),
            org.mockito.ArgumentMatchers.argThat((BigDecimal amount) -> amount.compareTo(new BigDecimal("10.00")) == 0));
        // 验证回补先于占用，确保原预算释放后新预算才占用（避免对同一活动先占用后回补导致瞬时超额）
        InOrder inOrder = inOrder(promotionPricingService);
        inOrder.verify(promotionPricingService).releaseCampaignBudgetForReservation(any(Reservation.class));
        inOrder.verify(promotionPricingService).consumeCampaignBudget(eq(8L), any(BigDecimal.class));
        verify(lifecycleService).approveRescheduleRequest(pendingRequest, "同意改期");
    }

    @Test
    void approveRescheduleSkipsReleaseWhenOriginalReservationHasNoCampaignBudget() {
        // AUD-P2-04 回补返修：原预约未绑定活动（promotionCampaignId=null）或无活动折扣（activityDiscountAmount<=0）
        // 时不应调用 releaseCampaignBudget，避免误回补。
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        // reservation.promotionCampaignId 默认 null；activityDiscountAmount 默认 null
        reservation.userCouponId = 70L;
        reservation.couponTemplateId = 5L;
        reservation.couponLockToken = "reservation-99-token";
        RescheduleRequest requestedTime = rescheduleRequest(reservation.startTime.plusDays(1),
            reservation.endTime.plusDays(1));
        ReservationRescheduleRequest pendingRequest = rescheduleEntity(301L, reservation, requestedTime);
        PromotionPricingContext newPricing = promotionPricingContext(requestedTime.startTime, requestedTime.endTime);
        newPricing.campaign.budgetAmount = new BigDecimal("100.00");
        when(lifecycleService.requireRescheduleRequestForUpdate(301L)).thenReturn(pendingRequest);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        VenueSpace space = activeSpace();
        space.pricePerHour = new BigDecimal("60.00");
        when(spaceMapper.selectById(10L)).thenReturn(space);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookableExcludingReservation(10L, requestedTime.startTime, requestedTime.endTime, 99L))
            .thenReturn(BookabilityDecision.ok());
        when(promotionPricingService.previewForReservation(7L, 20L, 10L, requestedTime.startTime,
            requestedTime.endTime, new BigDecimal("60.00"), 70L)).thenReturn(newPricing);
        when(promotionPricingService.consumeCampaignBudget(eq(8L),
            org.mockito.ArgumentMatchers.argThat((BigDecimal amount) -> amount.compareTo(new BigDecimal("10.00")) == 0)))
            .thenReturn(true);

        reservationService.reviewReschedule(301L, true, "同意改期");

        // 原预约无活动时 service 仍委托 public 方法（内部 no-op 由 PromotionPricingServiceTest 覆盖）
        verify(promotionPricingService).releaseCampaignBudgetForReservation(any(Reservation.class));
        // 新活动仍正常占用
        verify(promotionPricingService).consumeCampaignBudget(eq(8L),
            org.mockito.ArgumentMatchers.argThat((BigDecimal amount) -> amount.compareTo(new BigDecimal("10.00")) == 0));
        verify(lifecycleService).approveRescheduleRequest(pendingRequest, "同意改期");
    }

    @Test
    void approveRescheduleSucceedsWithLockedCouponForReservationOwner() {
        // AUD-P2-05：含已锁优惠券的预约改期审核应通过，不再因 CouponService 拒绝稳定失败。
        // 原 CouponService.couponForPricing L153-156 要求当前操作人=券所有者（改期场景下当前操作人是
        // 场馆管理员，券所有者是 reservation.userId，不匹配抛 forbidden），随后 L166 把 LOCKED 券
        // 判为不可用，审核通过路径稳定失败。修复后 CouponService 放宽校验，LOCKED 券对该预约视为可用。
        // 本测试在 ReservationService 层验证含已锁券的改期审核不抛异常；CouponService 层放宽行为由
        // CouponServiceTest 覆盖。
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        reservation.userCouponId = 70L;
        reservation.couponTemplateId = 5L;
        reservation.couponLockToken = "reservation-99-token"; // 券已锁在该预约
        RescheduleRequest requestedTime = rescheduleRequest(reservation.startTime.plusDays(1),
            reservation.endTime.plusDays(1));
        ReservationRescheduleRequest pendingRequest = rescheduleEntity(301L, reservation, requestedTime);
        PromotionPricingContext newPricing = promotionPricingContext(requestedTime.startTime, requestedTime.endTime);
        // 模拟 CouponService 放宽后对 LOCKED 券返回的 view：unavailableReason=null（视为可用），
        // 默认 promotionPricingContext helper 的 coupon.unavailableReason 即为 null。
        when(lifecycleService.requireRescheduleRequestForUpdate(301L)).thenReturn(pendingRequest);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        VenueSpace space = activeSpace();
        space.pricePerHour = new BigDecimal("60.00");
        when(spaceMapper.selectById(10L)).thenReturn(space);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookableExcludingReservation(10L, requestedTime.startTime, requestedTime.endTime, 99L))
            .thenReturn(BookabilityDecision.ok());
        when(promotionPricingService.previewForReservation(7L, 20L, 10L, requestedTime.startTime,
            requestedTime.endTime, new BigDecimal("60.00"), 70L)).thenReturn(newPricing);

        // 不应抛异常——原缺陷下会因 forbidden 或 unavailable 失败
        assertThatCode(() -> reservationService.reviewReschedule(301L, true, "同意改期"))
            .doesNotThrowAnyException();

        // 验证预约被更新，券保留为 70L
        ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationMapper).updateById(reservationCaptor.capture());
        Reservation updated = reservationCaptor.getValue();
        assertThat(updated.userCouponId).isEqualTo(70L);
        assertThat(updated.couponLockToken).isEqualTo("reservation-99-token");
        verify(lifecycleService).approveRescheduleRequest(pendingRequest, "同意改期");
    }

    @Test
    void rejectRescheduleRecordsReviewReason() {
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        RescheduleRequest requestedTime = rescheduleRequest(reservation.startTime.plusDays(1), reservation.endTime.plusDays(1));
        ReservationRescheduleRequest pendingRequest = rescheduleEntity(301L, reservation, requestedTime);
        when(lifecycleService.requireRescheduleRequestForUpdate(301L)).thenReturn(pendingRequest);

        reservationService.reviewReschedule(301L, false, "库存不足");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleService).rejectRescheduleRequest(pendingRequest, "库存不足");
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        verify(occupancyMapper, never()).releaseByReservationId(any());
    }

    @Test
    void reviewRescheduleRejectsConcurrentApprovalByRecheckingStatusUnderRowLock() {
        // A26-P3-03：审核入口锁申请行，串行化并发"通过+拒绝"。
        // 缺陷：原 reviewReschedule 用 requireRescheduleRequest（无锁），并发"通过+拒绝"可产生
        // "申请记为拒绝但预约已被改期"的矛盾终态。修复后用 requireRescheduleRequestForUpdate（行锁），
        // 后到的审核看到前一个写入的 APPROVED/REJECTED 状态而被 PENDING_REVIEW 守卫拒绝。
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        RescheduleRequest requestedTime = rescheduleRequest(reservation.startTime.plusDays(1),
            reservation.endTime.plusDays(1));
        ReservationRescheduleRequest pendingRequest = rescheduleEntity(301L, reservation, requestedTime);
        pendingRequest.status = "APPROVED"; // 并发已审核通过，锁后重读看到 APPROVED
        when(lifecycleService.requireRescheduleRequestForUpdate(301L)).thenReturn(pendingRequest);

        assertThatThrownBy(() -> reservationService.reviewReschedule(301L, false, "库存不足"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前改期申请不可审核");

        verify(lifecycleService, never()).rejectRescheduleRequest(any(), anyString());
        verify(lifecycleService, never()).approveRescheduleRequest(any(), anyString());
        verify(reservationMapper, never()).updateById(any(Reservation.class));
    }

    @Test
    void requestRescheduleRejectsTargetSlotCountExceedingMaxSlotsPerBooking() {
        // A26-P3-04：改期目标时段与 create 路径对齐校验 max_slots_per_booking。
        // 缺陷：原 requestReschedule 只调 ensureRequestBounds，不调 ensureSlotLimit，
        // 改期目标时段可绕过单次最大时段数限制（max_slots_per_booking=1，目标跨 2 小时 slotCount=2）。
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        RescheduleRequest request = rescheduleRequest(reservation.startTime.plusDays(1),
            reservation.startTime.plusDays(1).plusHours(2));

        assertThatThrownBy(() -> reservationService.requestReschedule(99L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("单次最多可选择 1 个时段");

        verify(lifecycleService, never()).createRescheduleRequest(any(Reservation.class),
            any(RescheduleRequest.class));
    }

    @Test
    void reviewRescheduleRejectsTargetSlotCountExceedingMaxSlotsPerBooking() {
        // A26-P3-04：改期审核通过路径与 create 对齐校验 max_slots_per_booking。
        // 缺陷：原 reviewReschedule 只调 ensureRequestBounds，不调 ensureSlotLimit，
        // 审核通过可绕过单次最大时段数限制（max_slots_per_booking=1，目标跨 2 小时 slotCount=2）。
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        RescheduleRequest requestedTime = rescheduleRequest(reservation.startTime.plusDays(1),
            reservation.startTime.plusDays(1).plusHours(2));
        ReservationRescheduleRequest pendingRequest = rescheduleEntity(301L, reservation, requestedTime);
        when(lifecycleService.requireRescheduleRequestForUpdate(301L)).thenReturn(pendingRequest);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        assertThatThrownBy(() -> reservationService.reviewReschedule(301L, true, "同意改期"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("单次最多可选择 1 个时段");

        verify(occupancyMapper, never()).releaseByReservationId(any());
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        verify(lifecycleService, never()).approveRescheduleRequest(any(), anyString());
    }

    @Test
    void cancelStoresReasonCodeAndReasonTextInLifecycleEvent() {
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of());

        reservationService.cancel(99L, "TIME_CONFLICT", "时间冲突", false);

        verify(lifecycleService).recordCancellation(reservation, ReservationStatus.PENDING_PAYMENT.name(),
            "TIME_CONFLICT", "时间冲突", "USER");
    }

    @Test
    void cancelRejectsAlreadyCanceledReservation() {
        Reservation reservation = pendingReviewReservation();
        reservation.status = ReservationStatus.CANCELED.name();
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        assertThatThrownBy(() -> reservationService.cancel(99L, "重复取消", false))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前预约状态不可取消");

        verify(orderService, never()).closeOrderForCanceledReservation(any(), any());
    }

    @Test
    void cancelPaidReservationReleasesOccupancyAndSyncsOrder() {
        Reservation reservation = pendingReviewReservation();
        reservation.status = ReservationStatus.PAID.name();
        reservation.startTime = LocalDateTime.now().plusDays(3);
        reservation.endTime = reservation.startTime.plusHours(1);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of());

        reservationService.cancel(99L, "用户取消", false);

        verify(reservationMapper).selectByIdForUpdate(99L);
        ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationMapper).updateById(reservationCaptor.capture());
        assertThat(reservationCaptor.getValue().status).isEqualTo(ReservationStatus.CANCELED.name());
        verify(occupancyMapper).releaseByReservationId(99L);
        verify(orderService).closeOrderForCanceledReservation(99L, "用户取消");
        verify(statusLogMapper).insert(any(ReservationStatusLog.class));
    }

    @Test
    void cancelUnpaidReservationWithCampaignBudgetReleasesCampaignBudget() {
        // A26-P2-04：非 PAID 取消（PENDING_PAYMENT）回补活动预算，与占用对称。
        // PAID 取消不在此回补（由后续 markRefundSuccess 终态回补，避免重复）。
        Reservation reservation = pendingReviewReservation();
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        reservation.promotionCampaignId = 8L;
        reservation.activityDiscountAmount = new BigDecimal("10.00");
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of());
        reservationService.cancel(99L, null, false);

        // 验证回补委托：调用单点 public 方法（CAS+回补由 PromotionPricingServiceTest 覆盖）
        verify(promotionPricingService).releaseCampaignBudgetForReservation(any(Reservation.class));
        // 验证回补与占用释放/券释放同序（都在 transition(CANCELED) 之后）
        InOrder inOrder = inOrder(occupancyMapper, couponService, promotionPricingService);
        inOrder.verify(occupancyMapper).releaseByReservationId(99L);
        inOrder.verify(couponService).releaseForReservation(eq(99L), anyString());
        inOrder.verify(promotionPricingService).releaseCampaignBudgetForReservation(any(Reservation.class));
    }

    @Test
    void cancelPaidReservationDoesNotReleaseCampaignBudgetToAvoidDoubleRelease() {
        // A26-P2-04：PAID 取消不在此回补——PAID → CANCELED 后会进入退款流程，
        // 由 markRefundSuccess 终态回补，避免重复回补。
        Reservation reservation = pendingReviewReservation();
        reservation.status = ReservationStatus.PAID.name();
        reservation.startTime = LocalDateTime.now().plusDays(3);
        reservation.endTime = reservation.startTime.plusHours(1);
        reservation.promotionCampaignId = 8L;
        reservation.activityDiscountAmount = new BigDecimal("10.00");
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of());

        reservationService.cancel(99L, "用户取消", false);

        // PAID 取消不调回补（留给 markRefundSuccess）
        verify(promotionPricingService, never()).releaseCampaignBudgetForReservation(any(Reservation.class));
        verify(couponService, never()).releaseForReservation(any(), anyString());
    }

    @Test
    void approveRejectionReleasesCampaignBudgetForPendingReviewReservation() {
        // A26-P2-04：审核拒绝（PENDING_REVIEW → REJECTED）回补活动预算，REJECTED 是终态无后续退款路径。
        Reservation reservation = pendingReviewReservation();
        reservation.promotionCampaignId = 8L;
        reservation.activityDiscountAmount = new BigDecimal("10.00");
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        reservationService.approve(99L, false, "活动名额已满");

        // 验证状态流转到 REJECTED
        ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationMapper).updateById(reservationCaptor.capture());
        assertThat(reservationCaptor.getValue().status).isEqualTo(ReservationStatus.REJECTED.name());
        // 验证回补委托：调用单点 public 方法（CAS+回补由 PromotionPricingServiceTest 覆盖）
        verify(promotionPricingService).releaseCampaignBudgetForReservation(any(Reservation.class));
        // 验证回补与占用释放/券释放同序（都在 transition(REJECTED) 之后）
        InOrder inOrder = inOrder(occupancyMapper, couponService, promotionPricingService);
        inOrder.verify(occupancyMapper).releaseByReservationId(99L);
        inOrder.verify(couponService).releaseForReservation(eq(99L), anyString());
        inOrder.verify(promotionPricingService).releaseCampaignBudgetForReservation(any(Reservation.class));
    }

    @Test
    void approveRejectionSkipsCampaignBudgetReleaseWhenNoCampaignBound() {
        // A26-P2-04：审核拒绝时未绑定活动（campaignId=null）或无活动折扣（amount<=0）不应回补。
        Reservation reservation = pendingReviewReservation();
        // promotionCampaignId 默认 null；activityDiscountAmount 默认 null
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        reservationService.approve(99L, false, "拒绝原因");

        // 无活动时 service 仍委托 public 方法（内部 no-op 由 PromotionPricingServiceTest 覆盖）
        verify(promotionPricingService).releaseCampaignBudgetForReservation(any(Reservation.class));
    }

    @Test
    void completeStartedReservationsDelegatesToLifecycleInsteadOfCompletingPaidDirectly() {
        Reservation paid = reservation(99L, ReservationStatus.PAID.name());
        paid.endTime = LocalDateTime.now().minusHours(1);
        Page<Reservation> page = new Page<>(1, 100);
        page.setRecords(List.of(paid));
        page.setTotal(1);
        when(reservationMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);
        when(reservationMapper.selectById(99L)).thenReturn(paid);
        lenient().when(lifecycleService.processCompletionCandidate(eq(paid), any(LocalDateTime.class)))
            .thenReturn(CompletionDecision.noAction());

        reservationService.completeStartedReservationsBatch(1000);

        verify(lifecycleService).processCompletionCandidate(eq(paid), any(LocalDateTime.class));
        verify(reservationMapper).selectByIdForUpdate(99L);
        verify(orderService, never()).closePaidOrderForCompletedReservation(eq(99L), anyString());
        verify(reservationMapper, never()).updateById(any(Reservation.class));
    }

    @Test
    void completeStartedReservationsCompletesWhenLifecycleAllowsCompletion() {
        Reservation paid = reservation(100L, ReservationStatus.PAID.name());
        paid.endTime = LocalDateTime.now().minusHours(1);
        Page<Reservation> page = new Page<>(1, 100);
        page.setRecords(List.of(paid));
        page.setTotal(1);
        when(reservationMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);
        when(reservationMapper.selectById(100L)).thenReturn(paid);
        lenient().when(lifecycleService.processCompletionCandidate(eq(paid), any(LocalDateTime.class)))
            .thenReturn(CompletionDecision.complete("预约已核销，可完成"));

        reservationService.completeStartedReservationsBatch(1000);

        ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationMapper).updateById(reservationCaptor.capture());
        verify(reservationMapper).selectByIdForUpdate(100L);
        assertThat(reservationCaptor.getValue().status).isEqualTo(ReservationStatus.COMPLETED.name());
        verify(orderService).closePaidOrderForCompletedReservation(100L, "预约已核销，可完成");
    }

    @Test
    void completeStartedReservationsProcessesAdjacentFullBatchesWithoutSkippingPaidReservations() {
        List<Reservation> candidates = LongStream.rangeClosed(1, 200)
            .mapToObj(id -> {
                Reservation reservation = reservation(id, ReservationStatus.PAID.name());
                reservation.endTime = LocalDateTime.now().minusHours(1);
                return reservation;
            })
            .toList();
        when(reservationMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenAnswer(invocation -> {
            Page<Reservation> page = invocation.getArgument(0);
            QueryWrapper<Reservation> query = invocation.getArgument(1);
            query.getSqlSegment();
            long lastScannedId = query.getParamNameValuePairs().values().stream()
                .filter(Long.class::isInstance)
                .map(Long.class::cast)
                .max(Long::compareTo)
                .orElse(0L);
            long offset = lastScannedId == 0 ? (page.getCurrent() - 1) * page.getSize() : 0;
            page.setRecords(candidates.stream()
                .filter(candidate -> ReservationStatus.PAID.name().equals(candidate.status))
                .filter(candidate -> candidate.id > lastScannedId)
                .skip(offset)
                .limit(page.getSize())
                .toList());
            return page;
        });
        when(reservationMapper.selectById(any(Long.class))).thenAnswer(invocation -> candidates.stream()
            .filter(candidate -> candidate.id.equals(invocation.getArgument(0)))
            .findFirst()
            .orElse(null));
        when(lifecycleService.processCompletionCandidate(any(Reservation.class), any(LocalDateTime.class)))
            .thenReturn(CompletionDecision.complete("预约已核销，可完成"));

        reservationService.completeStartedReservationsBatch(100);

        assertThat(candidates).allSatisfy(candidate ->
            assertThat(candidate.status).isEqualTo(ReservationStatus.COMPLETED.name()));
        verify(lifecycleService, times(200)).processCompletionCandidate(any(Reservation.class), any(LocalDateTime.class));
        verify(orderService, times(200)).closePaidOrderForCompletedReservation(any(Long.class), anyString());
        verify(reservationMapper, times(3)).selectPage(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    void createNotifiesOwnerAndOtherVenueManagerForManualReview() {
        LocalDateTime startTime = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateReservationRequest request = createRequest(startTime, startTime.plusHours(1));
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(inventoryService.checkBookable(10L, startTime, startTime.plusHours(1))).thenReturn(BookabilityDecision.ok());
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of(7L, 8L));
        doAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.id = 99L;
            return 1;
        }).when(reservationMapper).insert(any(Reservation.class));

        reservationService.create(request);

        verify(noticeService).createMessage(7L, "预约已提交", "预约已提交，请等待场馆管理员审核。", "RESERVATION", 99L);
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(noticeService, times(2)).createBusinessNotification(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(NotificationEvent::recipientUserId)
            .containsExactly(7L, 8L);
        assertThat(eventCaptor.getAllValues()).allSatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("RESERVATION_REVIEW_REQUIRED");
            assertThat(event.eventKey()).isEqualTo("RESERVATION_REVIEW_REQUIRED:99");
            assertThat(event.recipientKind()).isEqualTo(NotificationRecipientKind.VENUE_ADMIN);
            assertThat(event.inAppTitle()).isEqualTo("待审核预约");
            assertThat(event.inAppContent()).isEqualTo("有新的预约申请需要审核。");
            assertThat(event.businessType()).isEqualTo("RESERVATION");
            assertThat(event.businessId()).isEqualTo(99L);
            assertThat(event.templateModel()).isEqualTo(Map.of("reservationId", "99"));
        });
    }

    @Test
    void createDelegatesParticipantAndSeatAllocationToUnifiedResourceService() {
        LocalDateTime startTime = LocalDateTime.now().plusDays(2)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateReservationRequest request = createRequest(startTime, startTime.plusHours(1));
        request.participantCount = 2;
        request.seatIds = List.of(501L, 502L);
        Venue venue = activeVenue();
        when(spaceMapper.findVenueBySpaceIdForShare(10L)).thenReturn(venue);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(inventoryService.checkBookable(10L, startTime, startTime.plusHours(1), 2, request.seatIds))
            .thenReturn(BookabilityDecision.ok());
        when(venueAccessService.managerUserIds(20L)).thenReturn(List.of());
        doAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.id = 99L;
            return 1;
        }).when(reservationMapper).insert(any(Reservation.class));
        reservationService.setResourceAllocationService(resourceAllocationService);

        Reservation created = reservationService.create(request);

        verify(resourceAllocationService).allocate(created, 2, request.seatIds);
        verify(reservationMapper, never()).findOverlapsByStatuses(any(), any(), any(), any());
        verify(occupancyMapper, never()).insert(any(ReservationOccupancy.class));
    }

    @Test
    void approvedRescheduleDelegatesAtomicReplacementWithOriginalSelection() {
        Reservation reservation = reservation(99L, ReservationStatus.PENDING_PAYMENT.name());
        reservation.participantCount = 1;
        RescheduleRequest requestedTime = rescheduleRequest(
            reservation.startTime.plusDays(1), reservation.endTime.plusDays(1));
        ReservationRescheduleRequest pendingRequest = rescheduleEntity(301L, reservation, requestedTime);
        when(lifecycleService.requireRescheduleRequestForUpdate(301L)).thenReturn(pendingRequest);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(resourceAllocationService.activeSeatIds(99L)).thenReturn(List.of(501L));
        when(inventoryService.checkBookableExcludingReservation(10L, requestedTime.startTime,
            requestedTime.endTime, 99L, 1, List.of(501L))).thenReturn(BookabilityDecision.ok());
        reservationService.setResourceAllocationService(resourceAllocationService);

        reservationService.reviewReschedule(301L, true, "同意改期");

        verify(resourceAllocationService).replace(reservation, requestedTime.startTime, requestedTime.endTime,
            null, null);
        verify(occupancyMapper, never()).releaseByReservationId(any());
        verify(occupancyMapper, never()).insert(any(ReservationOccupancy.class));
    }

    private CreateReservationRequest createRequest(LocalDateTime startTime, LocalDateTime endTime) {
        CreateReservationRequest request = new CreateReservationRequest();
        request.spaceId = 10L;
        request.startTime = startTime;
        request.endTime = endTime;
        request.contactName = "测试用户";
        request.contactPhone = "13800000000";
        return request;
    }

    private ReservationQuoteRequest quoteRequest(LocalDateTime startTime, LocalDateTime endTime) {
        ReservationQuoteRequest request = new ReservationQuoteRequest();
        request.spaceId = 10L;
        request.startTime = startTime;
        request.endTime = endTime;
        return request;
    }

    private Reservation pendingReviewReservation() {
        Reservation reservation = new Reservation();
        reservation.id = 99L;
        reservation.userId = 7L;
        reservation.venueId = 20L;
        reservation.spaceId = 10L;
        reservation.status = ReservationStatus.PENDING_REVIEW.name();
        reservation.startTime = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        reservation.endTime = reservation.startTime.plusHours(1);
        reservation.deleted = 0;
        return reservation;
    }

    private Reservation reservation(Long id, String status) {
        Reservation reservation = pendingReviewReservation();
        reservation.id = id;
        reservation.status = status;
        return reservation;
    }

    private Reservation reservation(Long id, Long venueId, String status) {
        Reservation reservation = reservation(id, status);
        reservation.venueId = venueId;
        return reservation;
    }

    private RescheduleRequest rescheduleRequest(LocalDateTime startTime, LocalDateTime endTime) {
        RescheduleRequest request = new RescheduleRequest();
        request.startTime = startTime;
        request.endTime = endTime;
        request.reasonCode = "TIME_CONFLICT";
        request.reasonText = "时间冲突";
        return request;
    }

    private ReservationRescheduleRequest rescheduleEntity(Long id, Reservation reservation, RescheduleRequest request) {
        ReservationRescheduleRequest entity = new ReservationRescheduleRequest();
        entity.id = id;
        entity.reservationId = reservation.id;
        entity.userId = reservation.userId;
        entity.venueId = reservation.venueId;
        entity.spaceId = reservation.spaceId;
        entity.fromStartTime = reservation.startTime;
        entity.fromEndTime = reservation.endTime;
        entity.toStartTime = request.startTime;
        entity.toEndTime = request.endTime;
        entity.status = "PENDING_REVIEW";
        entity.reasonCode = request.reasonCode;
        entity.reasonText = request.reasonText;
        entity.deleted = 0;
        return entity;
    }

    private MemberPricingContext memberPricingContext(String originalAmount, String memberDiscountAmount,
                                                      String payableAmount) {
        MemberPricingContext context = new MemberPricingContext();
        context.amountBreakdown = new AmountBreakdown(
            new BigDecimal(originalAmount),
            new BigDecimal(memberDiscountAmount),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal(payableAmount),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
        MemberLevelResponse level = new MemberLevelResponse();
        level.id = 3L;
        level.levelCode = "GOLD";
        level.levelName = "黄金会员";
        level.discountRate = new BigDecimal("0.90");
        level.ruleVersion = "B2_MEMBER_V1";
        context.memberLevel = level;
        MemberAccountResponse account = new MemberAccountResponse();
        account.userId = 7L;
        account.memberAccountId = 77L;
        account.levelCode = "GOLD";
        account.levelName = "黄金会员";
        context.memberAccount = account;
        return context;
    }

    private PromotionPricingContext promotionPricingContext(LocalDateTime startTime, LocalDateTime endTime) {
        PromotionPricingContext context = new PromotionPricingContext();
        context.venueId = 20L;
        context.spaceId = 10L;
        context.startTime = startTime;
        context.endTime = endTime;
        context.unitPrice = new BigDecimal("60.00");
        context.baseAmount = new BigDecimal("120.00");
        context.priceRuleAdjustmentAmount = BigDecimal.ZERO;
        context.amountBreakdown = new AmountBreakdown(
            new BigDecimal("120.00"),
            new BigDecimal("5.00"),
            new BigDecimal("20.00"),
            new BigDecimal("10.00"),
            new BigDecimal("85.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
        context.memberLevel = memberPricingContext("120.00", "5.00", "85.00").memberLevel;
        context.memberAccount = memberPricingContext("120.00", "5.00", "85.00").memberAccount;
        PromotionCampaignResponse campaign = new PromotionCampaignResponse();
        campaign.id = 8L;
        campaign.campaignCode = "SUMMER";
        campaign.campaignName = "夏日活动";
        campaign.discountType = "CASH";
        campaign.discountValue = new BigDecimal("10.00");
        context.campaign = campaign;
        PriceRuleResponse priceRule = new PriceRuleResponse();
        priceRule.id = 9L;
        priceRule.ruleCode = "PEAK";
        context.priceRule = priceRule;
        CouponPricingView coupon = new CouponPricingView();
        coupon.userCouponId = 70L;
        coupon.templateId = 5L;
        coupon.templateCode = "C20";
        coupon.discountAmount = new BigDecimal("20.00");
        context.coupon = coupon;
        context.pricingSnapshotJson = """
            {"pricingVersion":"B3_PROMOTION_PRICING_V1","coupon":{"userCouponId":70,"templateId":5},"payableAmount":85.00}
            """;
        return context;
    }

    private PromotionPricingContext unavailableCouponPricingContext(LocalDateTime startTime, LocalDateTime endTime) {
        PromotionPricingContext context = promotionPricingContext(startTime, endTime, new BigDecimal("60.00"),
            "120.00", "5.00", "0.00", "10.00", "105.00");
        CouponPricingView coupon = new CouponPricingView();
        coupon.userCouponId = 70L;
        coupon.templateId = 5L;
        coupon.templateCode = "C20";
        coupon.discountAmount = BigDecimal.ZERO;
        coupon.unavailableReason = "未达到优惠券使用门槛";
        context.coupon = coupon;
        context.pricingSnapshotJson = """
            {"pricingVersion":"B3_PROMOTION_PRICING_V1","coupon":{"userCouponId":70,"unavailableReason":"未达到优惠券使用门槛"},"payableAmount":105.00}
            """;
        return context;
    }

    private PromotionPricingContext promotionPricingContext(LocalDateTime startTime, LocalDateTime endTime,
                                                            BigDecimal unitPrice, String originalAmount,
                                                            String memberDiscountAmount, String couponDiscountAmount,
                                                            String activityDiscountAmount, String payableAmount) {
        PromotionPricingContext context = new PromotionPricingContext();
        context.venueId = 20L;
        context.spaceId = 10L;
        context.startTime = startTime;
        context.endTime = endTime;
        context.unitPrice = unitPrice;
        context.baseAmount = new BigDecimal(originalAmount);
        context.priceRuleAdjustmentAmount = BigDecimal.ZERO;
        context.amountBreakdown = new AmountBreakdown(
            new BigDecimal(originalAmount),
            new BigDecimal(memberDiscountAmount),
            new BigDecimal(couponDiscountAmount),
            new BigDecimal(activityDiscountAmount),
            new BigDecimal(payableAmount),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
        context.memberLevel = memberPricingContext(originalAmount, memberDiscountAmount, payableAmount).memberLevel;
        context.memberAccount = memberPricingContext(originalAmount, memberDiscountAmount, payableAmount).memberAccount;
        context.pricingSnapshotJson = "{\"pricingVersion\":\"B3_PROMOTION_PRICING_V1\",\"payableAmount\":"
            + new BigDecimal(payableAmount) + "}";
        return context;
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

    private VenueSpace activeSpace() {
        VenueSpace space = new VenueSpace();
        space.id = 10L;
        space.venueId = 20L;
        space.status = "ACTIVE";
        space.pricePerHour = BigDecimal.valueOf(100);
        space.deleted = 0;
        return space;
    }

    private Venue activeVenue() {
        Venue venue = new Venue();
        venue.id = 20L;
        venue.status = "ACTIVE";
        venue.deleted = 0;
        return venue;
    }

    private SysConfig config(String value) {
        SysConfig config = new SysConfig();
        config.configValue = value;
        return config;
    }

    @Test
    void venueAvailabilitySummaryPicksEarliestAvailableSlotAcrossSpaces() {
        VenueSpace spaceA = activeSpace();
        VenueSpace spaceB = activeSpace();
        spaceB.id = 11L;
        when(spaceMapper.selectList(any())).thenReturn(List.of(spaceA, spaceB));
        when(inventoryService.dailyInventory(eq(20L), eq(10L), eq(LocalDate.of(2026, 9, 9)), eq(false)))
            .thenReturn(List.of(slot(10L, "RESERVED", "2026-09-09T10:00:00"),
                slot(10L, "AVAILABLE", "2026-09-09T18:00:00")));
        when(inventoryService.dailyInventory(eq(20L), eq(11L), eq(LocalDate.of(2026, 9, 9)), eq(false)))
            .thenReturn(List.of(slot(11L, "AVAILABLE", "2026-09-09T09:00:00")));

        List<VenueAvailabilitySummary> summaries =
            reservationService.venueAvailabilitySummary(LocalDate.of(2026, 9, 9), List.of(20L));

        assertThat(summaries).hasSize(1);
        VenueAvailabilitySummary summary = summaries.get(0);
        assertThat(summary.venueId).isEqualTo(20L);
        assertThat(summary.available).isTrue();
        assertThat(summary.spaceId).isEqualTo(11L);
        assertThat(summary.startTime).isEqualTo(LocalDateTime.of(2026, 9, 9, 9, 0));
        assertThat(summary.availableSlotCount).isEqualTo(2);
    }

    @Test
    void venueAvailabilitySummaryReportsUnavailableVenueWithoutSlots() {
        when(spaceMapper.selectList(any())).thenReturn(List.of(activeSpace()));
        when(inventoryService.dailyInventory(eq(20L), eq(10L), eq(LocalDate.of(2026, 9, 9)), eq(false)))
            .thenReturn(List.of(slot(10L, "RESERVED", "2026-09-09T10:00:00")));

        List<VenueAvailabilitySummary> summaries =
            reservationService.venueAvailabilitySummary(LocalDate.of(2026, 9, 9), List.of(20L));

        assertThat(summaries.get(0).available).isFalse();
        assertThat(summaries.get(0).availableSlotCount).isZero();
        assertThat(summaries.get(0).startTime).isNull();
    }

    @Test
    void venueAvailabilitySummaryRejectsOversizedVenueList() {
        List<Long> tooMany = LongStream.rangeClosed(1, 26).boxed().toList();
        assertThatThrownBy(() -> reservationService.venueAvailabilitySummary(LocalDate.of(2026, 9, 9), tooMany))
            .isInstanceOf(BusinessException.class);
    }

    private InventorySlot slot(Long spaceId, String status, String startTime) {
        InventorySlot slot = new InventorySlot();
        slot.venueId = 20L;
        slot.spaceId = spaceId;
        slot.status = status;
        slot.startTime = LocalDateTime.parse(startTime);
        slot.endTime = LocalDateTime.parse(startTime).plusHours(1);
        slot.price = BigDecimal.valueOf(60);
        return slot;
    }
}
