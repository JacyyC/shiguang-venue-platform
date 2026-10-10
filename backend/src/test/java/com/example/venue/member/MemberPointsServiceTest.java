package com.example.venue.member;

import com.example.venue.common.BusinessException;
import com.example.venue.member.entity.MemberAccount;
import com.example.venue.member.entity.MemberAccountLog;
import com.example.venue.member.mapper.MemberAccountLogMapper;
import com.example.venue.member.mapper.MemberAccountMapper;
import com.example.venue.member.service.MemberPointsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberPointsServiceTest {
    @Mock private MemberAccountMapper accountMapper;
    @Mock private MemberAccountLogMapper logMapper;

    private MemberPointsService service;

    @BeforeEach
    void setUp() {
        service = new MemberPointsService(accountMapper, logMapper);
    }

    @Test
    void appendUpdatesProjectionAndSnapshotsEvenWhenBalanceBecomesNegative() {
        MemberAccount account = account(70L, 7L, 30);
        when(accountMapper.findByUserIdForUpdate(7L)).thenReturn(account);
        when(logMapper.findByBusinessKey("ORDER_REFUND_POINTS:11")).thenReturn(null);
        when(logMapper.insert(any(MemberAccountLog.class))).thenAnswer(invocation -> {
            MemberAccountLog log = invocation.getArgument(0);
            log.id = 99L;
            return 1;
        });

        MemberAccountLog result = service.append(
            7L, "ORDER_REFUND_POINTS:11", "MEMBER_REFUND_REVERSED", "R4_POINTS_V1",
            -100, 0, BigDecimal.ZERO, 11L, "REFUND", "FULL_REFUND", "退款精确冲销");

        assertThat(account.pointsBalance).isEqualTo(-70);
        ArgumentCaptor<MemberAccountLog> captor = ArgumentCaptor.forClass(MemberAccountLog.class);
        verify(logMapper).insert(captor.capture());
        assertThat(captor.getValue().pointsBalanceBefore).isEqualTo(30);
        assertThat(captor.getValue().pointsBalanceAfter).isEqualTo(-70);
        assertThat(captor.getValue().pointsDelta).isEqualTo(-100);
        assertThat(result.id).isEqualTo(99L);
        verify(accountMapper).updateById(account);
    }

    @Test
    void sameBusinessKeyReturnsCommittedFactWithoutUpdatingAgain() {
        MemberAccountLog existing = new MemberAccountLog();
        existing.id = 99L;
        existing.userId = 7L;
        existing.businessKey = "REWARD_REDEEM:88";
        existing.eventType = "MEMBER_REWARD_REDEEMED";
        existing.ruleVersion = "R4_POINTS_V1";
        existing.pointsDelta = -20;
        existing.growthDelta = 0;
        existing.consumptionDelta = BigDecimal.ZERO;
        existing.sourceType = "REWARD";
        existing.reasonCode = "REDEEM";
        existing.reasonText = "兑换扣减";
        when(logMapper.findByBusinessKey(existing.businessKey)).thenReturn(existing);

        assertThat(service.append(
            7L, existing.businessKey, "MEMBER_REWARD_REDEEMED", "R4_POINTS_V1",
            -20, 0, BigDecimal.ZERO, null, "REWARD", "REDEEM", "兑换扣减")).isSameAs(existing);

        verify(accountMapper, never()).findByUserIdForUpdate(any());
        verify(accountMapper, never()).updateById(any(MemberAccount.class));
        verify(logMapper, never()).insert(any(MemberAccountLog.class));
    }

    @Test
    void sameBusinessKeyRejectsAnyDifferentImmutablePayload() {
        MemberAccountLog existing = new MemberAccountLog();
        existing.accountId = 70L;
        existing.userId = 7L;
        existing.businessKey = "ORDER_PAID_POINTS:11";
        existing.eventType = "MEMBER_POINTS_CHANGED";
        existing.ruleVersion = "R4_POINTS_V1";
        existing.pointsDelta = 20;
        existing.growthDelta = 20;
        existing.consumptionDelta = new BigDecimal("20.00");
        existing.orderId = 11L;
        existing.sourceType = "PAYMENT";
        existing.reasonCode = "PAID_ORDER";
        existing.reasonText = "支付积分";
        when(logMapper.findByBusinessKey(existing.businessKey)).thenReturn(existing);

        assertThatThrownBy(() -> service.append(7L, existing.businessKey, existing.eventType,
            existing.ruleVersion, 21, 20, existing.consumptionDelta, 11L,
            existing.sourceType, existing.reasonCode, existing.reasonText))
            .isInstanceOf(BusinessException.class).hasMessageContaining("载荷不一致");
        assertThatThrownBy(() -> service.append(7L, existing.businessKey, "MEMBER_REFUND_REVERSED",
            existing.ruleVersion, 20, 20, existing.consumptionDelta, 11L,
            existing.sourceType, existing.reasonCode, existing.reasonText))
            .isInstanceOf(BusinessException.class).hasMessageContaining("载荷不一致");
        assertThatThrownBy(() -> service.append(7L, existing.businessKey, existing.eventType,
            "R4_POINTS_V2", 20, 20, existing.consumptionDelta, 11L,
            existing.sourceType, existing.reasonCode, existing.reasonText))
            .isInstanceOf(BusinessException.class).hasMessageContaining("载荷不一致");
        assertThatThrownBy(() -> service.append(7L, existing.businessKey, existing.eventType,
            existing.ruleVersion, 20, 20, existing.consumptionDelta, 12L,
            existing.sourceType, existing.reasonCode, existing.reasonText))
            .isInstanceOf(BusinessException.class).hasMessageContaining("载荷不一致");
    }

    @Test
    void duplicateBusinessKeyWinnerStillValidatesRuleAndOrderPayload() {
        MemberAccount account = account(70L, 7L, 10);
        MemberAccountLog winner = new MemberAccountLog();
        winner.accountId = 70L;
        winner.userId = 7L;
        winner.businessKey = "ORDER_PAID_POINTS:11";
        winner.eventType = "MEMBER_POINTS_CHANGED";
        winner.ruleVersion = "R4_POINTS_V1";
        winner.pointsDelta = 20;
        winner.growthDelta = 20;
        winner.consumptionDelta = new BigDecimal("20.00");
        winner.orderId = 11L;
        winner.sourceType = "PAYMENT";
        winner.reasonCode = "PAID_ORDER";
        winner.reasonText = "支付积分";
        when(logMapper.findByBusinessKey(winner.businessKey)).thenReturn(null, winner);
        when(logMapper.insert(any(MemberAccountLog.class)))
            .thenThrow(new DuplicateKeyException("business key winner"));

        assertThatThrownBy(() -> service.appendLocked(account, winner.businessKey, winner.eventType,
            "R4_POINTS_V2", 20, 20, new BigDecimal("20.00"), 11L,
            "PAYMENT", "PAID_ORDER", "支付积分"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("载荷不一致");
        assertThat(account.pointsBalance).isEqualTo(10);
    }

    @Test
    void duplicateOrderWinnerAcceptsOnlyCompatibleLegacyFactWithoutRelabelingIt() {
        MemberAccount account = account(70L, 7L, 10);
        MemberAccountLog legacy = new MemberAccountLog();
        legacy.accountId = 70L;
        legacy.userId = 7L;
        legacy.eventType = "MEMBER_POINTS_CHANGED";
        legacy.pointsDelta = 20;
        legacy.growthDelta = 20;
        legacy.consumptionDelta = new BigDecimal("20.00");
        legacy.orderId = 11L;
        legacy.sourceType = "PAYMENT";
        legacy.reasonCode = "PAID_ORDER";
        when(logMapper.insert(any(MemberAccountLog.class)))
            .thenThrow(new DuplicateKeyException("legacy event/order winner"));
        when(logMapper.findByEventAndOrder("MEMBER_POINTS_CHANGED", 11L)).thenReturn(legacy);

        assertThat(service.appendLocked(account, "ORDER_PAID_POINTS:11", "MEMBER_POINTS_CHANGED",
            "R4_POINTS_V1", 20, 20, new BigDecimal("20.00"), 11L,
            "PAYMENT", "PAID_ORDER", "支付积分")).isSameAs(legacy);
        assertThat(legacy.businessKey).isNull();
        assertThat(legacy.ruleVersion).isNull();
        assertThat(account.pointsBalance).isEqualTo(10);

        assertThatThrownBy(() -> service.appendLocked(account, "ORDER_PAID_POINTS:11",
            "MEMBER_POINTS_CHANGED", "R4_POINTS_V1", 21, 20, new BigDecimal("20.00"),
            11L, "PAYMENT", "PAID_ORDER", "支付积分"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("载荷不一致");
    }

    @Test
    void requirePaidOrderFactRejectsMissingOrWrongOwnerInsteadOfGuessingFromAmount() {
        assertThatThrownBy(() -> service.requirePaidOrderFact(11L, 7L, 70L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("原支付积分事实缺失");

        MemberAccountLog wrong = new MemberAccountLog();
        wrong.orderId = 11L;
        wrong.userId = 8L;
        wrong.accountId = 70L;
        when(logMapper.findByEventAndOrder("MEMBER_POINTS_CHANGED", 11L)).thenReturn(wrong);

        assertThatThrownBy(() -> service.requirePaidOrderFact(11L, 7L, 70L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("归属不一致");
    }

    private MemberAccount account(long id, long userId, int points) {
        MemberAccount account = new MemberAccount();
        account.id = id;
        account.userId = userId;
        account.levelCode = "BASIC";
        account.pointsBalance = points;
        account.status = "ACTIVE";
        account.deleted = 0;
        return account;
    }
}
