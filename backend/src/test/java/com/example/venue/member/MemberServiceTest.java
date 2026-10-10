package com.example.venue.member;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.common.PageQuery;
import com.example.venue.member.dto.MemberAccountResponse;
import com.example.venue.member.dto.MemberPricingContext;
import com.example.venue.member.dto.MemberOverviewResponse;
import com.example.venue.member.dto.MemberLevelResponse;
import com.example.venue.member.dto.SaveMemberLevelRequest;
import com.example.venue.member.entity.MemberAccount;
import com.example.venue.member.entity.MemberAccountLog;
import com.example.venue.member.entity.MemberLevel;
import com.example.venue.member.entity.MemberProfileTag;
import com.example.venue.member.mapper.MemberAccountLogMapper;
import com.example.venue.member.mapper.MemberAccountMapper;
import com.example.venue.member.mapper.MemberLevelMapper;
import com.example.venue.member.mapper.MemberProfileTagMapper;
import com.example.venue.member.service.MemberPricingService;
import com.example.venue.member.service.MemberPointsService;
import com.example.venue.member.service.MemberService;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {
    @Mock
    private MemberLevelMapper memberLevelMapper;
    @Mock
    private MemberAccountMapper memberAccountMapper;
    @Mock
    private MemberAccountLogMapper memberAccountLogMapper;
    @Mock
    private MemberProfileTagMapper memberProfileTagMapper;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private BusinessEventService businessEventService;
    @Mock
    private EntityExistenceService entityExistenceService;
    private MemberPointsService memberPointsService;

    private MemberService service;

    @BeforeEach
    void setUp() {
        memberPointsService = new MemberPointsService(memberAccountMapper, memberAccountLogMapper);
        service = new MemberService(memberLevelMapper, memberAccountMapper, memberAccountLogMapper,
            memberProfileTagMapper, userMapper, businessEventService, new ObjectMapper().findAndRegisterModules(),
            entityExistenceService, memberPointsService);
        authenticate(new CurrentUser(7L, "tester", Set.of("USER"), Set.of("member:me")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void meCreatesDefaultBasicAccountWhenAbsent() {
        when(memberAccountMapper.findByUserId(7L)).thenReturn(null);
        when(memberLevelMapper.findActiveLevels()).thenReturn(List.of(basicLevel(), silverLevel(), goldLevel()));
        when(userMapper.selectById(7L)).thenReturn(user(7L, "tester", "测试用户"));
        when(memberAccountMapper.insert(any(MemberAccount.class))).thenAnswer(invocation -> {
            MemberAccount account = invocation.getArgument(0);
            account.id = 70L;
            return 1;
        });

        MemberAccountResponse response = service.currentAccount();

        assertThat(response.userId).isEqualTo(7L);
        assertThat(response.username).isEqualTo("tester");
        assertThat(response.nickname).isEqualTo("测试用户");
        assertThat(response.memberAccountId).isEqualTo(70L);
        assertThat(response.levelCode).isEqualTo("BASIC");
        assertThat(response.discountRate).isEqualByComparingTo("1.0000");
        assertThat(response.profileTags).contains("NEW_USER");
        assertThat(response.nextLevel.levelCode).isEqualTo("SILVER");
        assertThat(response.nextLevelGrowthGap).isEqualTo(1000);

        ArgumentCaptor<MemberAccount> accountCaptor = ArgumentCaptor.forClass(MemberAccount.class);
        verify(memberAccountMapper).insert(accountCaptor.capture());
        assertThat(accountCaptor.getValue().levelCode).isEqualTo("BASIC");
        assertThat(accountCaptor.getValue().growthValue).isZero();
        assertThat(accountCaptor.getValue().pointsBalance).isZero();
    }

    @Test
    void accountSnapshotOrDefaultReturnsDefaultLevelWithoutCreatingAccountWhenAbsent() {
        when(memberAccountMapper.findByUserId(7L)).thenReturn(null);
        when(memberLevelMapper.findActiveLevels()).thenReturn(List.of(basicLevel(), silverLevel(), goldLevel()));
        when(userMapper.selectById(7L)).thenReturn(user(7L, "tester", "测试用户"));

        MemberAccountResponse response = service.accountSnapshotOrDefault(7L);

        assertThat(response.userId).isEqualTo(7L);
        assertThat(response.memberAccountId).isNull();
        assertThat(response.levelCode).isEqualTo("BASIC");
        assertThat(response.levelName).isEqualTo("普通会员");
        assertThat(response.discountRate).isEqualByComparingTo("1.0000");
        assertThat(response.profileTags).isEmpty();
        assertThat(response.nextLevel.levelCode).isEqualTo("SILVER");
        assertThat(response.nextLevelGrowthGap).isEqualTo(1000);
        verify(memberAccountMapper, never()).insert(any(MemberAccount.class));
        verify(memberProfileTagMapper, never()).deactivateTagsByUserId(any());
        verify(memberProfileTagMapper, never()).findActiveByUserId(any());
    }

    @Test
    void saveLevelRejectsDuplicateCodeAndInvalidDiscount() {
        assertThatThrownBy(() -> service.saveLevel(requestWithDiscount("1.2")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("折扣率");

        verify(memberLevelMapper, never()).insert(any(MemberLevel.class));

        SaveMemberLevelRequest request = requestWithDiscount("0.9");
        request.levelCode = " gold ";
        MemberLevel existing = goldLevel();
        when(memberLevelMapper.findByCode("GOLD")).thenReturn(existing);

        assertThatThrownBy(() -> service.saveLevel(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("等级编码");
    }

    @Test
    void updateLevelKeepsInactiveStatusWhenRequestStatusIsOmitted() {
        MemberLevel level = silverLevel();
        level.status = "INACTIVE";
        when(memberLevelMapper.selectById(2L)).thenReturn(level);
        when(memberLevelMapper.findByCode("SILVER")).thenReturn(level);
        SaveMemberLevelRequest request = requestWithDiscount("0.9200");
        request.levelName = "银卡会员升级版";
        request.status = null;

        MemberLevelResponse response = service.updateLevel(2L, request);

        assertThat(response.status).isEqualTo("INACTIVE");
        ArgumentCaptor<MemberLevel> levelCaptor = ArgumentCaptor.forClass(MemberLevel.class);
        verify(memberLevelMapper).updateById(levelCaptor.capture());
        assertThat(levelCaptor.getValue().status).isEqualTo("INACTIVE");
        assertThat(levelCaptor.getValue().levelName).isEqualTo("银卡会员升级版");
        assertThat(levelCaptor.getValue().discountRate).isEqualByComparingTo("0.9200");
    }

    @Test
    void saveLevelUsesDefaultRuleVersionWhenRequestOmitsIt() {
        SaveMemberLevelRequest request = requestWithDiscount("0.9500");
        request.ruleVersion = "  ";
        when(memberLevelMapper.findByCode("SILVER")).thenReturn(null);

        MemberLevelResponse response = service.saveLevel(request);

        ArgumentCaptor<MemberLevel> captor = ArgumentCaptor.forClass(MemberLevel.class);
        verify(memberLevelMapper).insert(captor.capture());
        assertThat(captor.getValue().ruleVersion).isEqualTo("B2_MEMBER_V1");
        assertThat(response.ruleVersion).isEqualTo("B2_MEMBER_V1");
    }

    @Test
    void updateLevelKeepsStoredRuleVersionWhenRequestOmitsIt() {
        MemberLevel level = silverLevel();
        level.ruleVersion = "B2_MEMBER_V2";
        when(memberLevelMapper.selectById(2L)).thenReturn(level);
        when(memberLevelMapper.findByCode("SILVER")).thenReturn(level);
        SaveMemberLevelRequest request = requestWithDiscount("0.9200");
        request.levelName = "银卡会员升级版";
        request.ruleVersion = null;

        MemberLevelResponse response = service.updateLevel(2L, request);

        ArgumentCaptor<MemberLevel> captor = ArgumentCaptor.forClass(MemberLevel.class);
        verify(memberLevelMapper).updateById(captor.capture());
        // F-17：省略版本是「不改追溯字段」，不能被默认值覆盖
        assertThat(captor.getValue().ruleVersion).isEqualTo("B2_MEMBER_V2");
        assertThat(response.ruleVersion).isEqualTo("B2_MEMBER_V2");
        assertThat(captor.getValue().levelName).isEqualTo("银卡会员升级版");
    }

    @Test
    void updateLevelWritesExplicitRuleVersionFromRequest() {
        MemberLevel level = silverLevel();
        level.ruleVersion = "B2_MEMBER_V2";
        when(memberLevelMapper.selectById(2L)).thenReturn(level);
        when(memberLevelMapper.findByCode("SILVER")).thenReturn(level);
        SaveMemberLevelRequest request = requestWithDiscount("0.9200");
        request.ruleVersion = " B2_MEMBER_V3 ";

        MemberLevelResponse response = service.updateLevel(2L, request);

        ArgumentCaptor<MemberLevel> captor = ArgumentCaptor.forClass(MemberLevel.class);
        verify(memberLevelMapper).updateById(captor.capture());
        assertThat(captor.getValue().ruleVersion).isEqualTo("B2_MEMBER_V3");
        assertThat(response.ruleVersion).isEqualTo("B2_MEMBER_V3");
    }

    @Test
    void recordPaidOrderAccumulatesGrowthPointsAndPromotesLevelOnce() {
        MemberAccount account = account(7L, basicLevel());
        account.id = 70L;
        account.growthValue = 0;
        account.pointsBalance = 0;
        when(memberAccountLogMapper.findByEventAndOrder("MEMBER_POINTS_CHANGED", 11L))
            .thenReturn(null)
            .thenReturn(null)
            .thenReturn(legacyPaidFact(2600, "2600.00"));
        when(memberAccountMapper.findByUserIdForUpdate(7L)).thenReturn(account);
        when(memberLevelMapper.findActiveLevelForGrowth(2600)).thenReturn(goldLevel());

        BizOrder order = paidOrder("2600.00");
        Reservation reservation = reservation();
        service.recordPaidOrder(order, reservation);

        assertThat(account.growthValue).isEqualTo(2600);
        assertThat(account.pointsBalance).isEqualTo(2600);
        assertThat(account.levelCode).isEqualTo("GOLD");
        assertThat(account.totalConsumptionAmount).isEqualByComparingTo("2600.00");
        assertThat(account.totalReservations).isEqualTo(1);
        ArgumentCaptor<MemberAccountLog> promotionLog = ArgumentCaptor.forClass(MemberAccountLog.class);
        verify(memberAccountLogMapper).insert(promotionLog.capture());
        assertThat(promotionLog.getValue().beforeLevelCode).isEqualTo("BASIC");
        assertThat(promotionLog.getValue().afterLevelCode).isEqualTo("GOLD");
        verify(businessEventService).record(argThat(req -> req.eventType == BusinessEventType.MEMBER_POINTS_CHANGED));
        verify(businessEventService).record(argThat(req -> req.eventType == BusinessEventType.MEMBER_LEVEL_CHANGED));

        service.recordPaidOrder(order, reservation);

        verify(memberAccountLogMapper, times(1)).insert(any(MemberAccountLog.class));
        verify(memberAccountMapper, times(1)).updateById(any(MemberAccount.class));
    }

    @Test
    void recordPaidOrderFloorsGrowthButKeepsActualPaidAmountForConsumption() {
        MemberAccount account = account(7L, basicLevel());
        account.id = 70L;
        when(memberAccountLogMapper.findByEventAndOrder("MEMBER_POINTS_CHANGED", 11L)).thenReturn(null);
        when(memberAccountMapper.findByUserIdForUpdate(7L)).thenReturn(account);
        when(memberLevelMapper.findActiveLevelForGrowth(108)).thenReturn(basicLevel());

        service.recordPaidOrder(paidOrder("108.60"), reservation());

        assertThat(account.growthValue).isEqualTo(108);
        assertThat(account.pointsBalance).isEqualTo(108);
        assertThat(account.totalConsumptionAmount).isEqualByComparingTo("108.60");
        ArgumentCaptor<MemberAccountLog> logCaptor = ArgumentCaptor.forClass(MemberAccountLog.class);
        verify(memberAccountLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().growthDelta).isEqualTo(108);
        assertThat(logCaptor.getValue().pointsDelta).isEqualTo(108);
        assertThat(logCaptor.getValue().consumptionDelta).isEqualByComparingTo("108.60");
        assertThat(logCaptor.getValue().reservationId).isEqualTo(99L);
        assertThat(logCaptor.getValue().metadataJson).contains("ORD001");
        assertThat(logCaptor.getValue().beforeLevelCode).isEqualTo("BASIC");
        assertThat(logCaptor.getValue().afterLevelCode).isEqualTo("BASIC");
    }

    @Test
    void recordPaidOrderDuplicateLogDoesNotUpdateAccountAgain() {
        MemberAccount account = account(7L, basicLevel());
        account.id = 70L;
        MemberAccountLog legacyPaid = existingLog();
        legacyPaid.growthDelta = 2600;
        legacyPaid.pointsDelta = 2600;
        legacyPaid.consumptionDelta = new BigDecimal("2600.00");
        legacyPaid.sourceType = "PAYMENT";
        legacyPaid.reasonCode = "PAID_ORDER";
        when(memberAccountLogMapper.findByEventAndOrder("MEMBER_POINTS_CHANGED", 11L))
            .thenReturn(null)
            .thenReturn(null)
            .thenReturn(legacyPaid);
        when(memberAccountMapper.findByUserIdForUpdate(7L)).thenReturn(account);
        when(memberLevelMapper.findActiveLevelForGrowth(2600)).thenReturn(goldLevel());
        when(memberAccountLogMapper.insert(any(MemberAccountLog.class)))
            .thenThrow(new DuplicateKeyException("duplicate member account log"));

        service.recordPaidOrder(paidOrder("2600.00"), reservation());

        assertThat(account.pointsBalance).isZero();
        verify(memberAccountMapper, never()).updateById(any(MemberAccount.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void recordPaidOrderClearsStaleDormantTagWhenAccountBecomesActive() {
        MemberAccount account = account(7L, basicLevel());
        account.id = 70L;
        account.profileTagsJson = "[\"DORMANT_USER\"]";
        account.lastActiveTime = LocalDateTime.now().minusDays(10);
        when(memberAccountLogMapper.findByEventAndOrder("MEMBER_POINTS_CHANGED", 11L)).thenReturn(null);
        when(memberAccountMapper.findByUserIdForUpdate(7L)).thenReturn(account);
        when(memberLevelMapper.findActiveLevelForGrowth(108)).thenReturn(basicLevel());

        service.recordPaidOrder(paidOrder("108.60"), reservation());

        assertThat(account.profileTagsJson).contains("ACTIVE_USER").doesNotContain("DORMANT_USER");
        ArgumentCaptor<MemberProfileTag> tagCaptor = ArgumentCaptor.forClass(MemberProfileTag.class);
        verify(memberProfileTagMapper).insert(tagCaptor.capture());
        assertThat(tagCaptor.getValue().tagCode).isEqualTo("ACTIVE_USER");
    }

    @Test
    void recordPaidOrderContinuesWhenFirstAccountCreationRaces() {
        MemberAccount concurrentAccount = account(7L, basicLevel());
        concurrentAccount.id = 70L;
        when(memberAccountLogMapper.findByEventAndOrder("MEMBER_POINTS_CHANGED", 11L)).thenReturn(null);
        when(memberAccountMapper.findByUserIdForUpdate(7L))
            .thenReturn(null)
            .thenReturn(concurrentAccount);
        when(memberAccountMapper.findByUserId(7L))
            .thenReturn(null)
            .thenReturn(concurrentAccount);
        when(memberLevelMapper.findActiveLevels()).thenReturn(List.of(basicLevel(), silverLevel(), goldLevel()));
        when(memberAccountMapper.insert(any(MemberAccount.class)))
            .thenThrow(new DuplicateKeyException("duplicate member account"));
        when(memberLevelMapper.findActiveLevelForGrowth(2600)).thenReturn(goldLevel());

        assertThatCode(() -> service.recordPaidOrder(paidOrder("2600.00"), reservation()))
            .doesNotThrowAnyException();

        assertThat(concurrentAccount.growthValue).isEqualTo(2600);
        assertThat(concurrentAccount.pointsBalance).isEqualTo(2600);
        assertThat(concurrentAccount.levelCode).isEqualTo("GOLD");
        verify(memberAccountLogMapper).insert(any(MemberAccountLog.class));
        verify(memberAccountMapper).updateById(concurrentAccount);
        verify(businessEventService).record(argThat(req -> req.eventType == BusinessEventType.MEMBER_POINTS_CHANGED));
        verify(businessEventService).record(argThat(req -> req.eventType == BusinessEventType.MEMBER_LEVEL_CHANGED));
    }

    @Test
    void fullRefundWritesOneReverseLedgerEntryAndRecalculatesMemberAccount() {
        MemberAccount account = account(7L, goldLevel());
        account.id = 70L;
        account.growthValue = 2600;
        account.pointsBalance = 30;
        account.totalConsumptionAmount = new BigDecimal("2600.00");
        account.totalReservations = 1;
        account.totalMemberDiscountAmount = new BigDecimal("40.00");
        when(memberAccountMapper.findByUserIdForUpdate(7L)).thenReturn(account);
        when(memberLevelMapper.findActiveLevelForGrowth(0)).thenReturn(basicLevel());
        MemberAccountLog paidFact = existingLog();
        paidFact.accountId = 70L;
        paidFact.userId = 7L;
        paidFact.growthDelta = 2600;
        paidFact.pointsDelta = 100;
        paidFact.consumptionDelta = new BigDecimal("2600.00");
        paidFact.sourceType = "PAYMENT";
        paidFact.reasonCode = "PAID_ORDER";
        when(memberAccountLogMapper.findByEventAndOrder("MEMBER_REFUND_REVERSED", 11L)).thenReturn(null);
        when(memberAccountLogMapper.findByEventAndOrder("MEMBER_POINTS_CHANGED", 11L)).thenReturn(paidFact);

        service.reverseRefundedOrder(paidOrder("2600.00"), reservation());

        assertThat(account.growthValue).isZero();
        assertThat(account.pointsBalance).isEqualTo(-70);
        assertThat(account.totalConsumptionAmount).isEqualByComparingTo("0.00");
        assertThat(account.totalReservations).isZero();
        assertThat(account.totalMemberDiscountAmount).isEqualByComparingTo("0.00");
        assertThat(account.levelCode).isEqualTo("BASIC");
        ArgumentCaptor<MemberAccountLog> logCaptor = ArgumentCaptor.forClass(MemberAccountLog.class);
        verify(memberAccountLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().businessKey).isEqualTo("ORDER_REFUND_POINTS:11");
        assertThat(logCaptor.getValue().pointsDelta).isEqualTo(-100);
        assertThat(logCaptor.getValue().growthDelta).isEqualTo(-2600);
        assertThat(logCaptor.getValue().pointsBalanceAfter).isEqualTo(-70);
        assertThat(logCaptor.getValue().reservationId).isEqualTo(99L);
        assertThat(logCaptor.getValue().metadataJson).contains("ORD001").contains("reversal");
        assertThat(logCaptor.getValue().beforeLevelCode).isEqualTo("GOLD");
        assertThat(logCaptor.getValue().afterLevelCode).isEqualTo("BASIC");
        verify(memberAccountMapper).findByUserIdForUpdate(7L);
    }

    @Test
    void fullRefundIsIdempotentByItsOwnOrderLedgerKey() {
        MemberAccountLog existing = existingLog();
        existing.eventType = "MEMBER_REFUND_REVERSED";
        when(memberAccountLogMapper.findByEventAndOrder("MEMBER_REFUND_REVERSED", 11L)).thenReturn(existing);

        service.reverseRefundedOrder(paidOrder("100.00"), reservation());

        verify(memberAccountMapper, never()).findByUserIdForUpdate(any());
        verify(memberAccountMapper, never()).updateById(any(MemberAccount.class));
        verify(memberAccountLogMapper, never()).insert(any(MemberAccountLog.class));
    }

    @Test
    void fullRefundMissingPaidFactRecordsZeroPointExceptionAndReturnsNormally() {
        MemberAccount account = account(7L, basicLevel());
        account.id = 70L;
        account.growthValue = 100;
        account.pointsBalance = 30;
        account.totalConsumptionAmount = new BigDecimal("100.00");
        account.totalReservations = 1;
        account.totalMemberDiscountAmount = new BigDecimal("40.00");
        when(memberAccountMapper.findByUserIdForUpdate(7L)).thenReturn(account);
        when(memberAccountLogMapper.findByEventAndOrder("MEMBER_REFUND_REVERSED", 11L)).thenReturn(null);
        when(memberAccountLogMapper.findByEventAndOrder("MEMBER_POINTS_CHANGED", 11L)).thenReturn(null);
        when(memberLevelMapper.findActiveLevelForGrowth(0)).thenReturn(basicLevel());

        assertThatCode(() -> service.reverseRefundedOrder(paidOrder("100.00"), reservation()))
            .doesNotThrowAnyException();

        ArgumentCaptor<MemberAccountLog> logCaptor = ArgumentCaptor.forClass(MemberAccountLog.class);
        verify(memberAccountLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().businessKey).isEqualTo("ORDER_REFUND_POINTS_EXCEPTION:11");
        assertThat(logCaptor.getValue().eventType).isEqualTo("MEMBER_REFUND_POINTS_EXCEPTION");
        assertThat(logCaptor.getValue().reasonCode).isEqualTo("PAID_POINTS_FACT_MISSING");
        assertThat(logCaptor.getValue().pointsDelta).isZero();
        assertThat(logCaptor.getValue().growthDelta).isEqualTo(-100);
        assertThat(logCaptor.getValue().consumptionDelta).isEqualByComparingTo("-100.00");
        assertThat(logCaptor.getValue().reservationId).isEqualTo(99L);
        assertThat(logCaptor.getValue().metadataJson).contains("requiresManualReview");
        assertThat(account.pointsBalance).isEqualTo(30);
        assertThat(account.growthValue).isZero();
        assertThat(account.totalConsumptionAmount).isEqualByComparingTo("0.00");
        assertThat(account.totalReservations).isZero();
        assertThat(account.totalMemberDiscountAmount).isEqualByComparingTo("0.00");
    }

    @Test
    void fullRefundWrongOwnerPaidFactRecordsDistinctZeroPointException() {
        MemberAccount account = account(7L, basicLevel());
        account.id = 70L;
        account.pointsBalance = 30;
        MemberAccountLog wrongOwner = existingLog();
        wrongOwner.userId = 8L;
        when(memberAccountMapper.findByUserIdForUpdate(7L)).thenReturn(account);
        when(memberAccountLogMapper.findByEventAndOrder("MEMBER_REFUND_REVERSED", 11L)).thenReturn(null);
        when(memberAccountLogMapper.findByEventAndOrder("MEMBER_POINTS_CHANGED", 11L)).thenReturn(wrongOwner);

        assertThatCode(() -> service.reverseRefundedOrder(paidOrder("100.00"), reservation()))
            .doesNotThrowAnyException();

        ArgumentCaptor<MemberAccountLog> logCaptor = ArgumentCaptor.forClass(MemberAccountLog.class);
        verify(memberAccountLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode).isEqualTo("PAID_POINTS_FACT_OWNER_MISMATCH");
        assertThat(logCaptor.getValue().pointsDelta).isZero();
        assertThat(account.pointsBalance).isEqualTo(30);
    }

    @Test
    void separateOrdersForOneMemberAccumulateAgainstLockedCurrentAccount() {
        MemberAccount account = account(7L, basicLevel());
        account.id = 70L;
        when(memberAccountMapper.findByUserIdForUpdate(7L)).thenReturn(account);
        when(memberLevelMapper.findActiveLevelForGrowth(100)).thenReturn(basicLevel());
        when(memberLevelMapper.findActiveLevelForGrowth(300)).thenReturn(basicLevel());

        BizOrder first = paidOrder("100.00");
        first.id = 11L;
        BizOrder second = paidOrder("200.00");
        second.id = 12L;
        second.orderNo = "ORD002";

        service.recordPaidOrder(first, reservation());
        service.recordPaidOrder(second, reservation());

        assertThat(account.growthValue).isEqualTo(300);
        assertThat(account.pointsBalance).isEqualTo(300);
        assertThat(account.totalConsumptionAmount).isEqualByComparingTo("300.00");
        assertThat(account.totalReservations).isEqualTo(2);
        verify(memberAccountMapper, times(2)).findByUserIdForUpdate(7L);
        verify(memberAccountLogMapper, times(2)).insert(any(MemberAccountLog.class));
    }

    @Test
    void pricingPreviewFallsBackToActiveGrowthLevelWhenAccountLevelDisabled() {
        MemberAccount account = account(7L, goldLevel());
        account.id = 70L;
        account.growthValue = 2600;
        MemberLevel disabledGold = goldLevel();
        disabledGold.status = "INACTIVE";
        MemberLevel activeSilver = silverLevel();
        activeSilver.discountRate = new BigDecimal("0.9500");
        when(memberAccountMapper.findByUserId(7L)).thenReturn(account);
        when(memberLevelMapper.findActiveLevels()).thenReturn(List.of(basicLevel(), activeSilver));
        when(memberLevelMapper.findByCode("GOLD")).thenReturn(disabledGold);
        when(memberLevelMapper.findActiveLevelForGrowth(2600)).thenReturn(activeSilver);

        MemberPricingService pricingService = new MemberPricingService(service, memberLevelMapper);
        MemberPricingContext context = pricingService.previewForUser(
            7L,
            20L,
            200L,
            LocalDateTime.of(2026, 7, 2, 10, 0),
            LocalDateTime.of(2026, 7, 2, 12, 0),
            new BigDecimal("100.00"));

        assertThat(context.memberAccount.levelCode).isEqualTo("GOLD");
        assertThat(context.memberAccount.discountRate).isEqualByComparingTo("0.9000");
        assertThat(context.memberLevel.levelCode).isEqualTo("SILVER");
        assertThat(context.memberLevel.discountRate).isEqualByComparingTo("0.9500");
        assertThat(context.amountBreakdown.originalAmount()).isEqualByComparingTo("200.00");
        assertThat(context.amountBreakdown.memberDiscountAmount()).isEqualByComparingTo("10.00");
        assertThat(context.amountBreakdown.payableAmount()).isEqualByComparingTo("190.00");
    }

    @Test
    void readOnlyPricingPreviewUsesDefaultLevelWithoutCreatingAccountWhenAbsent() {
        when(memberAccountMapper.findByUserId(7L)).thenReturn(null);
        when(memberLevelMapper.findActiveLevels()).thenReturn(List.of(basicLevel(), silverLevel(), goldLevel()));
        when(memberLevelMapper.findActiveLevelForGrowth(0)).thenReturn(basicLevel());
        when(userMapper.selectById(7L)).thenReturn(user(7L, "tester", "测试用户"));

        MemberPricingService pricingService = new MemberPricingService(service, memberLevelMapper);

        MemberPricingContext context = pricingService.previewForUserReadOnly(
            7L,
            20L,
            200L,
            LocalDateTime.of(2026, 7, 2, 10, 0),
            LocalDateTime.of(2026, 7, 2, 12, 0),
            new BigDecimal("100.00"));

        assertThat(context.memberAccount.memberAccountId).isNull();
        assertThat(context.memberAccount.levelCode).isEqualTo("BASIC");
        assertThat(context.memberLevel.levelCode).isEqualTo("BASIC");
        assertThat(context.memberLevel.discountRate).isEqualByComparingTo("1.0000");
        assertThat(context.amountBreakdown.originalAmount()).isEqualByComparingTo("200.00");
        assertThat(context.amountBreakdown.memberDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(context.amountBreakdown.payableAmount()).isEqualByComparingTo("200.00");
        verify(memberAccountMapper, never()).insert(any(MemberAccount.class));
        verify(memberProfileTagMapper, never()).deactivateTagsByUserId(any());
    }

    @Test
    void overviewReturnsDistributionCoreAndDormantUsers() {
        MemberAccount dormant = account(8L, basicLevel());
        dormant.id = 80L;
        dormant.lastActiveTime = LocalDateTime.now().minusDays(120);
        dormant.profileTagsJson = "[\"DORMANT_USER\"]";
        MemberAccount core = account(9L, goldLevel());
        core.id = 90L;
        core.growthValue = 3000;
        core.pointsBalance = 500;
        core.totalConsumptionAmount = new BigDecimal("3000.00");
        core.totalReservations = 4;
        core.lastActiveTime = LocalDateTime.now().minusDays(2);
        core.profileTagsJson = "[\"HIGH_VALUE_USER\",\"ACTIVE_USER\"]";
        when(memberAccountMapper.countOverviewMembers()).thenReturn(2L);
        when(memberAccountMapper.countOverviewActiveMembers()).thenReturn(2L);
        when(memberAccountMapper.selectOverviewLevelDistribution()).thenReturn(List.of(
            Map.of("levelCode", "BASIC", "memberCount", 1L),
            Map.of("levelCode", "GOLD", "memberCount", 1L)
        ));
        when(memberAccountMapper.selectOverviewCoreMembers(10)).thenReturn(List.of(core));
        when(memberAccountMapper.selectOverviewDormantMembers(any(LocalDateTime.class), eq(10)))
            .thenReturn(List.of(dormant));
        when(userMapper.selectById(8L)).thenReturn(user(8L, "dormant", "沉睡用户"));
        when(userMapper.selectById(9L)).thenReturn(user(9L, "core", "核心用户"));

        MemberOverviewResponse overview = service.overview();

        assertThat(overview.totalMembers).isEqualTo(2);
        assertThat(overview.activeMembers).isEqualTo(2);
        assertThat(overview.levelDistribution).containsEntry("BASIC", 1L).containsEntry("GOLD", 1L);
        assertThat(overview.coreUsers).extracting(item -> item.userId).containsExactly(9L);
        assertThat(overview.dormantUsers).extracting(item -> item.userId).containsExactly(8L);
        verify(memberAccountMapper, never()).selectList(any(QueryWrapper.class));
    }

    @Test
    void accountProfileTagsUseReviewedDiscountAndDormantThresholds() {
        MemberAccount belowDiscountThreshold = account(8L, basicLevel());
        belowDiscountThreshold.id = 80L;
        belowDiscountThreshold.totalReservations = 1;
        belowDiscountThreshold.totalMemberDiscountAmount = new BigDecimal("199.99");
        belowDiscountThreshold.lastActiveTime = LocalDateTime.now().minusDays(60).plusMinutes(1);
        MemberAccount atDiscountThreshold = account(9L, basicLevel());
        atDiscountThreshold.id = 90L;
        atDiscountThreshold.totalReservations = 1;
        atDiscountThreshold.totalMemberDiscountAmount = new BigDecimal("200.00");
        atDiscountThreshold.lastActiveTime = LocalDateTime.now().minusDays(61);
        MemberAccount neverActive = account(10L, basicLevel());
        neverActive.id = 100L;
        neverActive.totalReservations = 1;
        neverActive.lastActiveTime = null;
        when(memberAccountMapper.findByUserId(8L)).thenReturn(belowDiscountThreshold);
        when(memberAccountMapper.findByUserId(9L)).thenReturn(atDiscountThreshold);
        when(memberAccountMapper.findByUserId(10L)).thenReturn(neverActive);
        when(memberLevelMapper.findActiveLevels()).thenReturn(List.of(basicLevel()));
        when(memberLevelMapper.findByCode("BASIC")).thenReturn(basicLevel());
        when(memberProfileTagMapper.findActiveByUserId(any())).thenReturn(List.of());
        when(userMapper.selectById(8L)).thenReturn(user(8L, "below", "低于阈值"));
        when(userMapper.selectById(9L)).thenReturn(user(9L, "at", "达到阈值"));
        when(userMapper.selectById(10L)).thenReturn(user(10L, "never", "未活跃"));

        MemberAccountResponse below = service.account(8L);
        MemberAccountResponse at = service.account(9L);
        MemberAccountResponse never = service.account(10L);

        assertThat(below.profileTags).doesNotContain("PRICE_SENSITIVE_USER", "DORMANT_USER");
        assertThat(at.profileTags).contains("PRICE_SENSITIVE_USER", "DORMANT_USER");
        assertThat(never.profileTags).contains("DORMANT_USER");
    }

    @Test
    void accountReadPathRemovesStaleDormantTagFromPersistedSnapshotWhenRecent() {
        MemberAccount account = account(7L, basicLevel());
        account.id = 70L;
        account.profileTagsJson = "[\"DORMANT_USER\",\"ACTIVE_USER\"]";
        account.lastActiveTime = LocalDateTime.now().minusDays(10);
        when(memberAccountMapper.findByUserId(7L)).thenReturn(account);
        when(memberLevelMapper.findActiveLevels()).thenReturn(List.of(basicLevel()));
        when(memberLevelMapper.findByCode("BASIC")).thenReturn(basicLevel());
        when(userMapper.selectById(7L)).thenReturn(user(7L, "tester", "测试用户"));

        MemberAccountResponse response = service.account(7L);

        assertThat(response.profileTags).containsExactly("ACTIVE_USER");
    }

    @Test
    void accountRejectsMissingSysUserAndDoesNotCreatePhantomAccount() {
        // AUD-P2-13: 不存在的 SysUser 必须被拒绝，不得建立幻影 member_account，也不得写入画像标签
        doThrow(BusinessException.badRequest("用户不存在"))
            .when(entityExistenceService).requireActiveUser(999L);

        assertThatThrownBy(() -> service.account(999L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("用户不存在");

        verify(memberAccountMapper, never()).findByUserId(any());
        verify(memberAccountMapper, never()).insert(any(MemberAccount.class));
        verify(memberProfileTagMapper, never()).deactivateTagsByUserId(any());
        verify(memberProfileTagMapper, never()).insert(any(MemberProfileTag.class));
    }

    @Test
    void generatedProfileTagMetadataIncludesAccountSnapshot() {
        MemberAccount account = account(7L, basicLevel());
        account.id = 70L;
        when(memberAccountLogMapper.findByEventAndOrder("MEMBER_POINTS_CHANGED", 11L)).thenReturn(null);
        when(memberAccountMapper.findByUserIdForUpdate(7L)).thenReturn(account);
        when(memberLevelMapper.findActiveLevelForGrowth(108)).thenReturn(basicLevel());

        service.recordPaidOrder(paidOrder("108.60"), reservation());

        ArgumentCaptor<MemberProfileTag> tagCaptor = ArgumentCaptor.forClass(MemberProfileTag.class);
        verify(memberProfileTagMapper, times(1)).insert(tagCaptor.capture());
        MemberProfileTag tag = tagCaptor.getValue();
        assertThat(tag.evidenceRef).isEqualTo("member.account:70");
        assertThat(tag.metadataJson)
            .contains("\"growthValue\":108")
            .contains("\"totalReservations\":1")
            .contains("\"totalConsumptionAmount\":108.60")
            .contains("\"totalMemberDiscountAmount\":40.00")
            .contains("\"lastActiveTime\"");
    }

    private void authenticate(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private SaveMemberLevelRequest requestWithDiscount(String discount) {
        SaveMemberLevelRequest request = new SaveMemberLevelRequest();
        request.levelCode = "SILVER";
        request.levelName = "银卡会员";
        request.growthThreshold = 1000;
        request.discountRate = new BigDecimal(discount);
        request.benefitText = "预约享 95 折";
        request.ruleVersion = "B2_MEMBER_V1";
        request.status = "ACTIVE";
        return request;
    }

    private MemberLevel basicLevel() {
        return level(1L, "BASIC", "普通会员", 0, "1.0000", 1);
    }

    private MemberLevel silverLevel() {
        return level(2L, "SILVER", "银卡会员", 1000, "0.9500", 2);
    }

    private MemberLevel goldLevel() {
        return level(3L, "GOLD", "金卡会员", 2000, "0.9000", 3);
    }

    private MemberLevel level(Long id, String code, String name, int growthThreshold, String discountRate, int sortOrder) {
        MemberLevel level = new MemberLevel();
        level.id = id;
        level.levelCode = code;
        level.levelName = name;
        level.growthThreshold = growthThreshold;
        level.discountRate = new BigDecimal(discountRate);
        level.benefitText = name + "权益";
        level.ruleVersion = "B2_MEMBER_V1";
        level.status = "ACTIVE";
        level.sortOrder = sortOrder;
        level.deleted = 0;
        return level;
    }

    private MemberAccount account(Long userId, MemberLevel level) {
        MemberAccount account = new MemberAccount();
        account.userId = userId;
        account.levelId = level.id;
        account.levelCode = level.levelCode;
        account.levelName = level.levelName;
        account.growthValue = 0;
        account.pointsBalance = 0;
        account.totalConsumptionAmount = new BigDecimal("0.00");
        account.totalReservations = 0;
        account.totalMemberDiscountAmount = new BigDecimal("0.00");
        account.lastActiveTime = LocalDateTime.now();
        account.status = "ACTIVE";
        account.deleted = 0;
        return account;
    }

    private MemberProfileTag tag(Long userId, String code) {
        MemberProfileTag tag = new MemberProfileTag();
        tag.userId = userId;
        tag.tagCode = code;
        tag.tagLabel = code;
        tag.status = "ACTIVE";
        tag.generatedAt = LocalDateTime.now();
        return tag;
    }

    private SysUser user(Long id, String username, String nickname) {
        SysUser user = new SysUser();
        user.id = id;
        user.username = username;
        user.nickname = nickname;
        return user;
    }

    private BizOrder paidOrder(String amount) {
        BizOrder order = new BizOrder();
        order.id = 11L;
        order.orderNo = "ORD001";
        order.reservationId = 99L;
        order.userId = 7L;
        order.venueId = 20L;
        order.payableAmount = new BigDecimal(amount);
        order.paidAmount = new BigDecimal(amount);
        order.memberDiscountAmount = new BigDecimal("40.00");
        return order;
    }

    private Reservation reservation() {
        Reservation reservation = new Reservation();
        reservation.id = 99L;
        reservation.userId = 7L;
        reservation.venueId = 20L;
        reservation.spaceId = 200L;
        reservation.startTime = LocalDateTime.now().plusDays(1);
        reservation.endTime = reservation.startTime.plusHours(2);
        return reservation;
    }

    private MemberAccountLog existingLog() {
        MemberAccountLog log = new MemberAccountLog();
        log.id = 1L;
        log.accountId = 70L;
        log.userId = 7L;
        log.orderId = 11L;
        log.eventType = "MEMBER_POINTS_CHANGED";
        return log;
    }

    private MemberAccountLog legacyPaidFact(int points, String consumption) {
        MemberAccountLog log = existingLog();
        log.growthDelta = points;
        log.pointsDelta = points;
        log.consumptionDelta = new BigDecimal(consumption);
        log.sourceType = "PAYMENT";
        log.reasonCode = "PAID_ORDER";
        return log;
    }
}
