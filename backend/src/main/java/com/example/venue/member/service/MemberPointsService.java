package com.example.venue.member.service;

import com.example.venue.common.BusinessException;
import com.example.venue.member.entity.MemberAccount;
import com.example.venue.member.entity.MemberAccountLog;
import com.example.venue.member.mapper.MemberAccountLogMapper;
import com.example.venue.member.mapper.MemberAccountMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class MemberPointsService {
    private final MemberAccountMapper accountMapper;
    private final MemberAccountLogMapper logMapper;

    public MemberPointsService(MemberAccountMapper accountMapper, MemberAccountLogMapper logMapper) {
        this.accountMapper = accountMapper;
        this.logMapper = logMapper;
    }

    @Transactional
    public MemberAccountLog append(Long userId, String businessKey, String eventType, String ruleVersion,
                                   int pointsDelta, int growthDelta, BigDecimal consumptionDelta,
                                   Long orderId, String sourceType, String reasonCode, String reasonText) {
        validate(userId, businessKey, eventType, ruleVersion);
        MemberAccountLog existing = logMapper.findByBusinessKey(businessKey);
        if (existing != null) {
            requireSamePayload(existing, null, userId, businessKey, eventType, ruleVersion,
                pointsDelta, growthDelta, consumptionDelta, orderId, sourceType, reasonCode,
                reasonText, null, null, null, null, false);
            return existing;
        }
        MemberAccount account = accountMapper.findByUserIdForUpdate(userId);
        if (account == null || Integer.valueOf(1).equals(account.deleted)) {
            throw BusinessException.conflict("会员账户不存在");
        }
        return appendLocked(account, businessKey, eventType, ruleVersion, pointsDelta, growthDelta,
            consumptionDelta, orderId, sourceType, reasonCode, reasonText);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MemberAccountLog appendLocked(MemberAccount account, String businessKey, String eventType,
                                         String ruleVersion, int pointsDelta, int growthDelta,
                                         BigDecimal consumptionDelta, Long orderId, String sourceType,
                                         String reasonCode, String reasonText) {
        return appendLocked(account, businessKey, eventType, ruleVersion, pointsDelta, growthDelta,
            consumptionDelta, orderId, sourceType, reasonCode, reasonText, null, null, null, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MemberAccountLog appendLocked(MemberAccount account, String businessKey, String eventType,
                                         String ruleVersion, int pointsDelta, int growthDelta,
                                         BigDecimal consumptionDelta, Long orderId, String sourceType,
                                         String reasonCode, String reasonText, Long reservationId,
                                         String beforeLevelCode, String afterLevelCode, String metadataJson) {
        if (account == null || account.userId == null) {
            throw BusinessException.badRequest("会员账户不能为空");
        }
        validate(account.userId, businessKey, eventType, ruleVersion);
        MemberAccountLog existing = logMapper.findByBusinessKey(businessKey);
        if (existing != null) {
            requireSamePayload(existing, account.id, account.userId, businessKey, eventType, ruleVersion,
                pointsDelta, growthDelta, consumptionDelta, orderId, sourceType, reasonCode,
                reasonText, reservationId, beforeLevelCode, afterLevelCode, metadataJson, false);
            return existing;
        }
        int before = account.pointsBalance == null ? 0 : account.pointsBalance;
        int after = Math.addExact(before, pointsDelta);
        MemberAccountLog log = new MemberAccountLog();
        log.accountId = account.id;
        log.userId = account.userId;
        log.eventType = eventType;
        log.beforeLevelCode = beforeLevelCode == null ? account.levelCode : beforeLevelCode;
        log.afterLevelCode = afterLevelCode == null ? account.levelCode : afterLevelCode;
        log.growthDelta = growthDelta;
        log.pointsDelta = pointsDelta;
        log.consumptionDelta = consumptionDelta == null ? BigDecimal.ZERO : consumptionDelta;
        log.reservationId = reservationId;
        log.orderId = orderId;
        log.sourceType = sourceType;
        log.reasonCode = reasonCode;
        log.reasonText = reasonText;
        log.metadataJson = metadataJson;
        log.businessKey = businessKey;
        log.ruleVersion = ruleVersion;
        log.pointsBalanceBefore = before;
        log.pointsBalanceAfter = after;
        log.createTime = LocalDateTime.now();
        try {
            logMapper.insert(log);
        } catch (DuplicateKeyException duplicate) {
            MemberAccountLog winner = logMapper.findByBusinessKey(businessKey);
            if (winner == null && orderId != null) {
                winner = logMapper.findByEventAndOrder(eventType, orderId);
            }
            if (winner != null) {
                boolean legacyOrderFact = winner.businessKey == null && orderId != null;
                requireSamePayload(winner, account.id, account.userId, businessKey, eventType, ruleVersion,
                    pointsDelta, growthDelta, consumptionDelta, orderId, sourceType, reasonCode,
                    reasonText, reservationId, beforeLevelCode, afterLevelCode, metadataJson, legacyOrderFact);
                return winner;
            }
            throw duplicate;
        }
        account.pointsBalance = after;
        account.updateTime = log.createTime;
        accountMapper.updateById(account);
        return log;
    }

    public MemberAccountLog requirePaidOrderFact(Long orderId, Long userId, Long accountId) {
        MemberAccountLog fact = logMapper.findByEventAndOrder("MEMBER_POINTS_CHANGED", orderId);
        if (fact == null) {
            throw new PaidOrderFactException("PAID_POINTS_FACT_MISSING", "原支付积分事实缺失，无法自动冲销");
        }
        if (!Objects.equals(fact.userId, userId)) {
            throw new PaidOrderFactException("PAID_POINTS_FACT_OWNER_MISMATCH", "原支付积分事实归属不一致");
        }
        if (!Objects.equals(fact.accountId, accountId)) {
            throw new PaidOrderFactException("PAID_POINTS_FACT_ACCOUNT_MISMATCH", "原支付积分事实账户不一致");
        }
        if (fact.pointsDelta == null || fact.pointsDelta < 0 || fact.growthDelta == null || fact.growthDelta < 0
            || amount(fact.consumptionDelta).signum() < 0
            || !Objects.equals(fact.sourceType, "PAYMENT") || !Objects.equals(fact.reasonCode, "PAID_ORDER")
            || (fact.businessKey != null && (!Objects.equals(fact.businessKey, "ORDER_PAID_POINTS:" + orderId)
                || !Objects.equals(fact.ruleVersion, "R4_POINTS_V1")))) {
            throw new PaidOrderFactException("PAID_POINTS_FACT_PAYLOAD_INVALID", "原支付积分事实载荷异常");
        }
        return fact;
    }

    public MemberAccountLog requireCompatibleOrderFact(MemberAccountLog fact, Long userId, String businessKey,
                                                        String eventType, String ruleVersion, int pointsDelta,
                                                        int growthDelta, BigDecimal consumptionDelta, Long orderId,
                                                        String sourceType, String reasonCode) {
        if (fact == null) {
            return null;
        }
        requireSamePayload(fact, null, userId, businessKey, eventType, ruleVersion,
            pointsDelta, growthDelta, consumptionDelta, orderId, sourceType, reasonCode,
            fact.reasonText, fact.reservationId, fact.beforeLevelCode, fact.afterLevelCode,
            fact.metadataJson, fact.businessKey == null);
        return fact;
    }

    public static final class PaidOrderFactException extends BusinessException {
        private final String reasonCode;

        private PaidOrderFactException(String reasonCode, String message) {
            super(com.example.venue.common.ResultCode.CONFLICT, message);
            this.reasonCode = reasonCode;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }

    private void validate(Long userId, String businessKey, String eventType, String ruleVersion) {
        if (userId == null || businessKey == null || businessKey.isBlank()
            || eventType == null || eventType.isBlank() || ruleVersion == null || ruleVersion.isBlank()) {
            throw BusinessException.badRequest("积分事实参数不完整");
        }
    }

    private void requireSamePayload(MemberAccountLog fact, Long accountId, Long userId, String businessKey,
                                    String eventType, String ruleVersion, int pointsDelta, int growthDelta,
                                    BigDecimal consumptionDelta, Long orderId, String sourceType,
                                    String reasonCode, String reasonText, Long reservationId,
                                    String beforeLevelCode, String afterLevelCode, String metadataJson,
                                    boolean allowLegacyOrderFact) {
        boolean sameOwner = Objects.equals(fact.userId, userId)
            && (accountId == null || Objects.equals(fact.accountId, accountId));
        boolean sameCorePayload = Objects.equals(fact.eventType, eventType)
            && Objects.equals(fact.orderId, orderId)
            && Objects.equals(fact.pointsDelta, pointsDelta)
            && Objects.equals(fact.growthDelta, growthDelta)
            && amount(fact.consumptionDelta).compareTo(amount(consumptionDelta)) == 0
            && Objects.equals(fact.sourceType, sourceType)
            && Objects.equals(fact.reasonCode, reasonCode);
        boolean sameVersionedPayload = Objects.equals(fact.businessKey, businessKey)
            && Objects.equals(fact.ruleVersion, ruleVersion)
            && Objects.equals(fact.reasonText, reasonText)
            && Objects.equals(fact.reservationId, reservationId)
            && Objects.equals(fact.beforeLevelCode, beforeLevelCode == null ? fact.beforeLevelCode : beforeLevelCode)
            && Objects.equals(fact.afterLevelCode, afterLevelCode == null ? fact.afterLevelCode : afterLevelCode)
            && Objects.equals(fact.metadataJson, metadataJson);
        boolean compatibleLegacy = allowLegacyOrderFact && fact.businessKey == null && fact.ruleVersion == null;
        if (!sameOwner || !sameCorePayload || (!compatibleLegacy && !sameVersionedPayload)) {
            throw BusinessException.conflict("积分业务键载荷不一致");
        }
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
