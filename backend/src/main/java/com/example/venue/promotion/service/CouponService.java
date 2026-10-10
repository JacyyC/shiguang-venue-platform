package com.example.venue.promotion.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.promotion.dto.CouponPricingView;
import com.example.venue.promotion.dto.CouponTemplateResponse;
import com.example.venue.promotion.dto.UserCouponResponse;
import com.example.venue.promotion.entity.CouponStatusLog;
import com.example.venue.promotion.entity.CouponTemplate;
import com.example.venue.promotion.entity.UserCoupon;
import com.example.venue.promotion.mapper.CouponStatusLogMapper;
import com.example.venue.promotion.mapper.CouponTemplateMapper;
import com.example.venue.promotion.mapper.UserCouponMapper;
import com.example.venue.security.SecurityContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CouponService {
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");
    private static final String ACTIVE = "ACTIVE";
    private static final String CLAIMED = "CLAIMED";
    private static final String LOCKED = "LOCKED";
    private static final String RELEASED = "RELEASED";
    private static final String USED = "USED";
    private static final String REFUNDED = "REFUNDED";
    private static final String PRICING_SNAPSHOT_VERSION = "B3_PROMOTION_PRICING_V1";

    private final CouponTemplateMapper couponTemplateMapper;
    private final UserCouponMapper userCouponMapper;
    private final CouponStatusLogMapper couponStatusLogMapper;
    private final BusinessEventService businessEventService;
    private final NoticeService noticeService;
    private final ObjectMapper objectMapper;

    public CouponService(CouponTemplateMapper couponTemplateMapper, UserCouponMapper userCouponMapper,
                         CouponStatusLogMapper couponStatusLogMapper, BusinessEventService businessEventService,
                         NoticeService noticeService, ObjectMapper objectMapper) {
        this.couponTemplateMapper = couponTemplateMapper;
        this.userCouponMapper = userCouponMapper;
        this.couponStatusLogMapper = couponStatusLogMapper;
        this.businessEventService = businessEventService;
        this.noticeService = noticeService;
        this.objectMapper = objectMapper;
    }

    public List<CouponTemplateResponse> claimable(Long venueId, Long spaceId) {
        LocalDateTime now = LocalDateTime.now();
        List<CouponTemplate> templates = couponTemplateMapper.findClaimable(venueId, spaceId, now);
        if (templates == null || templates.isEmpty()) {
            return List.of();
        }
        return templates
            .stream()
            .map(this::toTemplateResponse)
            .toList();
    }

    public PageResult<UserCouponResponse> myCoupons(PageQuery query, String status) {
        Long userId = requireCurrentUser();
        PageQuery safeQuery = query == null ? new PageQuery() : query;
        QueryWrapper<UserCoupon> wrapper = new QueryWrapper<UserCoupon>()
            .eq("user_id", userId)
            .eq("deleted", 0);
        String normalizedStatus = normalizeStatus(status);
        if (normalizedStatus != null) {
            wrapper.eq("status", normalizedStatus);
        }
        Page<UserCoupon> page = userCouponMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("claim_time").orderByDesc("id"));
        List<UserCoupon> records = page.getRecords();
        Set<Long> templateIds = records.stream()
            .map(coupon -> coupon.templateId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, CouponTemplate> templateMap = templateIds.isEmpty()
            ? Map.of()
            : couponTemplateMapper.selectBatchIds(templateIds).stream()
                .collect(Collectors.toMap(template -> template.id, Function.identity(), (a, b) -> a));
        return PageResult.of(records.stream()
                .map(coupon -> toUserCouponResponse(coupon, templateMap.get(coupon.templateId)))
                .toList(),
            page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional
    public UserCouponResponse claim(Long templateId) {
        Long userId = requireCurrentUser();
        // 压测（30 并发抢同一模板）发现「模板行更新 + user_coupon/通知插入」存在 MySQL 死锁：
        // 先 FOR UPDATE 锁定模板行，使所有领取事务在同一锁点串行，消除锁序交叉；
        // 下方 incrementIssuedIfAvailableForUser 原子自增仍是超发终审，语义不变。
        CouponTemplate locked = couponTemplateMapper.selectByIdForUpdate(templateId);
        if (locked == null || Integer.valueOf(1).equals(locked.deleted)) {
            throw BusinessException.notFound("优惠券模板不存在");
        }
        CouponTemplate template = requireClaimableTemplate(templateId, LocalDateTime.now());
        Integer perUserLimit = nonNull(template.perUserLimit, 1);
        long owned = userCouponMapper.countUserCouponsForTemplate(templateId, userId);
        if (owned >= perUserLimit) {
            throw BusinessException.conflict("已达到每人限领数量");
        }
        if (couponTemplateMapper.incrementIssuedIfAvailableForUser(templateId, userId, perUserLimit) == 0) {
            long guardedOwned = userCouponMapper.countUserCouponsForTemplate(templateId, userId);
            if (guardedOwned >= perUserLimit) {
                throw BusinessException.conflict("已达到每人限领数量");
            }
            throw BusinessException.conflict("优惠券已领完");
        }
        UserCoupon coupon = new UserCoupon();
        coupon.templateId = template.id;
        coupon.userId = userId;
        coupon.status = CLAIMED;
        coupon.claimTime = LocalDateTime.now();
        coupon.validStartTime = template.validStartTime;
        coupon.validEndTime = template.validEndTime;
        coupon.sourceType = "USER";
        coupon.discountAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        coupon.createTime = coupon.claimTime;
        coupon.updateTime = coupon.claimTime;
        coupon.deleted = 0;
        userCouponMapper.insert(coupon);
        writeStatusLog(coupon, null, CLAIMED, "USER_CLAIM", "用户领取优惠券", "USER");
        recordCouponEvent(BusinessEventType.COUPON_CLAIMED, coupon, template,
            BusinessSourceType.USER, null, null, "COUPON_CLAIMED:" + coupon.id, BigDecimal.ZERO);
        noticeService.createMessage(userId, "优惠券领取成功",
            "您已成功领取优惠券「" + template.templateName + "」，" + couponDiscountDescription(template) + "，请在有效期内使用。",
            "COUPON", coupon.id);
        return toUserCouponResponse(coupon, template);
    }

    public CouponPricingView couponForPricing(Long userId, Long userCouponId, BigDecimal amountAfterMemberAndActivity,
                                              Long venueId, Long spaceId, LocalDateTime start, LocalDateTime end) {
        if (userCouponId == null) {
            return new CouponPricingView();
        }
        // AUD-P2-05：放宽定价校验，预约 owner=券所有者即可，不要求当前操作人=券所有者。
        // 调用方负责上下文权限校验：用户场景下 userId 取自 SecurityContext.currentUserId()；
        // 改期审核场景下 ReservationService 已通过 venueAccessService.requireVenueManageAccess
        // 校验当前操作人是该场馆管理员，且 userId 取自 reservation.userId（即券所有者）。
        // 此处仍保留 coupon.userId == userId 校验，防止传入不属于 userId 的券 ID。
        UserCoupon coupon = userCouponMapper.selectById(userCouponId);
        if (coupon == null || Integer.valueOf(1).equals(coupon.deleted)) {
            return unavailable(userCouponId, null, null, "优惠券不存在");
        }
        if (!Objects.equals(coupon.userId, userId)) {
            throw BusinessException.forbidden("只能使用自己的优惠券");
        }
        CouponTemplate template = couponTemplateMapper.selectById(coupon.templateId);
        CouponPricingView view = basePricingView(coupon, template);
        // AUD-P2-05：LOCKED 券对该预约视为可用——券已锁在预约上，改期审核时正常计算折扣。
        // 其他非 CLAIMED 状态（USED/REFUNDED/RELEASED）仍视为不可用。
        if (!CLAIMED.equals(coupon.status) && !LOCKED.equals(coupon.status)) {
            view.unavailableReason = "优惠券不可用";
            return view;
        }
        String couponValidityReason = couponValidityReason(coupon, start, end);
        if (couponValidityReason != null) {
            view.unavailableReason = couponValidityReason;
            return view;
        }
        if (template == null || Integer.valueOf(1).equals(template.deleted)) {
            view.unavailableReason = "优惠券模板不存在";
            return view;
        }
        String templateReason = templatePricingUnavailableReason(template, venueId, spaceId, start, end);
        if (templateReason != null) {
            view.unavailableReason = templateReason;
            return view;
        }
        BigDecimal amount = normalizeAmount(amountAfterMemberAndActivity);
        BigDecimal threshold = normalizeAmount(template.thresholdAmount);
        if (amount.compareTo(threshold) < 0) {
            view.unavailableReason = "未达到优惠券使用门槛";
            return view;
        }
        view.discountAmount = normalizeAmount(applyMaxDiscount(template, discountFor(template, amount)));
        view.unavailableReason = null;
        return view;
    }

    @Transactional
    public void lockForReservation(Long userId, Long userCouponId, Long reservationId, String lockToken) {
        if (userCouponId == null) {
            return;
        }
        UserCoupon coupon = requireCoupon(userCouponId);
        if (!Objects.equals(coupon.userId, userId)) {
            throw BusinessException.forbidden("只能使用自己的优惠券");
        }
        CouponTemplate template = couponTemplateMapper.selectById(coupon.templateId);
        int updated = userCouponMapper.lockIfClaimed(userCouponId, userId, reservationId, lockToken);
        if (updated == 0) {
            UserCoupon current = userCouponMapper.selectById(userCouponId);
            if (current != null
                && LOCKED.equals(current.status)
                && Objects.equals(current.reservationId, reservationId)
                && Objects.equals(current.lockToken, lockToken)) {
                return;
            }
            throw BusinessException.conflict("优惠券无法锁定");
        }
        String fromStatus = coupon.status;
        coupon.status = LOCKED;
        coupon.reservationId = reservationId;
        coupon.lockToken = lockToken;
        writeStatusLog(coupon, fromStatus, LOCKED, "RESERVATION_LOCK", "预约锁定优惠券", "SYSTEM");
        recordCouponEvent(BusinessEventType.COUPON_LOCKED, coupon, template,
            BusinessSourceType.SYSTEM, "RESERVATION", reservationId,
            "COUPON_LOCKED:" + userCouponId + ":" + reservationId, BigDecimal.ZERO);
    }

    @Transactional
    public void releaseForReservation(Long reservationId, String reason) {
        if (reservationId == null) {
            return;
        }
        UserCoupon coupon = findCouponByReservationId(reservationId);
        if (coupon == null) {
            return;
        }
        if (CLAIMED.equals(coupon.status) || RELEASED.equals(coupon.status)) {
            return;
        }
        CouponTemplate template = couponTemplateMapper.selectById(coupon.templateId);
        int updated = userCouponMapper.releaseIfLocked(coupon.id, reason);
        if (updated == 0) {
            UserCoupon current = userCouponMapper.selectById(coupon.id);
            if (current != null && (CLAIMED.equals(current.status) || RELEASED.equals(current.status))) {
                return;
            }
            throw BusinessException.conflict("优惠券无法释放");
        }
        String fromStatus = coupon.status;
        coupon.status = CLAIMED;
        writeStatusLog(coupon, fromStatus, CLAIMED, "RESERVATION_RELEASE", reason, "SYSTEM");
        recordCouponEvent(BusinessEventType.COUPON_RELEASED, coupon, template,
            BusinessSourceType.SYSTEM, "RESERVATION", reservationId,
            "COUPON_RELEASED:" + coupon.id + ":" + reservationId, BigDecimal.ZERO);
        coupon.reservationId = null;
        coupon.lockToken = null;
        coupon.lockedAt = null;
    }

    @Transactional
    public void markUsedForOrder(Long orderId, Long userCouponId, BigDecimal discountAmount) {
        if (userCouponId == null) {
            return;
        }
        UserCoupon coupon = requireCoupon(userCouponId);
        CouponTemplate template = couponTemplateMapper.selectById(coupon.templateId);
        BigDecimal normalizedDiscount = normalizeAmount(discountAmount);
        int updated = userCouponMapper.useIfLocked(userCouponId, orderId, normalizedDiscount);
        if (updated == 0) {
            UserCoupon current = userCouponMapper.selectById(userCouponId);
            if (current != null
                && USED.equals(current.status)
                && Objects.equals(current.orderId, orderId)
                && sameAmount(current.discountAmount, normalizedDiscount)) {
                return;
            }
            throw BusinessException.conflict("优惠券无法核销");
        }
        couponTemplateMapper.incrementUsed(coupon.templateId);
        String fromStatus = coupon.status;
        coupon.status = USED;
        coupon.orderId = orderId;
        coupon.discountAmount = normalizedDiscount;
        writeStatusLog(coupon, fromStatus, USED, "ORDER_USED", "订单使用优惠券", "SYSTEM");
        recordCouponEvent(BusinessEventType.COUPON_USED, coupon, template,
            BusinessSourceType.SYSTEM, "ORDER", orderId,
            "COUPON_USED:" + userCouponId + ":" + orderId, normalizedDiscount);
    }

    @Transactional
    public void markRefundedForOrder(Long orderId, String reason) {
        if (orderId == null) {
            return;
        }
        UserCoupon coupon = findCouponByOrderId(orderId);
        if (coupon == null) {
            return;
        }
        if (!USED.equals(coupon.status)) {
            return;
        }
        CouponTemplate template = couponTemplateMapper.selectById(coupon.templateId);
        int updated = userCouponMapper.markRefundedIfUsed(coupon.id, reason);
        if (updated == 0) {
            UserCoupon current = userCouponMapper.selectById(coupon.id);
            if (current != null && REFUNDED.equals(current.status)) {
                return;
            }
            throw BusinessException.conflict("优惠券无法退款");
        }
        String fromStatus = coupon.status;
        coupon.status = REFUNDED;
        writeStatusLog(coupon, fromStatus, REFUNDED, "ORDER_REFUNDED", reason, "SYSTEM");
        recordCouponEvent(BusinessEventType.COUPON_REFUNDED, coupon, template,
            BusinessSourceType.SYSTEM, "ORDER", orderId,
            "COUPON_REFUNDED:" + coupon.id + ":" + orderId, normalizeAmount(coupon.discountAmount));
    }

    private CouponTemplate requireClaimableTemplate(Long templateId, LocalDateTime now) {
        if (templateId == null) {
            throw BusinessException.badRequest("优惠券模板ID不能为空");
        }
        CouponTemplate template = couponTemplateMapper.selectById(templateId);
        if (template == null || Integer.valueOf(1).equals(template.deleted)) {
            throw BusinessException.notFound("优惠券模板不存在");
        }
        if (!ACTIVE.equals(template.status)
            || isAfter(template.claimStartTime, now)
            || isBefore(template.claimEndTime, now)
            || isBefore(template.validEndTime, now)) {
            throw BusinessException.conflict("优惠券不可领取");
        }
        if (template.totalQuantity != null
            && template.totalQuantity > 0
            && nonNull(template.issuedQuantity, 0) >= template.totalQuantity) {
            throw BusinessException.conflict("优惠券已领完");
        }
        return template;
    }

    private UserCoupon requireCoupon(Long userCouponId) {
        UserCoupon coupon = userCouponMapper.selectById(userCouponId);
        if (coupon == null || Integer.valueOf(1).equals(coupon.deleted)) {
            throw BusinessException.notFound("优惠券不存在");
        }
        return coupon;
    }

    private UserCoupon findCouponByReservationId(Long reservationId) {
        return userCouponMapper.selectOne(new QueryWrapper<UserCoupon>()
            .eq("reservation_id", reservationId)
            .eq("deleted", 0)
            .orderByDesc("id")
            .last("LIMIT 1"));
    }

    private UserCoupon findCouponByOrderId(Long orderId) {
        return userCouponMapper.selectOne(new QueryWrapper<UserCoupon>()
            .eq("order_id", orderId)
            .eq("deleted", 0)
            .orderByDesc("id")
            .last("LIMIT 1"));
    }

    private Long requireCurrentUser() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        return userId;
    }

    private CouponTemplateResponse toTemplateResponse(CouponTemplate template) {
        CouponTemplateResponse response = new CouponTemplateResponse();
        response.id = template.id;
        response.templateCode = template.templateCode;
        response.templateName = template.templateName;
        response.couponType = template.couponType;
        response.discountValue = discountValue(template);
        response.thresholdAmount = normalizeAmount(template.thresholdAmount);
        response.maxDiscountAmount = normalizeNullableAmount(template.maxDiscountAmount);
        response.totalQuantity = template.totalQuantity;
        response.issuedQuantity = template.issuedQuantity;
        response.usedQuantity = template.usedQuantity;
        response.perUserLimit = template.perUserLimit;
        response.validStartTime = template.validStartTime;
        response.validEndTime = template.validEndTime;
        response.campaignId = template.campaignId;
        response.venueId = template.venueId;
        response.spaceId = template.spaceId;
        response.allowActivityStack = allowActivityStack(template);
        response.status = template.status;
        return response;
    }

    private UserCouponResponse toUserCouponResponse(UserCoupon coupon, CouponTemplate template) {
        UserCouponResponse response = new UserCouponResponse();
        response.id = coupon.id;
        response.templateId = coupon.templateId;
        response.status = coupon.status;
        response.claimTime = coupon.claimTime;
        response.validStartTime = coupon.validStartTime;
        response.validEndTime = coupon.validEndTime;
        response.reservationId = coupon.reservationId;
        response.orderId = coupon.orderId;
        response.discountAmount = normalizeAmount(coupon.discountAmount);
        response.sourceType = coupon.sourceType;
        response.releasedAt = coupon.releasedAt;
        if (template != null) {
            response.templateCode = template.templateCode;
            response.templateName = template.templateName;
            response.couponType = template.couponType;
            response.discountValue = discountValue(template);
            response.thresholdAmount = normalizeAmount(template.thresholdAmount);
        }
        return response;
    }

    private CouponPricingView basePricingView(UserCoupon coupon, CouponTemplate template) {
        CouponPricingView view = new CouponPricingView();
        view.userCouponId = coupon == null ? null : coupon.id;
        view.templateId = coupon == null ? null : coupon.templateId;
        view.discountAmount = ZERO_AMOUNT;
        if (template != null) {
            view.templateId = template.id;
            view.templateCode = template.templateCode;
            view.templateName = template.templateName;
            view.couponType = template.couponType;
            view.discountValue = discountValue(template);
            view.thresholdAmount = normalizeAmount(template.thresholdAmount);
            view.allowActivityStack = allowActivityStack(template);
        }
        return view;
    }

    private CouponPricingView unavailable(Long userCouponId, UserCoupon coupon, CouponTemplate template, String reason) {
        CouponPricingView view = basePricingView(coupon, template);
        view.userCouponId = userCouponId;
        view.unavailableReason = reason;
        return view;
    }

    private String couponValidityReason(UserCoupon coupon, LocalDateTime start, LocalDateTime end) {
        LocalDateTime effectiveStart = start == null ? LocalDateTime.now() : start;
        LocalDateTime effectiveEnd = end == null ? effectiveStart : end;
        if (coupon.validStartTime != null && coupon.validStartTime.isAfter(effectiveStart)) {
            return "优惠券未到可用时间";
        }
        if (coupon.validEndTime != null
            && (coupon.validEndTime.isBefore(LocalDateTime.now()) || coupon.validEndTime.isBefore(effectiveEnd))) {
            return "优惠券已过期";
        }
        return null;
    }

    private String templatePricingUnavailableReason(CouponTemplate template, Long venueId, Long spaceId,
                                                    LocalDateTime start, LocalDateTime end) {
        LocalDateTime effectiveStart = start == null ? LocalDateTime.now() : start;
        LocalDateTime effectiveEnd = end == null ? effectiveStart : end;
        if (!ACTIVE.equals(template.status)) {
            return "优惠券模板不可用";
        }
        if (template.validStartTime != null && template.validStartTime.isAfter(effectiveStart)) {
            return "优惠券未到可用时间";
        }
        if (template.validEndTime != null && template.validEndTime.isBefore(effectiveEnd)) {
            return "优惠券已过期";
        }
        if (template.venueId != null && !Objects.equals(template.venueId, venueId)) {
            return "优惠券不适用于当前场馆";
        }
        if (template.spaceId != null && !Objects.equals(template.spaceId, spaceId)) {
            return "优惠券不适用于当前场地";
        }
        return null;
    }

    private BigDecimal discountFor(CouponTemplate template, BigDecimal amount) {
        BigDecimal discountValue = discountValue(template);
        if (discountValue == null) {
            return ZERO_AMOUNT;
        }
        String couponType = template.couponType == null ? "" : template.couponType.toUpperCase(Locale.ROOT);
        return switch (couponType) {
            case "CASH" -> discountValue.min(amount);
            case "PERCENT" -> amount.subtract(amount.multiply(discountValue)).max(BigDecimal.ZERO);
            case "FIXED" -> amount.subtract(discountValue).max(BigDecimal.ZERO);
            default -> BigDecimal.ZERO;
        };
    }

    private BigDecimal applyMaxDiscount(CouponTemplate template, BigDecimal discountAmount) {
        BigDecimal maxDiscount = normalizeNullableAmount(template.maxDiscountAmount);
        if (maxDiscount == null) {
            return discountAmount;
        }
        return discountAmount.min(maxDiscount);
    }

    private BigDecimal discountValue(CouponTemplate template) {
        if (template == null) {
            return null;
        }
        return template.discountValue;
    }

    private String couponDiscountDescription(CouponTemplate template) {
        if (template == null) {
            return "";
        }
        BigDecimal value = normalizeAmount(discountValue(template));
        String couponType = template.couponType == null ? "" : template.couponType.toUpperCase(Locale.ROOT);
        return switch (couponType) {
            case "CASH" -> "面值 " + value + " 元";
            case "PERCENT" -> "享 " + value.multiply(BigDecimal.TEN).stripTrailingZeros().toPlainString() + " 折";
            // A26-P3-12：FIXED 券的 discountValue 是券后一口价（用户最终支付金额），
            // 不是优惠金额；通知文案必须与计价语义一致，避免误报成"立减 X 元"。
            case "FIXED" -> "券后一口价 " + value + " 元";
            default -> "优惠 " + value + " 元";
        };
    }

    private void writeStatusLog(UserCoupon coupon, String fromStatus, String toStatus, String reasonCode,
                                String reasonText, String sourceType) {
        CouponStatusLog log = new CouponStatusLog();
        log.userCouponId = coupon.id;
        log.fromStatus = fromStatus;
        log.toStatus = toStatus;
        log.reasonCode = reasonCode;
        log.reasonText = reasonText;
        log.operatorId = currentUserIdOrNull();
        log.sourceType = sourceType;
        log.reservationId = coupon.reservationId;
        log.orderId = coupon.orderId;
        log.metadataJson = toJson(Map.of(
            "templateId", coupon.templateId,
            "userId", coupon.userId
        ));
        log.createTime = LocalDateTime.now();
        couponStatusLogMapper.insert(log);
    }

    private void recordCouponEvent(BusinessEventType eventType, UserCoupon coupon, CouponTemplate template,
                                   BusinessSourceType sourceType, String relatedEntityType, Long relatedEntityId,
                                   String idempotencyKey, BigDecimal discountAmount) {
        BusinessEventRecordRequest request = BusinessEventRecordRequest.of(
                eventType,
                BusinessDomain.COUPON,
                "USER_COUPON",
                coupon.id,
                sourceType,
                "SUCCESS")
            .idempotencyKey(idempotencyKey)
            .reasonCode(eventType.name())
            .reasonText(reasonText(eventType))
            .metadata("templateId", coupon.templateId)
            .metadata("templateCode", template == null ? null : template.templateCode)
            .metadata("campaignId", template == null ? null : template.campaignId)
            .metadata("userCouponId", coupon.id)
            .metadata("userId", coupon.userId)
            .metadata("reservationId", coupon.reservationId)
            .metadata("orderId", coupon.orderId)
            .metadata("discountAmount", normalizeAmount(discountAmount))
            .metadata("pricingSnapshotVersion", PRICING_SNAPSHOT_VERSION)
            .metadata("reason", reasonText(eventType));
        if (relatedEntityType != null && relatedEntityId != null) {
            request.relatedEntity(relatedEntityType, relatedEntityId);
        }
        businessEventService.record(request);
    }

    private String reasonText(BusinessEventType eventType) {
        return switch (eventType) {
            case COUPON_CLAIMED -> "用户领取优惠券";
            case COUPON_LOCKED -> "预约锁定优惠券";
            case COUPON_RELEASED -> "预约释放优惠券";
            case COUPON_USED -> "订单使用优惠券";
            case COUPON_REFUNDED -> "订单退款后优惠券保持不可复用终态";
            default -> eventType.name();
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw BusinessException.badRequest("优惠券状态日志元数据无法序列化");
        }
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        return amount == null ? ZERO_AMOUNT : amount.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeNullableAmount(BigDecimal amount) {
        return amount == null ? null : normalizeAmount(amount);
    }

    private boolean sameAmount(BigDecimal storedAmount, BigDecimal requestedAmount) {
        return normalizeAmount(storedAmount).compareTo(normalizeAmount(requestedAmount)) == 0;
    }

    private boolean allowActivityStack(CouponTemplate template) {
        return template != null && !Boolean.FALSE.equals(template.allowActivityStack);
    }

    private Integer nonNull(Integer value, Integer fallback) {
        return value == null ? fallback : value;
    }

    private boolean isBefore(LocalDateTime value, LocalDateTime other) {
        return value != null && value.isBefore(other);
    }

    private boolean isAfter(LocalDateTime value, LocalDateTime other) {
        return value != null && value.isAfter(other);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private Long currentUserIdOrNull() {
        CurrentUser user = SecurityContext.currentUser();
        return user == null ? null : user.id();
    }
}
