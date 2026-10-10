package com.example.venue.promotion.service;

import com.example.venue.common.BusinessException;
import com.example.venue.promotion.entity.CouponStatusLog;
import com.example.venue.promotion.entity.CouponTemplate;
import com.example.venue.promotion.entity.UserCoupon;
import com.example.venue.promotion.mapper.CouponStatusLogMapper;
import com.example.venue.promotion.mapper.CouponTemplateMapper;
import com.example.venue.promotion.mapper.UserCouponMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

@Service
public class CouponRewardIssuanceService {
    private final CouponTemplateMapper templateMapper;
    private final UserCouponMapper couponMapper;
    private final CouponStatusLogMapper statusLogMapper;
    private final ObjectMapper objectMapper;
    private final CouponRewardIssuanceFaultInjector faultInjector;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CouponRewardIssuanceService(CouponTemplateMapper templateMapper, UserCouponMapper couponMapper,
                                       CouponStatusLogMapper statusLogMapper, ObjectMapper objectMapper,
                                       CouponRewardIssuanceFaultInjector faultInjector) {
        this(templateMapper, couponMapper, statusLogMapper, objectMapper, faultInjector, Clock.systemDefaultZone());
    }

    public CouponRewardIssuanceService(CouponTemplateMapper templateMapper, UserCouponMapper couponMapper,
                                       CouponStatusLogMapper statusLogMapper, ObjectMapper objectMapper,
                                       CouponRewardIssuanceFaultInjector faultInjector, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        this.templateMapper = templateMapper;
        this.couponMapper = couponMapper;
        this.statusLogMapper = statusLogMapper;
        this.objectMapper = objectMapper;
        this.faultInjector = faultInjector;
    }

    @Transactional
    public IssueResult issue(Long userId, Long templateId, Long redemptionId, Integer validityDays) {
        if (userId == null || templateId == null || redemptionId == null
            || userId <= 0 || templateId <= 0 || redemptionId <= 0
            || (validityDays != null && validityDays <= 0)) {
            throw BusinessException.badRequest("兑换优惠券发放参数不完整");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        CouponTemplate template = templateMapper.selectByIdForUpdate(templateId);
        if (template == null || !"ACTIVE".equals(template.status)
            || after(template.claimStartTime, now) || before(template.claimEndTime, now)
            || before(template.validEndTime, now)) {
            return IssueResult.rejected("兑换优惠券模板不可发放");
        }
        // Only normalize the generated persisted start; template-window checks retain full clock precision.
        LocalDateTime validStartTime = later(now.withNano(0), template.validStartTime);
        Integer days = validityDays == null ? template.validityDays : validityDays;
        LocalDateTime policyEnd = days == null ? template.validEndTime : now.plusDays(days);
        LocalDateTime validEndTime = earlier(policyEnd, template.validEndTime);
        if (validEndTime != null && !validEndTime.isAfter(validStartTime)) {
            return IssueResult.rejected("兑换优惠券有效期不可用");
        }
        String metadataJson = json(Map.of("redemptionId", redemptionId));
        int perUserLimit = template.perUserLimit == null ? 1 : template.perUserLimit;
        if (templateMapper.incrementIssuedIfAvailableForUser(templateId, userId, perUserLimit) == 0) {
            return IssueResult.rejected("兑换优惠券发放上限已达到");
        }
        faultInjector.afterInventoryReserved(templateId, userId, redemptionId);
        UserCoupon coupon = new UserCoupon();
        coupon.templateId = templateId;
        coupon.userId = userId;
        coupon.status = "CLAIMED";
        coupon.claimTime = now;
        coupon.validStartTime = validStartTime;
        coupon.validEndTime = validEndTime;
        coupon.discountAmount = new BigDecimal("0.00");
        coupon.sourceType = "MEMBER_REWARD";
        coupon.metadataJson = metadataJson;
        coupon.createTime = now;
        coupon.updateTime = now;
        coupon.deleted = 0;
        couponMapper.insert(coupon);
        writeLog(coupon, null, "CLAIMED", "MEMBER_REWARD_ISSUED", "积分兑换发放优惠券", userId);
        return IssueResult.issued(coupon);
    }

    public void validateCatalogTemplate(Long templateId) {
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null || Integer.valueOf(1).equals(template.deleted)) {
            throw BusinessException.notFound("兑换优惠券模板不存在");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void voidClaimed(Long couponId, Long redemptionId, Long expectedUserId,
                            Long expectedTemplateId, Long operatorId, String reason) {
        UserCoupon coupon = couponMapper.selectByIdForUpdate(couponId);
        if (coupon == null || redemptionId == null || expectedUserId == null || expectedTemplateId == null
            || !"MEMBER_REWARD".equals(coupon.sourceType)
            || !Objects.equals(redemptionId(coupon), redemptionId)
            || !Objects.equals(coupon.userId, expectedUserId)
            || !Objects.equals(coupon.templateId, expectedTemplateId)) {
            throw BusinessException.conflict("优惠券不属于该积分兑换");
        }
        if (!"CLAIMED".equals(coupon.status)) {
            throw BusinessException.conflict("只有 CLAIMED 兑换券可补偿作废");
        }
        if (couponMapper.voidClaimedRewardCoupon(couponId) != 1) {
            throw BusinessException.conflict("兑换券状态已变化");
        }
        writeLog(coupon, "CLAIMED", "VOIDED", "MEMBER_REWARD_COMPENSATED", reason, operatorId);
    }

    private Long redemptionId(UserCoupon coupon) {
        try {
            return objectMapper.readTree(coupon.metadataJson).path("redemptionId").longValue();
        } catch (Exception exception) {
            return null;
        }
    }

    private void writeLog(UserCoupon coupon, String from, String to, String code, String text, Long operatorId) {
        CouponStatusLog log = new CouponStatusLog();
        log.userCouponId = coupon.id;
        log.fromStatus = from;
        log.toStatus = to;
        log.reasonCode = code;
        log.reasonText = text;
        log.operatorId = operatorId;
        log.sourceType = "MEMBER_REWARD";
        log.metadataJson = coupon.metadataJson;
        log.createTime = LocalDateTime.now();
        statusLogMapper.insert(log);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw BusinessException.badRequest("兑换优惠券元数据无法序列化");
        }
    }

    private boolean after(LocalDateTime value, LocalDateTime now) { return value != null && value.isAfter(now); }
    private boolean before(LocalDateTime value, LocalDateTime now) { return value != null && value.isBefore(now); }
    private LocalDateTime later(LocalDateTime a, LocalDateTime b) { return b != null && b.isAfter(a) ? b : a; }
    private LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    public record IssueResult(UserCoupon coupon, String failureMessage) {
        public static IssueResult issued(UserCoupon coupon) {
            return new IssueResult(coupon, null);
        }

        public static IssueResult rejected(String message) {
            return new IssueResult(null, message);
        }

        public boolean issued() {
            return coupon != null;
        }
    }
}
