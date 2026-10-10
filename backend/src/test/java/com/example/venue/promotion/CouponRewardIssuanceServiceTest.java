package com.example.venue.promotion;

import com.example.venue.common.BusinessException;
import com.example.venue.promotion.entity.CouponStatusLog;
import com.example.venue.promotion.entity.CouponTemplate;
import com.example.venue.promotion.entity.UserCoupon;
import com.example.venue.promotion.mapper.CouponStatusLogMapper;
import com.example.venue.promotion.mapper.CouponTemplateMapper;
import com.example.venue.promotion.mapper.UserCouponMapper;
import com.example.venue.promotion.service.CouponRewardIssuanceService;
import com.example.venue.promotion.service.CouponRewardIssuanceFaultInjector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponRewardIssuanceServiceTest {
    @Mock private CouponTemplateMapper templateMapper;
    @Mock private UserCouponMapper couponMapper;
    @Mock private CouponStatusLogMapper statusLogMapper;
    @Mock private CouponRewardIssuanceFaultInjector faultInjector;
    private CouponRewardIssuanceService service;

    @BeforeEach
    void setUp() {
        service = new CouponRewardIssuanceService(
            templateMapper, couponMapper, statusLogMapper, new ObjectMapper(), faultInjector);
    }

    @Test
    void issueUsesDatabaseGuardAndCreatesClaimedRewardCoupon() {
        CouponTemplate template = template();
        when(templateMapper.selectByIdForUpdate(5L)).thenReturn(template);
        when(templateMapper.incrementIssuedIfAvailableForUser(5L, 7L, 1)).thenReturn(1);
        when(couponMapper.insert(any(UserCoupon.class))).thenAnswer(invocation -> {
            UserCoupon coupon = invocation.getArgument(0);
            coupon.id = 70L;
            return 1;
        });

        CouponRewardIssuanceService.IssueResult result = service.issue(7L, 5L, 88L, 30);
        UserCoupon coupon = result.coupon();

        assertThat(result.issued()).isTrue();
        assertThat(coupon.id).isEqualTo(70L);
        assertThat(coupon.status).isEqualTo("CLAIMED");
        assertThat(coupon.sourceType).isEqualTo("MEMBER_REWARD");
        assertThat(coupon.validEndTime).isAfter(coupon.validStartTime);
        // MySQL datetime rounds fractional seconds; immediate validity must never round into the future.
        assertThat(coupon.validStartTime.getNano()).isZero();
        assertThat(coupon.metadataJson).contains("redemptionId").contains("88");
        verify(statusLogMapper).insert(any(CouponStatusLog.class));
    }

    @Test
    void expiredClaimWindowStillUsesFullClockPrecision() {
        CouponTemplate template = template();
        LocalDateTime now = LocalDateTime.now().withNano(800_000_000);
        template.claimEndTime = now.withNano(500_000_000);
        when(templateMapper.selectByIdForUpdate(5L)).thenReturn(template);
        var zone = java.time.ZoneId.systemDefault();
        var clock = java.time.Clock.fixed(now.atZone(zone).toInstant(), zone);
        service = new CouponRewardIssuanceService(templateMapper, couponMapper, statusLogMapper,
            new ObjectMapper(), faultInjector, clock);
        assertThat(service.issue(7L, 5L, 88L, 30).issued()).isFalse();
        verify(couponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    void issueGuardFailureDoesNotCreateCoupon() {
        CouponTemplate template = template();
        when(templateMapper.selectByIdForUpdate(5L)).thenReturn(template);
        when(templateMapper.incrementIssuedIfAvailableForUser(5L, 7L, 1)).thenReturn(0);

        CouponRewardIssuanceService.IssueResult result = service.issue(7L, 5L, 88L, 30);

        assertThat(result.issued()).isFalse();
        assertThat(result.failureMessage()).contains("发放上限");
        verify(couponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    void invalidValidityPolicyIsRejectedBeforeInventoryGuard() {
        CouponTemplate template = template();
        template.validStartTime = LocalDateTime.now().plusDays(2);
        template.validEndTime = LocalDateTime.now().plusDays(1);
        when(templateMapper.selectByIdForUpdate(5L)).thenReturn(template);

        CouponRewardIssuanceService.IssueResult result = service.issue(7L, 5L, 88L, 30);

        assertThat(result.issued()).isFalse();
        assertThat(result.failureMessage()).contains("有效期不可用");

        verify(templateMapper, never()).incrementIssuedIfAvailableForUser(any(), any(), any(Integer.class));
        verify(couponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    void voidRewardCouponOnlyAcceptsClaimedCouponFromSameRedemption() {
        UserCoupon coupon = new UserCoupon();
        coupon.id = 70L;
        coupon.userId = 7L;
        coupon.templateId = 5L;
        coupon.status = "CLAIMED";
        coupon.sourceType = "MEMBER_REWARD";
        coupon.metadataJson = "{\"redemptionId\":88}";
        when(couponMapper.selectByIdForUpdate(70L)).thenReturn(coupon);
        when(couponMapper.voidClaimedRewardCoupon(70L)).thenReturn(1);

        service.voidClaimed(70L, 88L, 7L, 5L, 9L, "人工补偿");

        ArgumentCaptor<CouponStatusLog> captor = ArgumentCaptor.forClass(CouponStatusLog.class);
        verify(statusLogMapper).insert(captor.capture());
        assertThat(captor.getValue().toStatus).isEqualTo("VOIDED");

        coupon.status = "USED";
        assertThatThrownBy(() -> service.voidClaimed(70L, 88L, 7L, 5L, 9L, "重复补偿"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CLAIMED");
    }

    @Test
    void voidRewardCouponRejectsClaimedCouponWithWrongSourceOrRedemptionMetadataBeforeWrites() {
        UserCoupon coupon = new UserCoupon();
        coupon.id = 70L;
        coupon.status = "CLAIMED";
        coupon.sourceType = "ADMIN";
        coupon.metadataJson = "{\"redemptionId\":88}";
        when(couponMapper.selectByIdForUpdate(70L)).thenReturn(coupon);

        assertThatThrownBy(() -> service.voidClaimed(70L, 88L, 7L, 5L, 9L, "来源错误"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不属于");

        coupon.sourceType = "MEMBER_REWARD";
        coupon.metadataJson = "{\"redemptionId\":99}";
        assertThatThrownBy(() -> service.voidClaimed(70L, 88L, 7L, 5L, 9L, "元数据错误"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不属于");

        verify(couponMapper, never()).voidClaimedRewardCoupon(any());
        verify(statusLogMapper, never()).insert(any(CouponStatusLog.class));
    }

    @Test
    void voidRewardCouponRejectsUserOrTemplateMismatchBeforeWrites() {
        UserCoupon coupon = new UserCoupon();
        coupon.id = 70L;
        coupon.userId = 7L;
        coupon.templateId = 5L;
        coupon.status = "CLAIMED";
        coupon.sourceType = "MEMBER_REWARD";
        coupon.metadataJson = "{\"redemptionId\":88}";
        when(couponMapper.selectByIdForUpdate(70L)).thenReturn(coupon);

        assertThatThrownBy(() -> service.voidClaimed(70L, 88L, 8L, 5L, 9L, "用户错配"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不属于");
        assertThatThrownBy(() -> service.voidClaimed(70L, 88L, 7L, 6L, 9L, "模板错配"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不属于");

        verify(couponMapper, never()).voidClaimedRewardCoupon(any());
        verify(statusLogMapper, never()).insert(any(CouponStatusLog.class));
    }

    private CouponTemplate template() {
        CouponTemplate template = new CouponTemplate();
        template.id = 5L;
        template.status = "ACTIVE";
        template.deleted = 0;
        template.totalQuantity = 10;
        template.issuedQuantity = 0;
        template.perUserLimit = 1;
        template.validityDays = 60;
        template.validStartTime = LocalDateTime.now().minusDays(1);
        return template;
    }
}
