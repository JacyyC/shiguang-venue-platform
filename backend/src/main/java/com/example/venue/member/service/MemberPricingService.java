package com.example.venue.member.service;

import com.example.venue.member.dto.MemberAccountResponse;
import com.example.venue.member.dto.MemberLevelResponse;
import com.example.venue.member.dto.MemberPricingContext;
import com.example.venue.member.entity.MemberLevel;
import com.example.venue.member.mapper.MemberLevelMapper;
import com.example.venue.pricing.AmountBreakdown;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class MemberPricingService {
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    private final MemberService memberService;
    private final MemberLevelMapper memberLevelMapper;

    public MemberPricingService(MemberService memberService, MemberLevelMapper memberLevelMapper) {
        this.memberService = memberService;
        this.memberLevelMapper = memberLevelMapper;
    }

    public MemberPricingContext previewForUser(Long userId, Long venueId, Long spaceId,
                                               LocalDateTime start, LocalDateTime end,
                                               BigDecimal unitPrice) {
        return preview(memberService.account(userId), start, end, unitPrice);
    }

    public MemberPricingContext previewForUserReadOnly(Long userId, Long venueId, Long spaceId,
                                                       LocalDateTime start, LocalDateTime end,
                                                       BigDecimal unitPrice) {
        return preview(memberService.accountSnapshotOrDefault(userId), start, end, unitPrice);
    }

    private MemberPricingContext preview(MemberAccountResponse account,
                                         LocalDateTime start, LocalDateTime end,
                                         BigDecimal unitPrice) {
        MemberLevel pricingLevel = memberLevelMapper.findActiveLevelForGrowth(account.growthValue == null ? 0 : account.growthValue);
        BigDecimal originalAmount = originalAmount(unitPrice, start, end);
        BigDecimal discountRate = pricingLevel == null || pricingLevel.discountRate == null ? BigDecimal.ONE : pricingLevel.discountRate;
        BigDecimal payableAmount = originalAmount.multiply(discountRate).setScale(2, RoundingMode.HALF_UP);
        if (payableAmount.compareTo(ZERO_AMOUNT) < 0) {
            payableAmount = ZERO_AMOUNT;
        }
        BigDecimal memberDiscountAmount = originalAmount.subtract(payableAmount).setScale(2, RoundingMode.HALF_UP);
        if (memberDiscountAmount.compareTo(ZERO_AMOUNT) < 0) {
            memberDiscountAmount = ZERO_AMOUNT;
        }
        MemberPricingContext context = new MemberPricingContext();
        context.amountBreakdown = new AmountBreakdown(
            originalAmount,
            memberDiscountAmount,
            ZERO_AMOUNT,
            ZERO_AMOUNT,
            payableAmount,
            ZERO_AMOUNT,
            ZERO_AMOUNT,
            ZERO_AMOUNT
        );
        context.memberLevel = pricingLevel == null ? currentLevel(account) : toLevelResponse(pricingLevel);
        context.memberAccount = account;
        return context;
    }

    private MemberLevelResponse currentLevel(MemberAccountResponse account) {
        MemberLevelResponse level = new MemberLevelResponse();
        level.levelCode = account.levelCode;
        level.levelName = account.levelName;
        level.discountRate = account.discountRate;
        level.benefitText = account.benefitText;
        return level;
    }

    private MemberLevelResponse toLevelResponse(MemberLevel level) {
        MemberLevelResponse response = new MemberLevelResponse();
        response.id = level.id;
        response.levelCode = level.levelCode;
        response.levelName = level.levelName;
        response.growthThreshold = level.growthThreshold;
        response.discountRate = level.discountRate;
        response.benefitText = level.benefitText;
        response.ruleVersion = level.ruleVersion;
        response.status = level.status;
        return response;
    }

    private BigDecimal originalAmount(BigDecimal unitPrice, LocalDateTime start, LocalDateTime end) {
        if (unitPrice == null || start == null || end == null || !start.isBefore(end)) {
            return ZERO_AMOUNT;
        }
        long minutes = Duration.between(start, end).toMinutes();
        BigDecimal hours = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        return unitPrice.multiply(hours).setScale(2, RoundingMode.HALF_UP);
    }
}
