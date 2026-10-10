package com.example.venue.reservation.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.constant.BusinessDomainConst;
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
import com.example.venue.common.EntityExistenceService;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.SensitiveDataMasker;
import com.example.venue.funnel.service.FunnelEventService;
import com.example.venue.funnel.service.FunnelSessionContext;
import com.example.venue.inventory.dto.BookabilityDecision;
import com.example.venue.inventory.dto.InventorySlot;
import com.example.venue.inventory.service.VenueInventoryService;
import com.example.venue.member.dto.PricingPreviewResponse;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.order.service.OrderService;
import com.example.venue.pricing.AmountBreakdown;
import com.example.venue.pricing.PricingSnapshotFactory;
import com.example.venue.promotion.dto.PromotionPricingContext;
import com.example.venue.promotion.service.CouponService;
import com.example.venue.promotion.service.PromotionPricingService;
import com.example.venue.reservation.dto.AvailabilitySlot;
import com.example.venue.reservation.dto.CreateReservationRequest;
import com.example.venue.reservation.dto.ReservationQuery;
import com.example.venue.reservation.dto.ReservationQuoteRequest;
import com.example.venue.reservation.dto.ReservationRules;
import com.example.venue.reservation.dto.ReservationSubmissionResult;
import com.example.venue.reservation.dto.ReservationVenueName;
import com.example.venue.reservation.dto.VenueAvailabilitySummary;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.entity.ReservationCreateCommand;
import com.example.venue.reservation.entity.ReservationOccupancy;
import com.example.venue.reservation.entity.ReservationStatusLog;
import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.enums.ReservationStatusTransitions;
import com.example.venue.reservation.enums.ReservationSubmissionState;
import com.example.venue.reservation.exception.ReservationSubmissionStateException;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.reservation.mapper.ReservationStatusLogMapper;
import com.example.venue.resource.dto.ResourceAvailabilityResponse;
import com.example.venue.resource.dto.ResourceRuleResponse;
import com.example.venue.resource.enums.AllocationConflictCode;
import com.example.venue.resource.exception.ResourceAllocationConflictException;
import com.example.venue.resource.service.ResourceAllocationService;
import com.example.venue.security.SecurityContext;
import com.example.venue.system.service.SysConfigService;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.mapper.VenueMapper;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.example.venue.venue.service.VenueAccessService;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ReservationService {
    private static final int MAX_USER_VISIBLE_REASON_LENGTH = 500;
    private static final int DEFAULT_RESERVATION_LOCK_SECONDS = 20;
    private static final DefaultRedisScript<Long> RELEASE_RESERVATION_LOCK_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class);
    private static final Pattern USER_VISIBLE_EMAIL = Pattern.compile(
        "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final EnumSet<ReservationStatus> UNCANCELABLE_STATUSES = EnumSet.of(
        ReservationStatus.COMPLETED,
        ReservationStatus.REJECTED,
        ReservationStatus.CANCELED,
        ReservationStatus.EXPIRED,
        ReservationStatus.REFUNDING,
        ReservationStatus.REFUNDED
    );
    private static final EnumSet<ReservationStatus> UNPAID_RESCHEDULABLE_STATUSES = EnumSet.of(
        ReservationStatus.PENDING_REVIEW,
        ReservationStatus.PENDING_PAYMENT
    );

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationMapper reservationMapper;
    private final ReservationOccupancyMapper occupancyMapper;
    private final ReservationStatusLogMapper statusLogMapper;
    private final VenueMapper venueMapper;
    private final VenueSpaceMapper spaceMapper;
    private final EntityExistenceService entityExistenceService;
    private final VenueAccessService venueAccessService;
    private final SysConfigService sysConfigService;
    private final StringRedisTemplate redisTemplate;
    private final OrderService orderService;
    private final ReservationLifecycleService lifecycleService;
    private final NoticeService noticeService;
    private final BusinessEventService businessEventService;
    private final FunnelEventService funnelEventService;
    private final VenueInventoryService inventoryService;
    private final PromotionPricingService promotionPricingService;
    private final CouponService couponService;
    private final PricingSnapshotFactory pricingSnapshotFactory;
    private final ReservationCreateIdempotencyService createIdempotencyService;
    private final ReservationService self;
    private ReservationLifecycleChangePublisher lifecycleChangePublisher;
    private ResourceAllocationService resourceAllocationService;

    public ReservationService(ReservationMapper reservationMapper, ReservationOccupancyMapper occupancyMapper,
                              ReservationStatusLogMapper statusLogMapper, VenueMapper venueMapper,
                              VenueSpaceMapper spaceMapper,
                              EntityExistenceService entityExistenceService,
                              VenueAccessService venueAccessService, SysConfigService sysConfigService,
                              StringRedisTemplate redisTemplate, OrderService orderService,
                              ReservationLifecycleService lifecycleService, NoticeService noticeService,
                              BusinessEventService businessEventService, FunnelEventService funnelEventService,
                              VenueInventoryService inventoryService, PromotionPricingService promotionPricingService,
                              CouponService couponService,
                              PricingSnapshotFactory pricingSnapshotFactory,
                              ReservationCreateIdempotencyService createIdempotencyService,
                              @Lazy ReservationService self) {
        this.reservationMapper = reservationMapper;
        this.occupancyMapper = occupancyMapper;
        this.statusLogMapper = statusLogMapper;
        this.venueMapper = venueMapper;
        this.spaceMapper = spaceMapper;
        this.entityExistenceService = entityExistenceService;
        this.venueAccessService = venueAccessService;
        this.sysConfigService = sysConfigService;
        this.redisTemplate = redisTemplate;
        this.orderService = orderService;
        this.lifecycleService = lifecycleService;
        this.noticeService = noticeService;
        this.businessEventService = businessEventService;
        this.funnelEventService = funnelEventService;
        this.inventoryService = inventoryService;
        this.promotionPricingService = promotionPricingService;
        this.couponService = couponService;
        this.pricingSnapshotFactory = pricingSnapshotFactory;
        this.createIdempotencyService = createIdempotencyService;
        this.self = self;
    }

    @Autowired
    public void setResourceAllocationService(ResourceAllocationService resourceAllocationService) {
        this.resourceAllocationService = resourceAllocationService;
    }

    @Autowired(required = false)
    public void setLifecycleChangePublisher(ReservationLifecycleChangePublisher lifecycleChangePublisher) {
        this.lifecycleChangePublisher = lifecycleChangePublisher;
    }

    public List<AvailabilitySlot> availability(Long spaceId, LocalDate date) {
        VenueSpace space = requireSpace(spaceId);
        return inventoryService.dailyInventory(space.venueId, spaceId, date, false).stream()
            .map(this::toAvailabilitySlot)
            .toList();
    }

    /**
     * 场馆级可约聚合（只读）：对给定场馆逐场地复用 dailyInventory 权威逻辑，
     * 取当天 earliest 可约时段与可约档位数。查询有界：单次最多 25 个场馆、每馆最多 20 个在售场地。
     */
    @Transactional(readOnly = true)
    public List<VenueAvailabilitySummary> venueAvailabilitySummary(LocalDate date, List<Long> venueIds) {
        if (date == null) {
            throw BusinessException.badRequest("库存日期不能为空");
        }
        if (venueIds == null || venueIds.isEmpty()) {
            throw BusinessException.badRequest("场馆 ID 不能为空");
        }
        if (venueIds.size() > 25) {
            throw BusinessException.badRequest("单次最多查询 25 个场馆的可约概况");
        }
        List<VenueAvailabilitySummary> summaries = new ArrayList<>(venueIds.size());
        for (Long venueId : venueIds) {
            summaries.add(venueAvailability(venueId, date));
        }
        return summaries;
    }

    private VenueAvailabilitySummary venueAvailability(Long venueId, LocalDate date) {
        VenueAvailabilitySummary summary = new VenueAvailabilitySummary();
        summary.venueId = venueId;
        summary.available = false;
        summary.availableSlotCount = 0;
        List<VenueSpace> spaces = spaceMapper.selectList(new QueryWrapper<VenueSpace>()
            .eq("venue_id", venueId).eq("deleted", 0).eq("status", "ACTIVE")
            .orderByAsc("id")
            .last("limit 20"));
        InventorySlot earliest = null;
        int availableSlots = 0;
        for (VenueSpace space : spaces) {
            for (InventorySlot slot : inventoryService.dailyInventory(venueId, space.id, date, false)) {
                if (!"AVAILABLE".equals(slot.status)) continue;
                availableSlots++;
                if (earliest == null || slot.startTime.isBefore(earliest.startTime)) earliest = slot;
            }
        }
        summary.availableSlotCount = availableSlots;
        if (earliest != null) {
            summary.available = true;
            summary.spaceId = earliest.spaceId;
            summary.startTime = earliest.startTime;
            summary.endTime = earliest.endTime;
            summary.price = earliest.price;
        }
        return summary;
    }

    public List<AvailabilitySlot> rescheduleSlots(Long reservationId, LocalDate date) {
        Reservation reservation = requireReservation(reservationId);
        if (!Objects.equals(reservation.userId, SecurityContext.currentUserId())) {
            throw BusinessException.forbidden("不能查询他人的改期库存");
        }
        ensureUnpaidReschedulable(ReservationStatus.valueOf(reservation.status));
        VenueSpace space = requireSpace(reservation.spaceId);
        return inventoryService.dailyInventory(
                space.venueId, reservation.spaceId, date, false, reservation.id).stream()
            .map(this::toAvailabilitySlot)
            .toList();
    }

    public ResourceAvailabilityResponse resourceAvailability(Long spaceId,
                                                            LocalDateTime startTime,
                                                            LocalDateTime endTime,
                                                            Integer participantCount,
                                                            List<Long> seatIds) {
        if (SecurityContext.currentUserId() == null) {
            throw BusinessException.forbidden("未登录");
        }
        requireSpace(spaceId);
        ensureRequestBounds(startTime, endTime);
        ensureSlotLimit(slotCount(startTime, endTime));
        return resourceAvailabilityInternal(spaceId, startTime, endTime, participantCount, seatIds, null);
    }

    public ResourceAvailabilityResponse rescheduleAvailability(Long reservationId,
                                                              LocalDateTime startTime,
                                                              LocalDateTime endTime) {
        Reservation reservation = requireReservation(reservationId);
        if (!Objects.equals(reservation.userId, SecurityContext.currentUserId())) {
            throw BusinessException.forbidden("不能查询他人的改期库存");
        }
        ensureUnpaidReschedulable(ReservationStatus.valueOf(reservation.status));
        ensureRequestBounds(startTime, endTime);
        ensureSlotLimit(slotCount(startTime, endTime));
        List<Long> seatIds = resourceAllocationService == null
            ? List.of()
            : resourceAllocationService.activeSeatIds(reservation.id);
        return resourceAvailabilityInternal(
            reservation.spaceId,
            startTime,
            endTime,
            reservation.participantCount,
            seatIds,
            reservation.id
        );
    }

    public ReservationRules rules() {
        ReservationRules rules = new ReservationRules();
        rules.maxSlotsPerBooking = sysConfigService.getInt("reservation.max_slots_per_booking", 1);
        rules.advanceDays = reservationAdvanceDays();
        rules.freeCancelHours = sysConfigService.getInt("reservation.free_cancel_hours", 24);
        rules.lockSeconds = sysConfigService.getInt("reservation.lock_seconds", 900);
        return rules;
    }

    @Transactional(readOnly = true)
    public PricingPreviewResponse pricingPreview(CreateReservationRequest request) {
        return quotePricing(request);
    }

    @Transactional(readOnly = true)
    public PricingPreviewResponse quote(ReservationQuoteRequest request) {
        CreateReservationRequest selection = new CreateReservationRequest();
        selection.spaceId = request.spaceId;
        selection.startTime = request.startTime;
        selection.endTime = request.endTime;
        selection.slots = request.slots;
        selection.participantCount = request.participantCount;
        selection.seatIds = request.seatIds;
        selection.userCouponId = request.userCouponId;
        return quotePricing(selection);
    }

    private PricingPreviewResponse quotePricing(CreateReservationRequest request) {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        TimeRange requestedRange = normalizeRequestedRange(request);
        if (!requestedRange.startTime().isBefore(requestedRange.endTime())) {
            throw BusinessException.badRequest("预约开始时间必须早于结束时间");
        }
        VenueSpace space = requireSpace(request.spaceId);
        requireVenue(space.venueId);
        ensureRequestBounds(requestedRange.startTime(), requestedRange.endTime());
        ensureBookableByInventory(space.id, requestedRange.startTime(), requestedRange.endTime(),
            request.participantCount, request.seatIds);
        PromotionPricingContext pricingContext = previewPromotionPricing(userId, space, requestedRange, request.userCouponId);
        return toPricingPreviewResponse(pricingContext);
    }

    public Reservation myReservation(Long id) {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        Reservation reservation = requireReservation(id);
        if (!Objects.equals(reservation.userId, userId)) {
            throw BusinessException.forbidden("不能查看他人的预约");
        }
        enrichPage(List.of(reservation));
        return reservation;
    }

    /**
     * 预约创建流程。
     * <p>执行顺序：获取 Redis 分布式锁 → 校验时段可用性 → 促销引擎定价 → 插入预约记录 →
     * 锁定优惠券 → 创建占用记录 → 记录状态日志 → 记录业务事件 → 记录漏斗事件 →
     * 发送通知 → 自动审核（如配置）。</p>
     * <p>关键决策点：Redis 所有者锁在事务完成后释放，场地行锁兜底并发提交；时段校验确保不与已有预约重叠；
     * 定价由 PromotionPricingService 计算，支持会员折扣和优惠券。</p>
     * <p>副作用：写入 reservation、reservation_occupancy、reservation_status_log、
     * business_event、funnel_event；锁定 user_coupon；发送通知；可能触发审核流程。</p>
     */
    @Transactional
    public Reservation create(CreateReservationRequest request) {
        return create(request, null);
    }

    /**
     * 预约创建流程（带请求身份）。
     * <p>执行顺序：抢占请求身份 → 获取 Redis 分布式锁 → 校验时段可用性 → 促销引擎定价 → 插入预约记录 →
     * 锁定优惠券 → 创建占用记录 → 记录状态日志 → 记录业务事件 → 记录漏斗事件 →
     * 发送通知 → 自动审核（如配置）。</p>
     * <p>关键决策点：同一次提交意图（Idempotency-Key + 载荷指纹）由数据库唯一约束串行化，
     * 重放命中时直接返回原预约，不再执行任何副作用；Redis 所有者锁在事务完成后释放，
     * 场地行锁兜底并发提交。</p>
     * <p>副作用：写入 reservation_create_command、reservation、reservation_occupancy、
     * reservation_status_log、business_event、funnel_event；锁定 user_coupon；发送通知；可能触发审核流程。</p>
     */
    @Transactional
    public Reservation create(CreateReservationRequest request, String rawIdempotencyKey) {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        TimeRange requestedRange = normalizeRequestedRange(request);
        if (!requestedRange.startTime().isBefore(requestedRange.endTime())) {
            throw BusinessException.badRequest("预约开始时间必须早于结束时间");
        }
        // 请求身份先于任何库存判断：原提交的结果可能已经占用库存，
        // 若先做可售预检再拒绝重放，会把「其实已成功」重新判成冲突（AR-01）。
        String idempotencyKey = createIdempotencyService == null
            ? null : createIdempotencyService.normalizeKey(rawIdempotencyKey);
        ReservationCreateIdempotencyService.Claim claim = null;
        if (idempotencyKey != null) {
            String requestHash = createIdempotencyService.fingerprint(
                request, requestedRange.startTime(), requestedRange.endTime());
            claim = createIdempotencyService.claim(userId, idempotencyKey, requestHash);
            if (claim.replayed()) {
                return replayCreatedReservation(userId, claim);
            }
        }
        String lockKey = "reservation:lock:space:" + request.spaceId + ":date:" + requestedRange.startTime().toLocalDate();
        String lockToken = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken,
            Duration.ofSeconds(reservationLockSeconds()));
        if (!Boolean.TRUE.equals(locked)) {
            throw BusinessException.conflict("该时段正在被其他用户提交，请稍后刷新");
        }
        boolean releaseScheduled = false;
        try {
            releaseScheduled = releaseReservationLockAfterTransaction(lockKey, lockToken);
            // 运行态按 venue共享读锁→space排他锁加锁；管理写入仍使用venue排他锁，保持同一锁顺序。
            // 未注入新分配服务只发生在兼容旧单测时，保留 V1 的 space→venue fallback。
            VenueSpace space;
            Venue venue;
            if (resourceAllocationService == null) {
                space = entityExistenceService.requireSpaceForUpdate(request.spaceId);
                venue = entityExistenceService.requireVenueForUpdate(space.venueId);
            } else {
                venue = spaceMapper.findVenueBySpaceIdForShare(request.spaceId);
                if (venue == null) {
                    throw BusinessException.notFound("场地不存在");
                }
                space = entityExistenceService.requireSpaceForUpdate(request.spaceId);
            }
            if (!Objects.equals(space.venueId, venue.id)) {
                throw BusinessException.badRequest("场地与场馆不匹配");
            }
            ensureRequestBounds(requestedRange.startTime(), requestedRange.endTime());
            ensureBookableByInventory(space.id, requestedRange.startTime(), requestedRange.endTime(),
                request.participantCount, request.seatIds);
            Reservation reservation = new Reservation();
            reservation.userId = userId;
            reservation.venueId = space.venueId;
            reservation.spaceId = request.spaceId;
            reservation.startTime = requestedRange.startTime();
            reservation.endTime = requestedRange.endTime();
            reservation.status = ReservationStatus.PENDING_REVIEW.name();
            reservation.contactName = request.contactName;
            reservation.contactPhone = request.contactPhone;
            reservation.remark = request.remark;
            PromotionPricingContext pricingContext = previewPromotionPricing(userId, space, requestedRange, request.userCouponId);
            pricingContext = reserveCampaignBudgetForCreation(pricingContext, userId, space, requestedRange,
                request.userCouponId);
            rejectSelectedUnavailableCoupon(request.userCouponId, pricingContext);
            AmountBreakdown amountBreakdown = pricingContext.amountBreakdown;
            reservation.amount = amountBreakdown.payableAmount();
            reservation.originalAmount = amountBreakdown.originalAmount();
            reservation.memberDiscountAmount = amountBreakdown.memberDiscountAmount();
            reservation.couponDiscountAmount = amountBreakdown.couponDiscountAmount();
            reservation.activityDiscountAmount = amountBreakdown.activityDiscountAmount();
            reservation.payableAmount = amountBreakdown.payableAmount();
            reservation.paidAmount = amountBreakdown.paidAmount();
            reservation.feeAmount = amountBreakdown.feeAmount();
            reservation.netAmount = amountBreakdown.netAmount();
            reservation.pricingSnapshotJson = pricingSnapshotFactory.promotionSnapshot(pricingContext);
            reservation.promotionCampaignId = pricingContext.campaign == null ? null : pricingContext.campaign.id;
            reservation.priceRuleId = pricingContext.priceRule == null ? null : pricingContext.priceRule.id;
            reservation.couponTemplateId = pricingContext.coupon == null ? null : pricingContext.coupon.templateId;
            reservation.userCouponId = pricingContext.coupon == null ? null : pricingContext.coupon.userCouponId;
            reservation.createTime = LocalDateTime.now();
            reservation.updateTime = reservation.createTime;
            reservation.deleted = 0;
            reservationMapper.insert(reservation);
            if (reservation.userCouponId != null) {
                reservation.couponLockToken = "reservation-" + reservation.id + "-" + UUID.randomUUID();
                reservation.updateTime = LocalDateTime.now();
                reservationMapper.updateById(reservation);
                couponService.lockForReservation(userId, reservation.userCouponId, reservation.id, reservation.couponLockToken);
            }
            if (resourceAllocationService == null) {
                List<Reservation> overlaps = reservationMapper.findOverlapsByStatuses(
                    reservation.spaceId, reservation.startTime, reservation.endTime,
                    ReservationStatus.occupyingStatusNames()).stream()
                    .filter(r -> !Objects.equals(r.id, reservation.id))
                    .toList();
                if (!overlaps.isEmpty()) {
                    throw BusinessException.conflict("该时段已被预约，请重新选择");
                }
                createOccupancy(reservation);
            } else {
                resourceAllocationService.allocate(reservation, request.participantCount, request.seatIds);
            }
            if (claim != null) {
                // 与预约创建同一事务：事务回滚会一并删除请求身份记录，
                // 但查询无记录也可能是事务尚未提交，客户端必须保留原请求键。
                createIdempotencyService.complete(claim, reservation.id, reservation.status);
            }
            ReservationStatusLog statusLog = writeLog(
                reservation.id, null, reservation.status, "用户提交预约", "USER");
            writeReservationCreatedEvent(reservation, amountBreakdown, pricingContext);
            if (lifecycleChangePublisher != null) {
                if (statusLog.id != null) {
                    lifecycleChangePublisher.publishSubmitted(reservation, statusLog);
                } else {
                    lifecycleChangePublisher.publishSubmitted(reservation);
                }
            }
            funnelEventService.recordMilestone(
                "RESERVATION_SUBMITTED",
                BusinessDomainConst.RESERVATION,
                reservation.userId,
                reservation.venueId,
                reservation.id,
                null,
                reservation.payableAmount,
                "SUCCESS",
                null,
                "reservation-submitted-" + reservation.id,
                Map.of("spaceId", reservation.spaceId, "status", reservation.status),
                // A26-P1-03：从请求上下文（X-Funnel-Session header 经 FunnelSessionFilter 绑定到 ThreadLocal）
                // 提取 sessionKey，使提交预约里程碑与前序浏览器事件共享同一 sessionKey，避免漏斗 cohort 断链。
                FunnelSessionContext.current());
            noticeService.createMessage(userId, "预约已提交", "预约已提交，请等待场馆管理员审核。", "RESERVATION", reservation.id);
            if (sysConfigService.getBoolean("reservation.auto_approval", false)) {
                systemApprove(reservation, "系统自动审核通过");
                return reservationMapper.selectById(reservation.id);
            }
            notifyVenueManagersForReservationReview(reservation);
            return reservation;
        } catch (DuplicateKeyException ex) {
            throw BusinessException.conflict("该时段已被占用，请重新选择");
        } finally {
            if (!releaseScheduled) {
                releaseReservationLock(lockKey, lockToken);
            }
        }
    }

    /**
     * 只读核对一次提交意图的结果，供刷新、返回或响应丢失后恢复。
     *
     * <p>不产生任何写入，也不改换请求键：{@code NONE} 表示当前读视图尚未看到该键结果（不排除未提交事务），
     * {@code PROCESSING} 表示原请求仍在处理中，{@code COMPLETED} 返回真实预约。
     * 键的作用域包含当前用户，不会返回他人的请求或预约。</p>
     */
    @Transactional(readOnly = true)
    public ReservationSubmissionResult submissionResult(String rawIdempotencyKey) {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        String idempotencyKey = createIdempotencyService == null
            ? null : createIdempotencyService.normalizeKey(rawIdempotencyKey);
        if (idempotencyKey == null) {
            return ReservationSubmissionResult.none();
        }
        ReservationCreateCommand command = createIdempotencyService.find(userId, idempotencyKey);
        if (command == null) {
            return ReservationSubmissionResult.none();
        }
        if (!"COMPLETED".equals(command.status) || command.reservationId == null) {
            return ReservationSubmissionResult.processing(command.reservationId);
        }
        Reservation reservation = reservationMapper.selectById(command.reservationId);
        if (reservation == null || !Objects.equals(reservation.userId, userId)) {
            // 记录与预约不一致时按「仍在核对」呈现，不暴露他人数据，也不伪装成失败。
            return ReservationSubmissionResult.processing(command.reservationId);
        }
        return ReservationSubmissionResult.completed(reservation);
    }

    /** 重放命中时直接返回原预约；此时不做库存分配、不锁券、不占预算、不写日志/事件、不发通知。 */
    private Reservation replayCreatedReservation(Long userId, ReservationCreateIdempotencyService.Claim claim) {
        Long reservationId = claim.command().reservationId;
        // 用锁定读取原结果：并发下败者事务可能早于胜者提交就建立了读视图，
        // 一致性读会看不到刚提交的预约，从而把「已成功」误判成无法核对。
        Reservation existing = reservationId == null ? null : reservationMapper.selectByIdForUpdate(reservationId);
        if (existing == null || !Objects.equals(existing.userId, userId)
            || (existing.deleted != null && existing.deleted != 0)) {
            throw new ReservationSubmissionStateException(ReservationSubmissionState.SUBMISSION_IN_PROGRESS,
                "这次提交的结果暂时无法核对，请稍后重试");
        }
        return existing;
    }

    public PageResult<Reservation> myReservations(ReservationQuery query) {
        Long userId = SecurityContext.currentUserId();
        QueryWrapper<Reservation> wrapper = new QueryWrapper<Reservation>().eq("user_id", userId).eq("deleted", 0);
        applyReservationQuery(wrapper, query);
        Page<Reservation> page = reservationMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper.orderByDesc("id"));
        enrichPage(page.getRecords());
        return PageResult.of(page.getRecords(), page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    private void enrichPage(List<Reservation> reservations) {
        if (reservations == null || reservations.isEmpty()) return;
        Set<Long> venueIds = new HashSet<>();
        Set<Long> reservationIds = new HashSet<>();
        for (Reservation reservation : reservations) {
            if (reservation.venueId != null) venueIds.add(reservation.venueId);
            if (reservation.id != null) reservationIds.add(reservation.id);
        }
        Map<Long, String> venueNames = new HashMap<>();
        if (!venueIds.isEmpty()) {
            venueMapper.selectBatchIds(venueIds).forEach(venue -> venueNames.put(venue.id, venue.name));
        }
        Map<Long, Long> orderIds = orderService.findOrderIdsByReservationIds(reservationIds);
        for (Reservation reservation : reservations) {
            reservation.venueName = venueNames.get(reservation.venueId);
            if (orderIds != null) reservation.orderId = orderIds.get(reservation.id);
        }
    }

    public PageResult<Reservation> adminReservations(ReservationQuery query) {
        QueryWrapper<Reservation> wrapper = new QueryWrapper<Reservation>().eq("deleted", 0);
        if (query.venueId != null) {
            venueAccessService.requireVenueManageAccess(query.venueId);
            wrapper.eq("venue_id", query.venueId);
        } else {
            List<Long> manageableVenueIds = venueAccessService.manageableVenueIds();
            if (manageableVenueIds != null) {
                if (manageableVenueIds.isEmpty()) {
                    return PageResult.of(List.of(), 0, query.getPageNum(), query.getPageSize());
                }
                wrapper.in("venue_id", manageableVenueIds);
            }
        }
        applyReservationQuery(wrapper, query);
        Page<Reservation> page = reservationMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    @Transactional
    public ReservationRescheduleResponse requestReschedule(Long reservationId, RescheduleRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("改期申请不能为空");
        }
        Reservation reservation = requireReservationForUpdate(reservationId);
        if (!Objects.equals(reservation.userId, SecurityContext.currentUserId())) {
            throw BusinessException.forbidden("不能改期他人的预约");
        }
        ReservationStatus status = ReservationStatus.valueOf(reservation.status);
        ensureUnpaidReschedulable(status);
        ensureRequestBounds(request.startTime, request.endTime);
        // A26-P3-04：改期目标时段与 create 路径对齐校验 max_slots_per_booking，
        // 避免改期绕过单次最大时段数限制（create 两个分支都调 ensureSlotLimit）。
        ensureSlotLimit(slotCount(request.startTime, request.endTime));
        ensureBookableByInventoryExcludingReservation(reservation, request.startTime, request.endTime);
        return ReservationRescheduleResponse.from(lifecycleService.createRescheduleRequest(reservation, request));
    }

    public PageResult<ReservationRescheduleResponse> myRescheduleRequests(PageQuery query) {
        return toRescheduleResponses(lifecycleService.myRescheduleRequests(query, SecurityContext.currentUserId()));
    }

    public PageResult<ReservationRescheduleResponse> adminRescheduleRequests(PageQuery query) {
        List<Long> manageableVenueIds = venueAccessService.manageableVenueIds();
        return toRescheduleResponses(lifecycleService.adminRescheduleRequests(query, manageableVenueIds));
    }

    private PageResult<ReservationRescheduleResponse> toRescheduleResponses(
        PageResult<ReservationRescheduleRequest> page
    ) {
        return PageResult.of(
            page.getRecords().stream().map(ReservationRescheduleResponse::from).toList(),
            page.getTotal(),
            page.getPageNum(),
            page.getPageSize()
        );
    }

    @Transactional
    public void reviewReschedule(Long requestId, boolean approve, String reviewReason) {
        // A26-P3-03：审核入口锁申请行，串行化并发"通过+拒绝"，避免"申请记为拒绝但预约已被改期"
        // 的矛盾终态。锁后重读的 request 用于后续 PENDING_REVIEW 守卫与 approve/reject 路径。
        ReservationRescheduleRequest request = lifecycleService.requireRescheduleRequestForUpdate(requestId);
        venueAccessService.requireVenueManageAccess(request.venueId);
        if (!"PENDING_REVIEW".equals(request.status)) {
            throw BusinessException.badRequest("当前改期申请不可审核");
        }
        if (!approve) {
            lifecycleService.rejectRescheduleRequest(request, reviewReason);
            return;
        }

        Reservation reservation = requireReservationForUpdate(request.reservationId);
        if (!Objects.equals(reservation.venueId, request.venueId)
            || !Objects.equals(reservation.spaceId, request.spaceId)) {
            throw BusinessException.badRequest("改期申请与预约不匹配");
        }
        if (!Objects.equals(reservation.startTime, request.fromStartTime)
            || !Objects.equals(reservation.endTime, request.fromEndTime)) {
            throw BusinessException.conflict("改期申请已过期，原预约时段已变化");
        }
        ReservationStatus status = ReservationStatus.valueOf(reservation.status);
        ensureUnpaidReschedulable(status);
        ensureRequestBounds(request.toStartTime, request.toEndTime);
        // A26-P3-04：改期目标时段与 create 路径对齐校验 max_slots_per_booking，
        // 避免审核通过绕过单次最大时段数限制（create 两个分支都调 ensureSlotLimit）。
        ensureSlotLimit(slotCount(request.toStartTime, request.toEndTime));
        String lockKey = "reservation:lock:space:" + reservation.spaceId + ":date:" + request.toStartTime.toLocalDate();
        String lockToken = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken,
            Duration.ofSeconds(reservationLockSeconds()));
        if (!Boolean.TRUE.equals(locked)) {
            throw BusinessException.conflict("该时段正在被其他用户提交，请稍后刷新");
        }
        boolean releaseScheduled = false;
        try {
            releaseScheduled = releaseReservationLockAfterTransaction(lockKey, lockToken);
            // 统一锁顺序 venue→space：与 create/createBlock/saveCalendar 形成共同栅栏，
            // 防止改期与并发预约/不可售块/闭馆日历修改产生超卖或冲突遗漏。
            entityExistenceService.requireVenueForUpdate(reservation.venueId);
            VenueSpace space = entityExistenceService.requireSpaceForUpdate(reservation.spaceId);
            if (!Objects.equals(space.venueId, reservation.venueId)) {
                throw BusinessException.badRequest("预约场地与场馆不匹配");
            }
            ensureBookableByInventoryExcludingReservation(reservation, request.toStartTime, request.toEndTime);
            Long lockedUserCouponId = reservation.userCouponId;
            PromotionPricingContext pricingContext = previewPromotionPricing(reservation.userId, space,
                new TimeRange(request.toStartTime, request.toEndTime), lockedUserCouponId);
            rejectSelectedUnavailableCoupon(lockedUserCouponId, pricingContext);
            if (lockedUserCouponId != null && (pricingContext.coupon == null
                || !Objects.equals(pricingContext.coupon.userCouponId, lockedUserCouponId))) {
                throw BusinessException.badRequest("优惠券不可用");
            }
            // AUD-P2-04：改期审核通过时先回补原活动预算，再占用新活动预算，与 create 占用对称。
            // 仅当原预约绑定活动并实际占用折扣金额时回补；无 budget 的活动在 create 路径未被 consume 累加，
            // 回补 SQL 的 budget_used_amount >= discountAmount 条件会自然跳过，不会误减。
            promotionPricingService.releaseCampaignBudgetForReservation(reservation);
            // AUD-P2-04：占用新活动预算，与 create 保持一致。
            pricingContext = reserveCampaignBudgetForCreation(pricingContext, reservation.userId, space,
                new TimeRange(request.toStartTime, request.toEndTime), lockedUserCouponId);
            applyPromotionPricing(reservation, pricingContext);
            if (resourceAllocationService == null) {
                occupancyMapper.releaseByReservationId(reservation.id);
                reservation.startTime = request.toStartTime;
                reservation.endTime = request.toEndTime;
                reservation.updateTime = LocalDateTime.now();
                List<Reservation> overlaps = reservationMapper.findOverlapsByStatuses(
                    reservation.spaceId, request.toStartTime, request.toEndTime,
                    ReservationStatus.occupyingStatusNames()).stream()
                    .filter(r -> !Objects.equals(r.id, reservation.id))
                    .toList();
                if (!overlaps.isEmpty()) {
                    throw BusinessException.conflict("该时段已被预约，请重新选择");
                }
                createOccupancy(reservation);
            } else {
                resourceAllocationService.replace(reservation, request.toStartTime, request.toEndTime, null, null);
            }
            reservationMapper.updateById(reservation);
            if (status == ReservationStatus.PENDING_PAYMENT) {
                orderService.syncPendingOrderForRescheduledReservation(reservation);
            }
            lifecycleService.approveRescheduleRequest(request, reviewReason);
        } finally {
            if (!releaseScheduled) {
                releaseReservationLock(lockKey, lockToken);
            }
        }
    }

    /**
     * 审核通过后的联动逻辑。
     * <p>双路径处理：</p>
     * <ul>
     *   <li>拒绝路径：释放占用记录 → 释放优惠券锁定 → 发送拒绝通知</li>
     *   <li>通过路径：重新检查库存可用性 → 预约状态流转至 APPROVED → 创建待支付订单 →
     *       发送通过通知</li>
     * </ul>
     * <p>关键决策点：通过前重检库存防止审核期间被占用；订单创建后预约进入待支付状态，
     * 用户需在支付超时前完成支付。</p>
     * <p>副作用：更新 reservation 状态、写入 biz_order、更新 reservation_occupancy、
     * 更新 user_coupon、写入 reservation_status_log、发送通知。</p>
     */
    @Transactional
    public void approve(Long reservationId, boolean approve, String reason) {
        Reservation reservation = requireReservationForUpdate(reservationId);
        venueAccessService.requireVenueManageAccess(reservation.venueId);
        if (!ReservationStatus.PENDING_REVIEW.name().equals(reservation.status)) {
            throw BusinessException.badRequest("当前预约状态不可审核");
        }
        if (!approve) {
            if (reason == null || reason.isBlank()) {
                throw BusinessException.badRequest("拒绝预约必须填写原因");
            }
            reservation.rejectReason = reason;
            transition(reservation, ReservationStatus.REJECTED.name(), reason, "ADMIN");
            releaseOccupancy(reservation.id, "RESERVATION_REJECTED");
            couponService.releaseForReservation(reservation.id, reason == null ? "预约审核拒绝" : reason);
            // A26-P2-04：审核拒绝路径回补活动预算，与占用对称（create 时 consume，拒绝时 release）。
            // 拒绝是终态（REJECTED 不可再迁移），无后续退款路径重复回补风险。
            promotionPricingService.releaseCampaignBudgetForReservation(reservation);
            String userVisibleReason = sanitizeUserVisibleReason(
                reservation.rejectReason, "预约审核未通过。");
            noticeService.createBusinessNotification(new NotificationEvent(
                "RESERVATION_REJECTED",
                "RESERVATION_REJECTED:" + reservation.id,
                reservation.userId,
                NotificationRecipientKind.USER,
                "预约审核未通过",
                userVisibleReason,
                "RESERVATION",
                reservation.id,
                Map.of("reservationId", String.valueOf(reservation.id), "reason", userVisibleReason)
            ));
            return;
        }
        VenueSpace space = requireSpace(reservation.spaceId);
        requireVenue(reservation.venueId);
        if (!Objects.equals(space.venueId, reservation.venueId)) {
            throw BusinessException.badRequest("预约场地与场馆不匹配");
        }
        ensureRequestBounds(reservation.startTime, reservation.endTime);
        ensureBookableByInventoryExcludingReservation(reservation, reservation.startTime, reservation.endTime);
        transition(reservation, ReservationStatus.PENDING_PAYMENT.name(), reason, "ADMIN");
        reservation.payExpireTime = LocalDateTime.now().plusMinutes(sysConfigService.getInt("payment.timeout_minutes", 30));
        reservationMapper.updateById(reservation);
        orderService.createPendingOrder(reservation, sysConfigService.getInt("payment.timeout_minutes", 30));
        noticeService.createBusinessNotification(new NotificationEvent(
            "RESERVATION_APPROVED",
            "RESERVATION_APPROVED:" + reservation.id,
            reservation.userId,
            NotificationRecipientKind.USER,
            "预约审核通过",
            "预约已通过审核，请在支付截止前完成支付。",
            "RESERVATION",
            reservation.id,
            Map.of("reservationId", String.valueOf(reservation.id))
        ));
    }

    @Transactional
    public void cancel(Long reservationId, String reason, boolean admin) {
        cancel(reservationId, null, reason, admin);
    }

    @Transactional
    public void cancel(Long reservationId, String reasonCode, String reasonText, boolean admin) {
        Reservation reservation = requireReservationForUpdate(reservationId);
        if (admin) {
            venueAccessService.requireVenueManageAccess(reservation.venueId);
        } else if (!Objects.equals(reservation.userId, SecurityContext.currentUserId())) {
            throw BusinessException.forbidden("不能取消他人的预约");
        }
        ReservationStatus status = ReservationStatus.valueOf(reservation.status);
        if (UNCANCELABLE_STATUSES.contains(status)) {
            throw BusinessException.badRequest("当前预约状态不可取消");
        }
        int freeCancelHours = sysConfigService.getInt("reservation.free_cancel_hours", 24);
        if (!admin && reservation.startTime.minusHours(freeCancelHours).isBefore(LocalDateTime.now()) && status == ReservationStatus.PAID) {
            throw BusinessException.badRequest("已超过免费取消时间，请走退款申请");
        }
        String source = admin ? "ADMIN" : "USER";
        transition(reservation, ReservationStatus.CANCELED.name(), reasonText, source);
        lifecycleService.recordCancellation(reservation, status.name(), reasonCode, reasonText, source);
        releaseOccupancy(reservation.id, "RESERVATION_CANCELED");
        if (status != ReservationStatus.PAID) {
            couponService.releaseForReservation(reservation.id,
                reasonText == null || reasonText.isBlank() ? "预约取消" : reasonText);
            // A26-P2-04：非 PAID 取消（PENDING_REVIEW/APPROVED/PENDING_PAYMENT）回补活动预算。
            // PAID 取消不在此回补：状态机 PAID → CANCELED → REFUNDED，PAID 取消后会进入退款流程，
            // 由 markRefundSuccess 终态回补；非 PAID 取消直接到终态 CANCELED，无后续退款路径。
            promotionPricingService.releaseCampaignBudgetForReservation(reservation);
        }
        orderService.closeOrderForCanceledReservation(reservation.id, reasonText);
        noticeService.createBusinessNotification(new NotificationEvent(
            "RESERVATION_CANCELED",
            "RESERVATION_CANCELED:" + reservation.id + ":CANCELED",
            reservation.userId,
            NotificationRecipientKind.USER,
            "预约已取消",
            reasonText == null ? "预约已取消。" : reasonText,
            "RESERVATION",
            reservation.id,
            Map.of(
                "reservationId", String.valueOf(reservation.id),
                "status", ReservationStatus.CANCELED.name(),
                "source", source)
        ));
        if (!admin) {
            notifyVenueManagersInAppOnly(reservation, "用户取消预约",
                reasonText == null || reasonText.isBlank() ? "用户已取消预约。" : reasonText);
        }
    }

    /**
     * 按主键游标扫描已结束的已支付预约并执行自动完成，避免全量加载且不遗漏状态变化后的相邻记录。
     *
     * @param batchSize 每批处理的记录数
     */
    public void completeStartedReservationsBatch(int batchSize) {
        LocalDateTime now = LocalDateTime.now();
        Long lastScannedId = null;
        while (true) {
            QueryWrapper<Reservation> query = new QueryWrapper<Reservation>()
                .eq("status", ReservationStatus.PAID.name())
                .lt("end_time", now)
                .orderByAsc("id");
            if (lastScannedId != null) {
                query.gt("id", lastScannedId);
            }
            Page<Reservation> page = reservationMapper.selectPage(new Page<>(1, batchSize), query);
            if (page.getRecords().isEmpty()) {
                break;
            }
            Long nextScannedId = page.getRecords().get(page.getRecords().size() - 1).id;
            for (Reservation reservation : page.getRecords()) {
                try {
                    self.completeOneReservation(reservation, now);
                } catch (Exception ex) {
                    log.error("预约自动完成失败 reservationId={}", reservation.id, ex);
                }
            }
            if (page.getRecords().size() < batchSize) {
                break;
            }
            if (nextScannedId == null || lastScannedId != null && nextScannedId <= lastScannedId) {
                log.warn("预约自动完成批处理游标未前进，停止本轮扫描 lastScannedId={}", lastScannedId);
                break;
            }
            lastScannedId = nextScannedId;
        }
    }

    /**
     * 单条预约自动完成，独立事务隔离失败记录。
     * <p>使用 REQUIRES_NEW 传播级别，确保单条失败仅回滚自身事务，不影响批次内其他记录。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeOneReservation(Reservation reservation, LocalDateTime now) {
        if (reservation == null || reservation.id == null) {
            return;
        }
        Reservation lockedReservation = requireReservationForUpdate(reservation.id);
        if (!ReservationStatus.PAID.name().equals(lockedReservation.status)) {
            return;
        }
        CompletionDecision decision = lifecycleService.processCompletionCandidate(lockedReservation, now);
        if (decision != null && decision.shouldComplete()) {
            transition(lockedReservation, ReservationStatus.COMPLETED.name(), decision.reasonText(), "SYSTEM");
            releaseOccupancy(lockedReservation.id, "RESERVATION_COMPLETED");
            orderService.closePaidOrderForCompletedReservation(lockedReservation.id, decision.reasonText());
        }
    }

    private void applyReservationQuery(QueryWrapper<Reservation> wrapper, ReservationQuery query) {
        if (query.spaceId != null) {
            wrapper.eq("space_id", query.spaceId);
        }
        if (query.status != null && !query.status.isBlank()) {
            wrapper.eq("status", query.status);
        }
        if (query.date != null && !query.date.isBlank()) {
            LocalDate date = LocalDate.parse(query.date);
            wrapper.ge("start_time", date.atStartOfDay()).lt("start_time", date.plusDays(1).atStartOfDay());
        }
    }

    private void createOccupancy(Reservation reservation) {
        if (!reservation.startTime.toLocalDate().equals(reservation.endTime.toLocalDate())) {
            throw BusinessException.badRequest("暂不支持跨日预约");
        }
        ReservationOccupancy occupancy = new ReservationOccupancy();
        occupancy.reservationId = reservation.id;
        occupancy.spaceId = reservation.spaceId;
        occupancy.slotDate = reservation.startTime.toLocalDate();
        occupancy.startTime = reservation.startTime.toLocalTime();
        occupancy.endTime = reservation.endTime.toLocalTime();
        occupancy.activeFlag = 1;
        occupancyMapper.insert(occupancy);
    }

    private TimeRange normalizeRequestedRange(CreateReservationRequest request) {
        if (request.slots == null || request.slots.isEmpty()) {
            if (request.startTime == null || request.endTime == null) {
                throw BusinessException.badRequest("请选择预约时段");
            }
            ensureSlotLimit(slotCount(request.startTime, request.endTime));
            return new TimeRange(request.startTime, request.endTime);
        }
        ensureSlotLimit(request.slots.size());
        for (AvailabilitySlot slot : request.slots) {
            if (slot.startTime == null || slot.endTime == null || !slot.startTime.isBefore(slot.endTime)) {
                throw BusinessException.badRequest("预约时段不合法");
            }
        }
        List<AvailabilitySlot> sortedSlots = request.slots.stream()
            .sorted((left, right) -> left.startTime.compareTo(right.startTime))
            .toList();
        LocalDate slotDate = sortedSlots.get(0).startTime.toLocalDate();
        LocalDateTime expectedStart = sortedSlots.get(0).startTime;
        for (AvailabilitySlot slot : sortedSlots) {
            if (!Objects.equals(slot.spaceId, request.spaceId)) {
                throw BusinessException.badRequest("只能预约同一个场地的时段");
            }
            if (!slot.startTime.toLocalDate().equals(slotDate) || !slot.endTime.toLocalDate().equals(slotDate)) {
                throw BusinessException.badRequest("暂不支持跨日预约");
            }
            if (!slot.startTime.equals(expectedStart)) {
                throw BusinessException.badRequest("只能选择连续时段");
            }
            expectedStart = slot.endTime;
        }
        return new TimeRange(sortedSlots.get(0).startTime, sortedSlots.get(sortedSlots.size() - 1).endTime);
    }

    private void ensureSlotLimit(int selectedCount) {
        int maxSlots = sysConfigService.getInt("reservation.max_slots_per_booking", 1);
        if (maxSlots < 1) {
            maxSlots = 1;
        }
        if (selectedCount < 1) {
            throw BusinessException.badRequest("请选择预约时段");
        }
        if (selectedCount > maxSlots) {
            throw BusinessException.badRequest("单次最多可选择 " + maxSlots + " 个时段");
        }
    }

    private int slotCount(LocalDateTime startTime, LocalDateTime endTime) {
        long minutes = Duration.between(startTime, endTime).toMinutes();
        if (minutes <= 0) {
            return 0;
        }
        return (int) Math.ceil(minutes / 60.0);
    }

    private void systemApprove(Reservation reservation, String reason) {
        transition(reservation, ReservationStatus.PENDING_PAYMENT.name(), reason, "SYSTEM");
        reservation.payExpireTime = LocalDateTime.now().plusMinutes(sysConfigService.getInt("payment.timeout_minutes", 30));
        reservationMapper.updateById(reservation);
        orderService.createPendingOrder(reservation, sysConfigService.getInt("payment.timeout_minutes", 30));
        noticeService.createBusinessNotification(new NotificationEvent(
            "RESERVATION_APPROVED",
            "RESERVATION_APPROVED:" + reservation.id,
            reservation.userId,
            NotificationRecipientKind.USER,
            "预约审核通过",
            "预约已自动审核通过，请在支付截止前完成支付。",
            "RESERVATION",
            reservation.id,
            Map.of("reservationId", String.valueOf(reservation.id))
        ));
    }

    private void writeReservationCreatedEvent(Reservation reservation, AmountBreakdown amountBreakdown,
                                              PromotionPricingContext pricingContext) {
        businessEventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.RESERVATION_CREATED,
                BusinessDomain.RESERVATION,
                "RESERVATION",
                reservation.id,
                BusinessSourceType.USER,
                "SUCCESS")
            .toStatus(ReservationStatus.PENDING_REVIEW.name())
            .reasonText("用户提交预约")
            .idempotencyKey("RESERVATION_CREATED:" + reservation.id)
            .metadata("venueId", reservation.venueId)
            .metadata("spaceId", reservation.spaceId)
            .metadata("startTime", reservation.startTime)
            .metadata("endTime", reservation.endTime)
            .metadata("amount", reservation.amount)
            .metadata("amountLegacy", reservation.amount)
            .metadata("amountBreakdown", amountBreakdown)
            .metadata("memberLevel", pricingContext.memberLevel == null ? null : pricingContext.memberLevel.levelCode)
            .metadata("memberAccountId", pricingContext.memberAccount == null ? null : pricingContext.memberAccount.memberAccountId)
            .metadata("promotionCampaignId", reservation.promotionCampaignId)
            .metadata("priceRuleId", reservation.priceRuleId)
            .metadata("couponTemplateId", reservation.couponTemplateId)
            .metadata("userCouponId", reservation.userCouponId)
            .metadata("pricingSnapshotVersion", PricingSnapshotFactory.B3_PROMOTION_PRICING_VERSION)
            .metadata("pricingSnapshotJson", reservation.pricingSnapshotJson));
    }

    private PromotionPricingContext previewPromotionPricing(Long userId, VenueSpace space, TimeRange requestedRange,
                                                            Long userCouponId) {
        return promotionPricingService.previewForReservation(
            userId,
            space.venueId,
            space.id,
            requestedRange.startTime(),
            requestedRange.endTime(),
            space.pricePerHour,
            userCouponId
        );
    }

    private PromotionPricingContext reserveCampaignBudgetForCreation(PromotionPricingContext pricingContext,
                                                                      Long userId, VenueSpace space,
                                                                      TimeRange requestedRange, Long userCouponId) {
        if (pricingContext.campaign == null || pricingContext.campaign.budgetAmount == null
            || pricingContext.amountBreakdown.activityDiscountAmount()
            .compareTo(BigDecimal.ZERO) <= 0) {
            return pricingContext;
        }
        if (promotionPricingService.consumeCampaignBudget(pricingContext.campaign.id,
            pricingContext.amountBreakdown.activityDiscountAmount())) {
            return pricingContext;
        }
        return promotionPricingService.previewForReservationExcludingCampaign(userId, space.venueId, space.id,
            requestedRange.startTime(), requestedRange.endTime(), space.pricePerHour, userCouponId,
            pricingContext.campaign.id);
    }

    // AUD-P2-04：活动预算回补已抽取到 PromotionPricingService.releaseCampaignBudgetForReservation 单点 public
    // 方法（含 CAS 幂等防护）。改期/取消/审核拒绝终态直接调用 promotionPricingService.releaseCampaignBudgetForReservation。
    // PAID 取消/支付超时/退款到账路径由 OrderService 调用同一 public 方法，CAS 幂等防双终态重复回补。


    private void rejectSelectedUnavailableCoupon(Long userCouponId, PromotionPricingContext pricingContext) {
        if (userCouponId == null || pricingContext == null || pricingContext.coupon == null) {
            return;
        }
        String unavailableReason = pricingContext.coupon.unavailableReason;
        if (unavailableReason != null && !unavailableReason.isBlank()) {
            throw BusinessException.badRequest(unavailableReason);
        }
    }

    private void applyPromotionPricing(Reservation reservation, PromotionPricingContext pricingContext) {
        AmountBreakdown amountBreakdown = pricingContext.amountBreakdown;
        reservation.amount = amountBreakdown.payableAmount();
        reservation.originalAmount = amountBreakdown.originalAmount();
        reservation.memberDiscountAmount = amountBreakdown.memberDiscountAmount();
        reservation.couponDiscountAmount = amountBreakdown.couponDiscountAmount();
        reservation.activityDiscountAmount = amountBreakdown.activityDiscountAmount();
        reservation.payableAmount = amountBreakdown.payableAmount();
        reservation.paidAmount = amountBreakdown.paidAmount();
        reservation.feeAmount = amountBreakdown.feeAmount();
        reservation.netAmount = amountBreakdown.netAmount();
        reservation.pricingSnapshotJson = pricingSnapshotFactory.promotionSnapshot(pricingContext);
        reservation.promotionCampaignId = pricingContext.campaign == null ? null : pricingContext.campaign.id;
        reservation.priceRuleId = pricingContext.priceRule == null ? null : pricingContext.priceRule.id;
        reservation.couponTemplateId = pricingContext.coupon == null ? null : pricingContext.coupon.templateId;
        reservation.userCouponId = pricingContext.coupon == null ? null : pricingContext.coupon.userCouponId;
        // AUD-P2-04：改期绑定新活动后重置预算回补标记，使新活动预算可在后续终态回补。
        // applyPromotionPricing 仅 reviewReschedule 调用（create 直接写字段），改期前已 release 旧活动并置 1，
        // 此处重置为 0，使新活动预算的终态回补 CAS 占位可生效。
        reservation.budgetReleased = 0;
    }

    private PricingPreviewResponse toPricingPreviewResponse(PromotionPricingContext pricingContext) {
        AmountBreakdown amountBreakdown = pricingContext.amountBreakdown;
        PricingPreviewResponse response = new PricingPreviewResponse();
        response.originalAmount = amountBreakdown.originalAmount();
        response.memberDiscountAmount = amountBreakdown.memberDiscountAmount();
        response.couponDiscountAmount = amountBreakdown.couponDiscountAmount();
        response.activityDiscountAmount = amountBreakdown.activityDiscountAmount();
        response.payableAmount = amountBreakdown.payableAmount();
        response.memberLevel = pricingContext.memberLevel;
        response.memberAccount = pricingContext.memberAccount;
        response.promotionCampaignId = pricingContext.campaign == null ? null : pricingContext.campaign.id;
        response.priceRuleId = pricingContext.priceRule == null ? null : pricingContext.priceRule.id;
        response.couponTemplateId = pricingContext.coupon == null ? null : pricingContext.coupon.templateId;
        response.userCouponId = pricingContext.coupon == null ? null : pricingContext.coupon.userCouponId;
        response.pricingVersion = pricingContext.pricingVersion == null
            ? PricingSnapshotFactory.B3_PROMOTION_PRICING_VERSION
            : pricingContext.pricingVersion;
        response.couponUnavailableReason = pricingContext.coupon == null ? null : pricingContext.coupon.unavailableReason;
        response.pricingSnapshotJson = pricingSnapshotFactory.promotionSnapshot(pricingContext);
        return response;
    }

    private void notifyVenueManagersForReservationReview(Reservation reservation) {
        List<Long> managerIds = venueAccessService.managerUserIds(reservation.venueId);
        if (managerIds == null || managerIds.isEmpty()) {
            noticeService.recordNoEligibleVenueAdminExclusion(
                "RESERVATION_REVIEW_REQUIRED",
                "RESERVATION_REVIEW_REQUIRED:" + reservation.id,
                "RESERVATION",
                reservation.id);
            return;
        }
        for (Long managerId : managerIds) {
            noticeService.createBusinessNotification(new NotificationEvent(
                "RESERVATION_REVIEW_REQUIRED",
                "RESERVATION_REVIEW_REQUIRED:" + reservation.id,
                managerId,
                NotificationRecipientKind.VENUE_ADMIN,
                "待审核预约",
                "有新的预约申请需要审核。",
                "RESERVATION",
                reservation.id,
                Map.of("reservationId", String.valueOf(reservation.id))
            ));
        }
    }

    private void notifyVenueManagersInAppOnly(Reservation reservation, String title, String content) {
        List<Long> managerIds = venueAccessService.managerUserIds(reservation.venueId);
        if (managerIds == null || managerIds.isEmpty()) {
            return;
        }
        for (Long managerId : managerIds) {
            if (!Objects.equals(managerId, reservation.userId)) {
                noticeService.createMessage(managerId, title, content, "RESERVATION", reservation.id);
            }
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

    private AvailabilitySlot toAvailabilitySlot(InventorySlot inventory) {
        AvailabilitySlot slot = new AvailabilitySlot();
        slot.spaceId = inventory.spaceId;
        slot.startTime = inventory.startTime;
        slot.endTime = inventory.endTime;
        slot.status = inventory.status;
        slot.inventoryStatus = inventory.status;
        slot.label = inventory.label;
        slot.price = inventory.price;
        slot.reason = inventory.reasonText;
        slot.reasonCode = inventory.reasonCode;
        slot.blockType = inventory.blockType;
        slot.blockId = inventory.blockId;
        slot.reservationId = inventory.reservationId;
        return slot;
    }

    private ResourceAvailabilityResponse resourceAvailabilityInternal(Long spaceId,
                                                                      LocalDateTime startTime,
                                                                      LocalDateTime endTime,
                                                                      Integer participantCount,
                                                                      List<Long> seatIds,
                                                                      Long excludedReservationId) {
        List<Long> requestedSeatIds = seatIds == null ? List.of() : seatIds;
        if (resourceAllocationService == null) {
            BookabilityDecision decision = excludedReservationId == null
                ? inventoryService.checkBookable(spaceId, startTime, endTime)
                : inventoryService.checkBookableExcludingReservation(spaceId, startTime, endTime,
                    excludedReservationId);
            // 兼容旧单测（未注入分配服务）的 V1 回退分支：没有资源规则，物理容量同样未知，
            // 因此 physicalCapacity 为 null，不在这里臆造物理上限。
            ResourceRuleResponse legacyRule = new ResourceRuleResponse(
                null,
                spaceId,
                "SPORTS_FIELD",
                "EXCLUSIVE",
                1,
                null,
                1,
                "ACTIVE",
                null,
                List.of()
            );
            return new ResourceAvailabilityResponse(
                spaceId,
                legacyRule,
                1,
                decision.bookable ? 1 : 0,
                decision.bookable,
                decision.reasonCode,
                decision.reasonText
            );
        }

        ResourceAvailabilityResponse allocation = resourceAllocationService.availability(
            spaceId,
            startTime,
            endTime,
            participantCount,
            requestedSeatIds,
            excludedReservationId
        );
        boolean selectionRequiredLater = "ASSIGNED_SEAT".equals(allocation.rule().bookingMode())
            && requestedSeatIds.isEmpty();
        BookabilityDecision inventory;
        if (excludedReservationId == null) {
            inventory = selectionRequiredLater
                ? inventoryService.checkBookable(spaceId, startTime, endTime)
                : inventoryService.checkBookable(spaceId, startTime, endTime,
                    allocation.participantCount(), requestedSeatIds);
        } else {
            inventory = selectionRequiredLater
                ? inventoryService.checkBookableExcludingReservation(spaceId, startTime, endTime,
                    excludedReservationId)
                : inventoryService.checkBookableExcludingReservation(spaceId, startTime, endTime,
                    excludedReservationId, allocation.participantCount(), requestedSeatIds);
        }
        if (!inventory.bookable) {
            return allocation.withDecision(false, inventory.reasonCode, inventoryRejectReason(inventory));
        }
        return allocation;
    }

    private void ensureRequestBounds(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw BusinessException.badRequest("预约开始时间必须早于结束时间");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!startTime.isAfter(now)) {
            throw BusinessException.badRequest("不能预约过去时段");
        }
        if (!startTime.toLocalDate().equals(endTime.toLocalDate())) {
            throw BusinessException.badRequest("暂不支持跨日预约");
        }
        if (startTime.toLocalDate().isAfter(now.toLocalDate().plusDays(reservationAdvanceDays()))) {
            throw BusinessException.badRequest("预约日期超过可提前预约天数");
        }
    }

    private void ensureBookableByInventory(Long spaceId,
                                           LocalDateTime startTime,
                                           LocalDateTime endTime,
                                           Integer participantCount,
                                           List<Long> seatIds) {
        BookabilityDecision decision = resourceAllocationService == null
            ? inventoryService.checkBookable(spaceId, startTime, endTime)
            : inventoryService.checkBookable(spaceId, startTime, endTime, participantCount, seatIds);
        ensureAcceptedInventoryDecision(decision);
    }

    private void ensureBookableByInventoryExcludingReservation(Reservation reservation,
                                                               LocalDateTime startTime,
                                                               LocalDateTime endTime) {
        BookabilityDecision decision;
        if (resourceAllocationService == null) {
            decision = inventoryService.checkBookableExcludingReservation(reservation.spaceId, startTime, endTime,
                reservation.id);
        } else {
            decision = inventoryService.checkBookableExcludingReservation(reservation.spaceId, startTime, endTime,
                reservation.id, reservation.participantCount, resourceAllocationService.activeSeatIds(reservation.id));
        }
        ensureAcceptedInventoryDecision(decision);
    }

    private void ensureUnpaidReschedulable(ReservationStatus status) {
        if (status == ReservationStatus.PAID) {
            throw BusinessException.badRequest("已支付预约暂不支持改期");
        }
        if (!UNPAID_RESCHEDULABLE_STATUSES.contains(status)) {
            throw BusinessException.badRequest("当前预约状态不可改期");
        }
    }

    private void ensureAcceptedInventoryDecision(BookabilityDecision decision) {
        if (decision == null || decision.bookable) {
            return;
        }
        AllocationConflictCode allocationConflictCode = allocationConflictCode(decision.reasonCode);
        if (allocationConflictCode != null) {
            throw new ResourceAllocationConflictException(allocationConflictCode, inventoryRejectReason(decision));
        }
        throw BusinessException.badRequest(inventoryRejectReason(decision));
    }

    /**
     * 决定库存拒绝是否以 409 资源冲突返回。
     *
     * <p>F-01（SHIGUANG-WAVE-01-REMEDIATION-20260916 S04）：{@code PHYSICAL_CAPACITY_EXCEEDED} 是
     * 「请求人数本身超过场地物理承载人数」的入参校验错误，与并发或库存争用无关，也不是重试能解决的
     * 冲突；创建主路径在 {@code ResourceAllocationService.allocate} 中同样以
     * {@link BusinessException#badRequest} 拒绝。若这里把它映射成 409，同一个越界请求会因为
     * 「先经过库存预检还是先进入分配」而返回不同状态码。因此显式排除它，统一为 400。
     */
    private AllocationConflictCode allocationConflictCode(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return null;
        }
        try {
            AllocationConflictCode code = AllocationConflictCode.valueOf(reasonCode);
            return code == AllocationConflictCode.PHYSICAL_CAPACITY_EXCEEDED ? null : code;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void releaseOccupancy(Long reservationId, String reason) {
        if (resourceAllocationService == null) {
            occupancyMapper.releaseByReservationId(reservationId);
        } else {
            resourceAllocationService.release(reservationId, reason);
        }
    }

    private String inventoryRejectReason(BookabilityDecision decision) {
        if (decision.reasonText != null && !decision.reasonText.isBlank()) {
            return decision.reasonText;
        }
        return switch (decision.status == null ? "" : decision.status) {
            case "RESERVED" -> "该时段已被预约";
            case "MANUAL_HOLD" -> "该时段为人工保留";
            case "BUFFER" -> "该时段处于清洁缓冲";
            case "UNAVAILABLE" -> "该时段不可预约";
            case "CLOSED" -> "该时段不可预约";
            default -> "该时段不可预约";
        };
    }

    private VenueSpace requireSpace(Long spaceId) {
        VenueSpace space = spaceMapper.selectById(spaceId);
        if (space == null || space.deleted != null && space.deleted == 1) {
            throw BusinessException.notFound("场地不存在");
        }
        return space;
    }

    private Venue requireVenue(Long venueId) {
        Venue venue = venueMapper.selectById(venueId);
        if (venue == null || venue.deleted != null && venue.deleted == 1) {
            throw BusinessException.notFound("场馆不存在");
        }
        return venue;
    }

    private Reservation requireReservation(Long id) {
        Reservation reservation = reservationMapper.selectById(id);
        if (reservation == null || reservation.deleted != null && reservation.deleted == 1) {
            throw BusinessException.notFound("预约不存在");
        }
        return reservation;
    }

    private Reservation requireReservationForUpdate(Long id) {
        Reservation reservation = reservationMapper.selectByIdForUpdate(id);
        if (reservation == null || reservation.deleted != null && reservation.deleted == 1) {
            throw BusinessException.notFound("预约不存在");
        }
        return reservation;
    }

    private void transition(Reservation reservation, String toStatus, String reason, String source) {
        String from = reservation.status;
        ReservationStatusTransitions.parseAndValidate(from, toStatus);
        reservation.status = toStatus;
        reservation.updateTime = LocalDateTime.now();
        reservationMapper.updateById(reservation);
        ReservationStatusLog statusLog = writeLog(reservation.id, from, toStatus, reason, source);
        if (lifecycleChangePublisher != null) {
            if (statusLog.id != null) {
                lifecycleChangePublisher.publishStatusChange(reservation, statusLog);
            } else {
                lifecycleChangePublisher.publishStatusChange(reservation, from, toStatus, reason, source);
            }
        }
    }

    private ReservationStatusLog writeLog(Long reservationId, String fromStatus, String toStatus,
                                           String reason, String source) {
        ReservationStatusLog log = new ReservationStatusLog();
        log.reservationId = reservationId;
        log.fromStatus = fromStatus;
        log.toStatus = toStatus;
        log.operatorId = SecurityContext.currentUserId();
        log.reason = reason;
        log.source = source;
        log.createTime = LocalDateTime.now();
        statusLogMapper.insert(log);
        return log;
    }

    private int reservationAdvanceDays() {
        return Math.max(0, sysConfigService.getInt("reservation.advance_days", 7));
    }

    private int reservationLockSeconds() {
        return Math.max(1, sysConfigService.getInt("reservation.lock_seconds", DEFAULT_RESERVATION_LOCK_SECONDS));
    }

    private boolean releaseReservationLockAfterTransaction(String lockKey, String lockToken) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                releaseReservationLock(lockKey, lockToken);
            }
        });
        return true;
    }

    private void releaseReservationLock(String lockKey, String lockToken) {
        redisTemplate.execute(RELEASE_RESERVATION_LOCK_SCRIPT, List.of(lockKey), lockToken);
    }

    private record TimeRange(LocalDateTime startTime, LocalDateTime endTime) {
    }
}
