package com.example.venue.promotion;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.notice.dto.NoticeRequest;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.common.AuditLog;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.PageQuery;
import com.example.venue.promotion.controller.PromotionController;
import com.example.venue.promotion.dto.PromotionCampaignResponse;
import com.example.venue.promotion.dto.PromotionOverviewResponse;
import com.example.venue.promotion.dto.SaveCouponTemplateRequest;
import com.example.venue.promotion.dto.SavePriceRuleRequest;
import com.example.venue.promotion.dto.SavePromotionCampaignRequest;
import com.example.venue.promotion.entity.CouponTemplate;
import com.example.venue.promotion.entity.PriceRule;
import com.example.venue.promotion.entity.PromotionCampaign;
import com.example.venue.promotion.entity.UserCoupon;
import com.example.venue.promotion.mapper.CouponStatusLogMapper;
import com.example.venue.promotion.mapper.CouponStatusLogMapper;
import com.example.venue.promotion.mapper.CouponTemplateMapper;
import com.example.venue.promotion.mapper.PriceRuleMapper;
import com.example.venue.promotion.mapper.PromotionCampaignMapper;
import com.example.venue.promotion.mapper.UserCouponMapper;
import com.example.venue.promotion.service.PromotionAdminService;
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
import org.springframework.web.bind.annotation.PutMapping;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PromotionAdminServiceTest {
    @Mock
    private PromotionCampaignMapper campaignMapper;
    @Mock
    private CouponTemplateMapper couponTemplateMapper;
    @Mock
    private PriceRuleMapper priceRuleMapper;
    @Mock
    private UserCouponMapper userCouponMapper;
    @Mock
    private CouponStatusLogMapper couponStatusLogMapper;
    @Mock
    private BusinessEventService businessEventService;
    @Mock
    private NoticeService noticeService;

    private PromotionAdminService service;

    @BeforeEach
    void setUp() {
        service = new PromotionAdminService(campaignMapper, couponTemplateMapper, priceRuleMapper,
            userCouponMapper, couponStatusLogMapper, businessEventService, noticeService,
            new ObjectMapper().findAndRegisterModules());
        authenticate(new CurrentUser(3L, "promotion-admin", Set.of("SYSTEM_ADMIN"),
            Set.of("promotion:manage", "coupon:manage", "pricing:manage")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void saveCampaignValidatesTimeAndWritesActivityCreatedEvent() {
        SavePromotionCampaignRequest invalid = validCampaignRequest();
        invalid.endTime = invalid.startTime.minusMinutes(1);

        assertThatThrownBy(() -> service.saveCampaign(invalid))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("活动时间");

        when(campaignMapper.insert(any(PromotionCampaign.class))).thenAnswer(invocation -> {
            PromotionCampaign campaign = invocation.getArgument(0);
            campaign.id = 8L;
            return 1;
        });

        PromotionCampaignResponse campaign = service.saveCampaign(validCampaignRequest());

        assertThat(campaign.id).isEqualTo(8L);
        assertThat(campaign.targetMemberLevel).isEqualTo("GOLD");
        assertThat(campaign.maxDiscountAmount).isEqualByComparingTo("30.00");
        assertThat(campaign.allowCouponStack).isTrue();
        ArgumentCaptor<PromotionCampaign> campaignCaptor = ArgumentCaptor.forClass(PromotionCampaign.class);
        verify(campaignMapper).insert(campaignCaptor.capture());
        assertThat(campaignCaptor.getValue().memberLevelCode).isEqualTo("GOLD");
        assertThat(campaignCaptor.getValue().stackable).isEqualTo(1);
        verify(businessEventService).record(argThat(req ->
            req.eventType == BusinessEventType.ACTIVITY_CREATED
                && req.businessDomain == BusinessDomain.ACTIVITY
                && "PROMOTION_CAMPAIGN".equals(req.entityType)
                && req.entityId.equals(8L)
                && req.sourceType == BusinessSourceType.ADMIN));
    }

    @Test
    void updateCampaignStatusWritesActivityStatusChangedEvent() {
        PromotionCampaign campaign = campaign(8L);
        campaign.status = "ACTIVE";
        when(campaignMapper.selectById(8L)).thenReturn(campaign);
        when(campaignMapper.update(isNull(), any())).thenReturn(1);

        service.updateCampaignStatus(8L, "PAUSED");

        assertThat(campaign.status).isEqualTo("PAUSED");
        // A26-P2-07：状态切换走 UpdateWrapper 而非 updateById，避免覆盖并发累加的 budget_used_amount。
        verify(campaignMapper, never()).updateById(any(PromotionCampaign.class));
        ArgumentCaptor<UpdateWrapper<PromotionCampaign>> wrapperCaptor =
            ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(campaignMapper).update(isNull(), wrapperCaptor.capture());
        String sqlSet = wrapperCaptor.getValue().getSqlSet();
        assertThat(sqlSet).contains("status");
        assertThat(sqlSet).doesNotContain("budget_used_amount");
        verify(businessEventService).record(argThat(req ->
            req.eventType == BusinessEventType.ACTIVITY_STATUS_CHANGED
                && "ACTIVE".equals(req.fromStatus)
                && "PAUSED".equals(req.toStatus)
                && "ACTIVITY_STATUS_CHANGED:8:ACTIVE:PAUSED".equals(req.idempotencyKey)));
    }

    @Test
    void updateCampaignPreservesConcurrentlyIncrementedBudgetUsedAmount() {
        // A26-P2-07：管理员读取活动时 budget_used_amount=800，期间用户消费累加到 950；
        // 保存若整实体写回，会把 DB 中 950 覆盖回 800，后续还能再消耗 200，实际发放折扣 1150 超出预算。
        PromotionCampaign campaign = campaign(8L);
        campaign.budgetUsedAmount = new BigDecimal("800.00");
        campaign.status = "ACTIVE";
        when(campaignMapper.selectById(8L)).thenReturn(campaign);
        when(campaignMapper.update(isNull(), any())).thenReturn(1);

        SavePromotionCampaignRequest request = validCampaignRequest();
        request.budgetAmount = new BigDecimal("1000.00");
        service.updateCampaign(8L, request);

        verify(campaignMapper, never()).updateById(any(PromotionCampaign.class));
        ArgumentCaptor<UpdateWrapper<PromotionCampaign>> wrapperCaptor =
            ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(campaignMapper).update(isNull(), wrapperCaptor.capture());
        String sqlSet = wrapperCaptor.getValue().getSqlSet();
        // budget_amount 仍可由管理员调整；budget_used_amount 由 consumeBudgetIfAvailable/releaseBudgetIfAvailable 原子维护，不进 SET。
        assertThat(sqlSet).contains("budget_amount");
        assertThat(sqlSet).doesNotContain("budget_used_amount");
    }

    @Test
    void saveCampaignRejectsIllegalDiscountConfigBeforePersistence() {
        // F-18：管理 API 是金额权威。PERCENT=0 会被计价读成「整单免费」，
        // PERCENT>1 会被读成零优惠，未知类型会静默跳过活动——三者都必须在落库前拒绝。
        for (String value : new String[] { "0.00", "-0.01", "1.01", "1.50" }) {
            SavePromotionCampaignRequest request = validCampaignRequest();
            request.discountType = "PERCENT";
            request.discountValue = new BigDecimal(value);
            assertThatThrownBy(() -> service.saveCampaign(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PERCENT");
        }

        for (String type : new String[] { "CASH", "FIXED" }) {
            SavePromotionCampaignRequest zero = validCampaignRequest();
            zero.discountType = type;
            zero.discountValue = BigDecimal.ZERO;
            assertThatThrownBy(() -> service.saveCampaign(zero))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("活动优惠值不合法");
        }

        SavePromotionCampaignRequest missing = validCampaignRequest();
        missing.discountValue = null;
        assertThatThrownBy(() -> service.saveCampaign(missing))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("活动优惠值不能为空");

        SavePromotionCampaignRequest unknown = validCampaignRequest();
        unknown.discountType = "DISCOUNT_9";
        unknown.discountValue = new BigDecimal("20.00");
        assertThatThrownBy(() -> service.saveCampaign(unknown))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("活动优惠类型不支持");

        verify(campaignMapper, never()).insert(any(PromotionCampaign.class));
    }

    @Test
    void saveCampaignAcceptsLegalDiscountBoundaries() {
        when(campaignMapper.insert(any(PromotionCampaign.class))).thenAnswer(invocation -> {
            PromotionCampaign campaign = invocation.getArgument(0);
            campaign.id = 8L;
            return 1;
        });

        SavePromotionCampaignRequest fullOffEdge = validCampaignRequest();
        fullOffEdge.discountType = "PERCENT";
        fullOffEdge.discountValue = new BigDecimal("1.00");
        assertThat(service.saveCampaign(fullOffEdge).discountValue).isEqualByComparingTo("1.00");

        SavePromotionCampaignRequest smallest = validCampaignRequest();
        smallest.discountType = "PERCENT";
        smallest.discountValue = new BigDecimal("0.01");
        assertThat(service.saveCampaign(smallest).discountValue).isEqualByComparingTo("0.01");

        SavePromotionCampaignRequest fixed = validCampaignRequest();
        fixed.discountType = "FIXED";
        fixed.discountValue = new BigDecimal("0.01");
        assertThat(service.saveCampaign(fixed).discountType).isEqualTo("FIXED");
    }

    @Test
    void saveCampaignDefaultsPriorityAndPersistsExplicitPriority() {
        when(campaignMapper.insert(any(PromotionCampaign.class))).thenAnswer(invocation -> {
            PromotionCampaign campaign = invocation.getArgument(0);
            campaign.id = 8L;
            return 1;
        });
        ArgumentCaptor<PromotionCampaign> captor = ArgumentCaptor.forClass(PromotionCampaign.class);

        SavePromotionCampaignRequest omitted = validCampaignRequest();
        assertThat(service.saveCampaign(omitted).priority).isZero();

        SavePromotionCampaignRequest explicit = validCampaignRequest();
        explicit.priority = 40;
        PromotionCampaignResponse response = service.saveCampaign(explicit);
        verify(campaignMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).priority).isZero();
        assertThat(captor.getAllValues().get(1).priority).isEqualTo(40);
        assertThat(response.priority).isEqualTo(40);

        SavePromotionCampaignRequest negative = validCampaignRequest();
        negative.priority = -1;
        assertThatThrownBy(() -> service.saveCampaign(negative))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("活动优先级");
    }

    @Test
    void updateCampaignWritesPriorityAndKeepsStoredValueWhenOmitted() {
        PromotionCampaign campaign = campaign(8L);
        campaign.priority = 15;
        when(campaignMapper.selectById(8L)).thenReturn(campaign);
        when(campaignMapper.update(isNull(), any())).thenReturn(1);

        SavePromotionCampaignRequest omitted = validCampaignRequest();
        PromotionCampaignResponse kept = service.updateCampaign(8L, omitted);

        SavePromotionCampaignRequest explicit = validCampaignRequest();
        explicit.priority = 60;
        PromotionCampaignResponse overwritten = service.updateCampaign(8L, explicit);

        ArgumentCaptor<UpdateWrapper<PromotionCampaign>> wrapperCaptor =
            ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(campaignMapper, times(2)).update(isNull(), wrapperCaptor.capture());
        // F-21：priority 必须进 SET 才会写回；省略时绑定值仍是库内 15，显式时才换成 60。
        UpdateWrapper<PromotionCampaign> first = wrapperCaptor.getAllValues().get(0);
        UpdateWrapper<PromotionCampaign> second = wrapperCaptor.getAllValues().get(1);
        assertThat(first.getSqlSet()).contains("priority=");
        assertThat(first.getParamNameValuePairs().containsValue(15)).isTrue();
        assertThat(kept.priority).isEqualTo(15);
        assertThat(second.getSqlSet()).contains("priority=");
        assertThat(second.getParamNameValuePairs().containsValue(60)).isTrue();
        assertThat(overwritten.priority).isEqualTo(60);
    }

    @Test
    void updateCouponTemplatePreservesConcurrentlyIncrementedIssuedAndUsedQuantities() {
        // A26-P2-08：总量 100、已发 99 时管理员编辑，期间用户领取使已发到 100（售罄）；
        // 保存若整实体写回，会把 DB 中 100 覆盖回 99，条件再次成立继续放行，可超发。
        CouponTemplate template = couponTemplate(18L);
        template.issuedQuantity = 99;
        template.usedQuantity = 10;
        template.status = "ACTIVE";
        when(couponTemplateMapper.selectById(18L)).thenReturn(template);
        when(couponTemplateMapper.update(isNull(), any())).thenReturn(1);

        service.updateCouponTemplate(18L, validCouponTemplateRequest());

        verify(couponTemplateMapper, never()).updateById(any(CouponTemplate.class));
        ArgumentCaptor<UpdateWrapper<CouponTemplate>> wrapperCaptor =
            ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(couponTemplateMapper).update(isNull(), wrapperCaptor.capture());
        String sqlSet = wrapperCaptor.getValue().getSqlSet();
        // total_quantity 仍可由管理员调整；issued_quantity/used_quantity 由 incrementIssuedIfAvailable*/incrementUsed 原子维护，不进 SET。
        assertThat(sqlSet).contains("total_quantity");
        assertThat(sqlSet).doesNotContain("issued_quantity");
        assertThat(sqlSet).doesNotContain("used_quantity");
    }

    @Test
    void updateCouponTemplateRejectsTotalBelowIssuedButAllowsUnlimited() {
        // B4-P6：领取守卫是 (total_quantity <= 0 or issued_quantity < total_quantity)。
        // 把总量改到低于已发行会让模板此后永久无法再发（运营不可逆），必须显式拒绝。
        CouponTemplate template = couponTemplate(18L);
        template.issuedQuantity = 150;
        template.status = "ACTIVE";
        when(couponTemplateMapper.selectById(18L)).thenReturn(template);

        SaveCouponTemplateRequest tooSmall = validCouponTemplateRequest();
        tooSmall.totalQuantity = 120;
        assertThatThrownBy(() -> service.updateCouponTemplate(18L, tooSmall))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("发行总量不能小于已发行数量");
        verify(couponTemplateMapper, never()).update(isNull(), any());

        // 恰好等于已发行可以保存（售罄但存量有效）。
        SaveCouponTemplateRequest exactlyIssued = validCouponTemplateRequest();
        exactlyIssued.totalQuantity = 150;
        when(couponTemplateMapper.update(isNull(), any())).thenReturn(1);
        assertThat(service.updateCouponTemplate(18L, exactlyIssued)).isNotNull();

        // total_quantity <= 0 是既有「不限量」约定：下调到 0 只解除上限、不影响已发券，必须放行。
        SaveCouponTemplateRequest unlimited = validCouponTemplateRequest();
        unlimited.totalQuantity = 0;
        assertThat(service.updateCouponTemplate(18L, unlimited)).isNotNull();
    }

    @Test
    void couponTemplateResponseExposesCostOwnerForVerification() {
        // B4-P5：cost_owner 有列、有写入，但此前无任何读取出口，写入后不可见不可核。
        CouponTemplate template = couponTemplate(18L);
        template.issuedQuantity = 0;
        template.usedQuantity = 0;
        template.status = "ACTIVE";
        template.costOwner = "PLATFORM";
        when(couponTemplateMapper.selectById(18L)).thenReturn(template);
        when(couponTemplateMapper.update(isNull(), any())).thenReturn(1);

        assertThat(service.updateCouponTemplate(18L, validCouponTemplateRequest()).costOwner)
            .isEqualTo("PLATFORM");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void couponTemplateListAppliesOptionalStatusFilterOnlyWhenProvided() {
        // B4-P9：权益目录按 status:'ACTIVE' 拉「可选券模板」，此前该入参被服务端忽略，
        // 草稿/暂停/结束模板也会进入候选，可能在会员兑换时才失败。
        when(couponTemplateMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenAnswer(invocation -> {
            Page<CouponTemplate> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0);
            return page;
        });
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);

        service.couponTemplates(new PageQuery(), null);
        service.couponTemplates(new PageQuery(), "active");

        verify(couponTemplateMapper, org.mockito.Mockito.times(2)).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getAllValues().get(0).getSqlSegment()).doesNotContain("status =");
        assertThat(captor.getAllValues().get(1).getSqlSegment()).contains("status =");
        assertThat(captor.getAllValues().get(1).getParamNameValuePairs().values()).contains("ACTIVE");

        assertThatThrownBy(() -> service.couponTemplates(new PageQuery(), "ARCHIVED"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("状态不支持");
    }

    @Test
    void updateCouponTemplateStatusPreservesConcurrentlyIncrementedQuantities() {
        // A26-P2-08：ACTIVE↔PAUSED 切换同样不能整实体写回，否则会覆盖并发累加的 issued_quantity/used_quantity。
        CouponTemplate template = couponTemplate(18L);
        template.issuedQuantity = 100;
        template.usedQuantity = 20;
        template.status = "PAUSED";
        when(couponTemplateMapper.selectById(18L)).thenReturn(template);
        when(couponTemplateMapper.update(isNull(), any())).thenReturn(1);

        service.updateCouponTemplateStatus(18L, "ACTIVE");

        verify(couponTemplateMapper, never()).updateById(any(CouponTemplate.class));
        ArgumentCaptor<UpdateWrapper<CouponTemplate>> wrapperCaptor =
            ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(couponTemplateMapper).update(isNull(), wrapperCaptor.capture());
        String sqlSet = wrapperCaptor.getValue().getSqlSet();
        assertThat(sqlSet).contains("status");
        assertThat(sqlSet).doesNotContain("issued_quantity");
        assertThat(sqlSet).doesNotContain("used_quantity");
    }

    @Test
    void saveCouponTemplateRejectsInvalidQuantityAndDiscount() {
        SaveCouponTemplateRequest invalidQuantity = validCouponTemplateRequest();
        invalidQuantity.totalQuantity = -1;

        assertThatThrownBy(() -> service.saveCouponTemplate(invalidQuantity))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("优惠券数量");

        SaveCouponTemplateRequest invalidDiscount = validCouponTemplateRequest();
        invalidDiscount.discountValue = BigDecimal.ZERO;

        assertThatThrownBy(() -> service.saveCouponTemplate(invalidDiscount))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("优惠券折扣");

        when(couponTemplateMapper.insert(any(CouponTemplate.class))).thenAnswer(invocation -> {
            CouponTemplate template = invocation.getArgument(0);
            template.id = 18L;
            return 1;
        });

        var template = service.saveCouponTemplate(validCouponTemplateRequest());

        assertThat(template.id).isEqualTo(18L);
        assertThat(template.allowActivityStack).isTrue();
        assertThat(template.issuedQuantity).isZero();
        assertThat(template.usedQuantity).isZero();
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void saveCouponTemplatePublishesFixedCouponNoticeWithFixedPriceDescription() {
        // A26-P3-12：FIXED 券 discountValue 是券后一口价，上架通知文案必须是"券后一口价 X 元"，
        // 不能误用"立减 X 元"（200 元订单用 50 元 FIXED 券实际优惠 150 元，"立减 50 元"与计价语义相反）。
        SaveCouponTemplateRequest request = validCouponTemplateRequest();
        request.couponType = "FIXED";
        request.discountValue = new BigDecimal("50.00");
        when(couponTemplateMapper.insert(any(CouponTemplate.class))).thenAnswer(invocation -> {
            CouponTemplate template = invocation.getArgument(0);
            template.id = 18L;
            return 1;
        });

        service.saveCouponTemplate(request);

        ArgumentCaptor<NoticeRequest> noticeCaptor = ArgumentCaptor.forClass(NoticeRequest.class);
        verify(noticeService).publish(noticeCaptor.capture());
        String content = noticeCaptor.getValue().content;
        assertThat(content).contains("券后一口价 50.00 元");
        assertThat(content).doesNotContain("立减 50.00 元");
        assertThat(content).doesNotContain("立减");
    }

    @Test
    void saveCouponTemplatePublishesCashCouponNoticeWithFaceValueDescription() {
        // A26-P3-12：CASH 券文案保持"面值 X 元"原语义，确认 FIXED 文案修正未误改其他分支。
        SaveCouponTemplateRequest request = validCouponTemplateRequest();
        // validCouponTemplateRequest() 默认即 CASH + discountValue=20.00
        when(couponTemplateMapper.insert(any(CouponTemplate.class))).thenAnswer(invocation -> {
            CouponTemplate template = invocation.getArgument(0);
            template.id = 18L;
            return 1;
        });

        service.saveCouponTemplate(request);

        ArgumentCaptor<NoticeRequest> noticeCaptor = ArgumentCaptor.forClass(NoticeRequest.class);
        verify(noticeService).publish(noticeCaptor.capture());
        String content = noticeCaptor.getValue().content;
        assertThat(content).contains("面值 20.00 元");
        assertThat(content).doesNotContain("立减");
        assertThat(content).doesNotContain("券后一口价");
    }

    @Test
    void savePriceRuleRejectsInvalidTimeWindowAndWritesPriceRuleEvent() {
        SavePriceRuleRequest invalid = validPriceRuleRequest();
        invalid.endTime = invalid.startTime.minusMinutes(1);

        assertThatThrownBy(() -> service.savePriceRule(invalid))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("价格规则时间");

        SavePriceRuleRequest missingAdjustmentType = validPriceRuleRequest();
        missingAdjustmentType.adjustmentType = null;

        assertThatThrownBy(() -> service.savePriceRule(missingAdjustmentType))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("价格调整类型");

        when(priceRuleMapper.insert(any(PriceRule.class))).thenAnswer(invocation -> {
            PriceRule rule = invocation.getArgument(0);
            rule.id = 28L;
            return 1;
        });

        var rule = service.savePriceRule(validPriceRuleRequest());

        assertThat(rule.id).isEqualTo(28L);
        assertThat(rule.ruleType).isEqualTo("PEAK");
        assertThat(rule.dayOfWeekMask).isEqualTo("1,2,3,4,5");
        assertThat(rule.reasonText).isEqualTo("工作日高峰调价");
        ArgumentCaptor<PriceRule> ruleCaptor = ArgumentCaptor.forClass(PriceRule.class);
        verify(priceRuleMapper).insert(ruleCaptor.capture());
        PriceRule insertedRule = ruleCaptor.getValue();
        assertThat(insertedRule.ruleType).isEqualTo("PEAK");
        assertThat(insertedRule.startTime).isEqualTo(rule.startTime);
        assertThat(insertedRule.endTime).isEqualTo(rule.endTime);
        assertThat(insertedRule.dayOfWeekMask).isEqualTo("1,2,3,4,5");
        assertThat(insertedRule.reasonText).isEqualTo("工作日高峰调价");
        assertThat(insertedRule.metadataJson == null ? "" : insertedRule.metadataJson)
            .doesNotContain("ruleType", "dayOfWeekMask", "reasonText");
        verify(businessEventService).record(argThat(req ->
            req.eventType == BusinessEventType.PRICE_RULE_CHANGED
                && req.businessDomain == BusinessDomain.PRICING
                && "PRICE_RULE".equals(req.entityType)
                && req.entityId.equals(28L)
                && "PRICE_RULE_CHANGED:28".equals(req.idempotencyKey)));
    }

    @Test
    void savePriceRuleRejectsInvalidPercentMultiplierBeforePersistence() {
        SavePriceRuleRequest invalid = validPriceRuleRequest();
        invalid.adjustmentType = "PERCENT";
        invalid.adjustmentValue = new BigDecimal("1.01");

        assertThatThrownBy(() -> service.savePriceRule(invalid))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PERCENT");
        verify(priceRuleMapper, never()).insert(any(PriceRule.class));
    }

    @Test
    void priceRulesReadsB3PhysicalFieldsInsteadOfMetadataJson() {
        PriceRule physicalRule = priceRule(29L);
        physicalRule.ruleType = "PEAK";
        physicalRule.startTime = LocalDateTime.of(2026, 7, 10, 0, 0);
        physicalRule.endTime = LocalDateTime.of(2026, 7, 31, 23, 59);
        physicalRule.dayOfWeekMask = "1,2,3,4,5";
        physicalRule.reasonText = "物理字段原因";
        physicalRule.metadataJson = """
            {"ruleType":"METADATA","dayOfWeekMask":"7","reasonText":"metadata reason"}
            """;
        Page<PriceRule> page = new Page<>(1, 10);
        page.setRecords(List.of(physicalRule));
        page.setTotal(1);
        when(priceRuleMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        var response = service.priceRules(new PageQuery()).getRecords().get(0);

        assertThat(response.ruleType).isEqualTo("PEAK");
        assertThat(response.startTime).isEqualTo(LocalDateTime.of(2026, 7, 10, 0, 0));
        assertThat(response.endTime).isEqualTo(LocalDateTime.of(2026, 7, 31, 23, 59));
        assertThat(response.dayOfWeekMask).isEqualTo("1,2,3,4,5");
        assertThat(response.reasonText).isEqualTo("物理字段原因");
    }

    @Test
    void overviewAggregatesCampaignTemplatesRulesAndCouponStatus() throws NoSuchMethodException {
        when(campaignMapper.selectCount(any(QueryWrapper.class))).thenReturn(2L);
        when(couponTemplateMapper.selectCount(any(QueryWrapper.class))).thenReturn(3L);
        when(priceRuleMapper.selectCount(any(QueryWrapper.class))).thenReturn(4L);
        // 退款历史按 ORDER_REFUNDED 状态日志统计，避免把同一张终态券重复计数。
        when(userCouponMapper.selectCount(any(QueryWrapper.class))).thenReturn(5L, 6L, 7L);
        when(couponStatusLogMapper.selectCount(any(QueryWrapper.class))).thenReturn(8L);
        // 总优惠金额仅统计仍处于 USED 状态的券折扣金额。
        when(userCouponMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            coupon(70L, "USED", "20.00")));

        PromotionOverviewResponse overview = service.overview();

        assertThat(overview.activeCampaigns).isEqualTo(2);
        assertThat(overview.activeCouponTemplates).isEqualTo(3);
        assertThat(overview.activePriceRules).isEqualTo(4);
        assertThat(overview.claimedCoupons).isEqualTo(5);
        assertThat(overview.lockedCoupons).isEqualTo(6);
        assertThat(overview.usedCoupons).isEqualTo(7);
        assertThat(overview.refundedCoupons).isEqualTo(8);
        assertThat(overview.totalCouponDiscountAmount).isEqualByComparingTo("20.00");
        assertControllerContract();
    }

    private void assertControllerContract() throws NoSuchMethodException {
        assertMapping(PromotionController.class.getDeclaredMethod("campaigns", PageQuery.class),
            GetMapping.class, "/api/admin/promotions/campaigns", "hasAuthority('promotion:manage')", false);
        assertMapping(PromotionController.class.getDeclaredMethod("createCampaign", SavePromotionCampaignRequest.class),
            PostMapping.class, "/api/admin/promotions/campaigns", "hasAuthority('promotion:manage')", true);
        assertMapping(PromotionController.class.getDeclaredMethod("updateCampaign", Long.class, SavePromotionCampaignRequest.class),
            PutMapping.class, "/api/admin/promotions/campaigns/{id}", "hasAuthority('promotion:manage')", true);
        assertMapping(PromotionController.class.getDeclaredMethod("updateCampaignStatus", Long.class, String.class),
            PutMapping.class, "/api/admin/promotions/campaigns/{id}/status", "hasAuthority('promotion:manage')", true);

        assertMapping(PromotionController.class.getDeclaredMethod("couponTemplates", PageQuery.class, String.class),
            GetMapping.class, "/api/admin/promotions/coupon-templates", "hasAuthority('coupon:manage')", false);
        assertMapping(PromotionController.class.getDeclaredMethod("createCouponTemplate", SaveCouponTemplateRequest.class),
            PostMapping.class, "/api/admin/promotions/coupon-templates", "hasAuthority('coupon:manage')", true);
        assertMapping(PromotionController.class.getDeclaredMethod("updateCouponTemplate", Long.class, SaveCouponTemplateRequest.class),
            PutMapping.class, "/api/admin/promotions/coupon-templates/{id}", "hasAuthority('coupon:manage')", true);
        assertMapping(PromotionController.class.getDeclaredMethod("updateCouponTemplateStatus", Long.class, String.class),
            PutMapping.class, "/api/admin/promotions/coupon-templates/{id}/status", "hasAuthority('coupon:manage')", true);
        assertMapping(PromotionController.class.getDeclaredMethod("couponRedemptions", PageQuery.class, String.class),
            GetMapping.class, "/api/admin/promotions/coupon-redemptions", "hasAuthority('coupon:manage')", false);

        assertMapping(PromotionController.class.getDeclaredMethod("priceRules", PageQuery.class),
            GetMapping.class, "/api/admin/promotions/price-rules", "hasAuthority('pricing:manage')", false);
        assertMapping(PromotionController.class.getDeclaredMethod("createPriceRule", SavePriceRuleRequest.class),
            PostMapping.class, "/api/admin/promotions/price-rules", "hasAuthority('pricing:manage')", true);
        assertMapping(PromotionController.class.getDeclaredMethod("updatePriceRule", Long.class, SavePriceRuleRequest.class),
            PutMapping.class, "/api/admin/promotions/price-rules/{id}", "hasAuthority('pricing:manage')", true);
        assertMapping(PromotionController.class.getDeclaredMethod("updatePriceRuleStatus", Long.class, String.class),
            PutMapping.class, "/api/admin/promotions/price-rules/{id}/status", "hasAuthority('pricing:manage')", true);

        assertMapping(PromotionController.class.getDeclaredMethod("overview"),
            GetMapping.class, "/api/admin/promotions/overview", "hasAuthority('promotion:manage')", false);
    }

    private void assertMapping(java.lang.reflect.Method method, Class<?> mappingType, String path,
                               String permission, boolean auditRequired) {
        if (mappingType == GetMapping.class) {
            assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
        } else if (mappingType == PostMapping.class) {
            assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
        } else if (mappingType == PutMapping.class) {
            assertThat(method.getAnnotation(PutMapping.class).value()).containsExactly(path);
        }
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(permission);
        if (auditRequired) {
            assertThat(method.getAnnotation(AuditLog.class)).isNotNull();
        }
    }

    private void authenticate(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private SavePromotionCampaignRequest validCampaignRequest() {
        SavePromotionCampaignRequest request = new SavePromotionCampaignRequest();
        request.campaignCode = "SUMMER_CAMP";
        request.campaignName = "暑期活动";
        request.campaignType = "DISCOUNT";
        request.venueId = 1L;
        request.spaceId = 10L;
        request.targetMemberLevel = "GOLD";
        request.startTime = LocalDateTime.now().plusDays(1);
        request.endTime = request.startTime.plusDays(7);
        request.budgetAmount = new BigDecimal("5000.00");
        request.discountType = "CASH";
        request.discountValue = new BigDecimal("20.00");
        request.maxDiscountAmount = new BigDecimal("30.00");
        request.allowCouponStack = true;
        request.status = "active";
        return request;
    }

    private SaveCouponTemplateRequest validCouponTemplateRequest() {
        SaveCouponTemplateRequest request = new SaveCouponTemplateRequest();
        request.templateCode = "B3_CASH_20";
        request.templateName = "满百减二十";
        request.couponType = "CASH";
        request.discountValue = new BigDecimal("20.00");
        request.thresholdAmount = new BigDecimal("100.00");
        request.maxDiscountAmount = new BigDecimal("20.00");
        request.totalQuantity = 100;
        request.perUserLimit = 1;
        request.validStartTime = LocalDateTime.now().plusDays(1);
        request.validEndTime = request.validStartTime.plusDays(30);
        request.campaignId = 8L;
        request.venueId = 1L;
        request.spaceId = 10L;
        request.allowActivityStack = true;
        request.costOwner = "PLATFORM";
        request.status = "ACTIVE";
        return request;
    }

    private SavePriceRuleRequest validPriceRuleRequest() {
        SavePriceRuleRequest request = new SavePriceRuleRequest();
        request.ruleCode = "PEAK_WEEKDAY";
        request.ruleName = "工作日高峰价";
        request.ruleType = "PEAK";
        request.venueId = 1L;
        request.spaceId = 10L;
        request.memberLevelCode = "GOLD";
        request.startTime = LocalDateTime.now().plusDays(1);
        request.endTime = request.startTime.plusDays(30);
        request.dayOfWeekMask = "1,2,3,4,5";
        request.startClock = LocalTime.of(18, 0);
        request.endClock = LocalTime.of(21, 0);
        request.adjustmentType = "DELTA";
        request.adjustmentValue = new BigDecimal("20.00");
        request.priority = 10;
        request.status = "ACTIVE";
        request.reasonText = "工作日高峰调价";
        return request;
    }

    private PromotionCampaign campaign(Long id) {
        PromotionCampaign campaign = new PromotionCampaign();
        campaign.id = id;
        campaign.campaignCode = "SUMMER_CAMP";
        campaign.campaignName = "暑期活动";
        campaign.campaignType = "DISCOUNT";
        campaign.memberLevelCode = "GOLD";
        campaign.discountType = "CASH";
        campaign.discountValue = new BigDecimal("20.00");
        campaign.stackable = 1;
        campaign.startTime = LocalDateTime.now().plusDays(1);
        campaign.endTime = campaign.startTime.plusDays(7);
        campaign.status = "ACTIVE";
        campaign.deleted = 0;
        return campaign;
    }

    private CouponTemplate couponTemplate(Long id) {
        CouponTemplate template = new CouponTemplate();
        template.id = id;
        template.templateCode = "B3_CASH_20";
        template.templateName = "满百减二十";
        template.couponType = "CASH";
        template.discountValue = new BigDecimal("20.00");
        template.thresholdAmount = new BigDecimal("100.00");
        template.maxDiscountAmount = new BigDecimal("20.00");
        template.totalQuantity = 100;
        template.perUserLimit = 1;
        template.validStartTime = LocalDateTime.now().plusDays(1);
        template.validEndTime = template.validStartTime.plusDays(30);
        template.campaignId = 8L;
        template.venueId = 1L;
        template.spaceId = 10L;
        template.allowActivityStack = Boolean.TRUE;
        template.costOwner = "PLATFORM";
        template.status = "ACTIVE";
        template.deleted = 0;
        return template;
    }

    private PriceRule priceRule(Long id) {
        PriceRule rule = new PriceRule();
        rule.id = id;
        rule.ruleCode = "PEAK_WEEKDAY";
        rule.ruleName = "工作日高峰价";
        rule.venueId = 1L;
        rule.spaceId = 10L;
        rule.memberLevelCode = "GOLD";
        rule.startClock = LocalTime.of(18, 0);
        rule.endClock = LocalTime.of(21, 0);
        rule.adjustmentType = "DELTA";
        rule.adjustmentValue = new BigDecimal("20.00");
        rule.priority = 10;
        rule.status = "ACTIVE";
        rule.deleted = 0;
        return rule;
    }

    private UserCoupon coupon(Long id, String status, String discountAmount) {
        UserCoupon coupon = new UserCoupon();
        coupon.id = id;
        coupon.templateId = 18L;
        coupon.userId = 7L;
        coupon.status = status;
        coupon.discountAmount = new BigDecimal(discountAmount);
        coupon.deleted = 0;
        return coupon;
    }
}
