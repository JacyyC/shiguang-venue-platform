package com.example.venue.member.service;

import com.example.venue.common.BusinessException;
import com.example.venue.member.entity.MemberAccount;
import com.example.venue.member.entity.MemberAccountLog;
import com.example.venue.member.entity.MemberRewardCatalogItem;
import com.example.venue.member.entity.MemberRewardCatalogVersion;
import com.example.venue.member.entity.MemberRewardRedemption;
import com.example.venue.member.mapper.MemberAccountMapper;
import com.example.venue.member.mapper.MemberRewardCatalogItemMapper;
import com.example.venue.member.mapper.MemberRewardCatalogVersionMapper;
import com.example.venue.member.mapper.MemberRewardRedemptionMapper;
import com.example.venue.promotion.entity.UserCoupon;
import com.example.venue.promotion.service.CouponRewardIssuanceService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class MemberRewardExecutionService {
    private final MemberRewardRedemptionMapper redemptionMapper;
    private final MemberRewardCatalogItemMapper itemMapper;
    private final MemberRewardCatalogVersionMapper versionMapper;
    private final MemberAccountMapper accountMapper;
    private final MemberService memberService;
    private final MemberPointsService pointsService;
    private final CouponRewardIssuanceService couponService;
    private final MemberRewardEventService eventService;
    private final MemberRewardExecutionFaultInjector faultInjector;
    private final ApplicationEventPublisher eventPublisher;

    public MemberRewardExecutionService(MemberRewardRedemptionMapper redemptionMapper,
                                        MemberRewardCatalogItemMapper itemMapper,
                                        MemberRewardCatalogVersionMapper versionMapper,
                                        MemberAccountMapper accountMapper, MemberService memberService,
                                        MemberPointsService pointsService,
                                        CouponRewardIssuanceService couponService,
                                        MemberRewardEventService eventService,
                                        MemberRewardExecutionFaultInjector faultInjector,
                                        ApplicationEventPublisher eventPublisher) {
        this.redemptionMapper = redemptionMapper;
        this.itemMapper = itemMapper;
        this.versionMapper = versionMapper;
        this.accountMapper = accountMapper;
        this.memberService = memberService;
        this.pointsService = pointsService;
        this.couponService = couponService;
        this.eventService = eventService;
        this.faultInjector = faultInjector;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public MemberRewardRedemption execute(Long redemptionId) {
        MemberRewardRedemption redemption = redemptionMapper.selectByIdForUpdate(redemptionId);
        if (redemption == null) throw BusinessException.notFound("积分兑换不存在");
        if (!"PENDING".equals(redemption.status)) return redemption;
        String from = redemption.status;
        MemberRewardCatalogItem item = itemMapper.selectByIdForUpdate(redemption.catalogItemId);
        MemberRewardCatalogVersion version = versionMapper.selectByIdForUpdate(redemption.catalogVersionId);
        LocalDateTime now = LocalDateTime.now();
        if (item == null || !"ACTIVE".equals(item.status)) return reject(redemption, from, "CATALOG_INACTIVE", "兑换目录已停用");
        if (version == null || !"PUBLISHED".equals(version.status)
            || !redemption.catalogItemId.equals(version.catalogItemId)) {
            return reject(redemption, from, "VERSION_UNAVAILABLE", "兑换版本不可用");
        }
        if ((version.effectiveStartTime != null && version.effectiveStartTime.isAfter(now))
            || (version.effectiveEndTime != null && !version.effectiveEndTime.isAfter(now))) {
            return reject(redemption, from, "VERSION_OUTSIDE_WINDOW", "兑换版本不在生效期");
        }
        memberService.account(redemption.userId);
        MemberAccount account = accountMapper.findByUserIdForUpdate(redemption.userId);
        if (account == null || !"ACTIVE".equals(account.status)) return reject(redemption, from, "ACCOUNT_INACTIVE", "会员账户不可用");
        if (account.growthValue == null || account.growthValue < redemption.minimumGrowthThresholdSnapshot) {
            return reject(redemption, from, "LEVEL_NOT_ELIGIBLE", "会员等级未达到兑换门槛");
        }
        if (account.pointsBalance == null || account.pointsBalance < redemption.pointsCost) {
            return reject(redemption, from, "INSUFFICIENT_POINTS", "可用积分不足");
        }
        CouponRewardIssuanceService.IssueResult issueResult = couponService.issue(
            redemption.userId, redemption.couponTemplateId, redemption.id, redemption.validityDaysSnapshot);
        if (!issueResult.issued()) {
            return reject(redemption, from, "COUPON_UNAVAILABLE", issueResult.failureMessage());
        }
        UserCoupon coupon = issueResult.coupon();
        faultInjector.afterCouponIssued(redemption.id);
        MemberAccountLog debit = pointsService.appendLocked(account, "REWARD_REDEEM:" + redemption.id,
            "MEMBER_REWARD_REDEEMED", "R4_REWARD_V1", -redemption.pointsCost, 0,
            BigDecimal.ZERO, null, "MEMBER_REWARD", "REWARD_REDEEMED", "积分兑换扣减");
        faultInjector.afterPointsDebited(redemption.id);
        redemption.accountId = account.id;
        redemption.userCouponId = coupon.id;
        redemption.pointsDebitLogId = debit.id;
        redemption.status = "SUCCEEDED";
        redemption.completedAt = now;
        redemption.failureCode = null;
        redemption.failureMessage = null;
        redemption.updateTime = now;
        redemptionMapper.updateById(redemption);
        eventService.redemption(redemption, "REDEMPTION_SUCCEEDED", "REDEMPTION_SUCCEEDED:" + redemption.id,
            from, "SUCCEEDED", "SUCCESS", redemption.userId, "USER", "REWARD_REDEEMED", "积分兑换成功");
        eventPublisher.publishEvent(new MemberRewardSucceededEvent(redemption.id, redemption.userId,
            redemption.itemNameSnapshot));
        return redemption;
    }

    @Transactional
    public MemberRewardRedemption compensate(Long redemptionId, Long operatorId, String reason) {
        MemberRewardRedemption redemption = redemptionMapper.selectByIdForUpdate(redemptionId);
        if (redemption == null) throw BusinessException.notFound("积分兑换不存在");
        if ("COMPENSATED".equals(redemption.status)) return redemption;
        if (!"SUCCEEDED".equals(redemption.status) || redemption.userCouponId == null) {
            throw BusinessException.conflict("只有成功且未补偿的兑换可处理");
        }
        MemberAccount account = accountMapper.findByUserIdForUpdate(redemption.userId);
        if (account == null) throw BusinessException.conflict("会员账户不存在");
        couponService.voidClaimed(redemption.userCouponId, redemption.id, redemption.userId,
            redemption.couponTemplateId, operatorId, reason);
        MemberAccountLog credit = pointsService.appendLocked(account, "REWARD_COMPENSATE:" + redemption.id,
            "MEMBER_REWARD_COMPENSATED", "R4_REWARD_V1", redemption.pointsCost, 0,
            BigDecimal.ZERO, null, "MEMBER_REWARD", "REWARD_COMPENSATED", reason);
        redemption.pointsCompensationLogId = credit.id;
        redemption.status = "COMPENSATED";
        redemption.compensatedAt = LocalDateTime.now();
        redemption.reasonCode = "AUTHORIZED_COMPENSATION";
        redemption.reasonText = reason;
        redemption.updateTime = redemption.compensatedAt;
        redemptionMapper.updateById(redemption);
        eventService.redemption(redemption, "REDEMPTION_COMPENSATED", "REDEMPTION_COMPENSATED:" + redemption.id,
            "SUCCEEDED", "COMPENSATED", "SUCCESS", operatorId, "ADMIN",
            "AUTHORIZED_COMPENSATION", reason);
        return redemption;
    }

    private MemberRewardRedemption reject(MemberRewardRedemption redemption, String from, String code, String message) {
        redemption.status = "REJECTED";
        redemption.failureCode = code;
        redemption.failureMessage = message;
        redemption.completedAt = LocalDateTime.now();
        redemption.updateTime = redemption.completedAt;
        redemptionMapper.updateById(redemption);
        eventService.redemption(redemption, "REDEMPTION_REJECTED", "REDEMPTION_REJECTED:" + redemption.id,
            from, "REJECTED", "REJECTED", redemption.userId, "USER", code, message);
        return redemption;
    }
}
