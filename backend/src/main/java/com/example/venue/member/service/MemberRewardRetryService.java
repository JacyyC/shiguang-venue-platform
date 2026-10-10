package com.example.venue.member.service;

import com.example.venue.common.BusinessException;
import com.example.venue.member.entity.MemberRewardRedemption;
import com.example.venue.member.mapper.MemberRewardRedemptionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class MemberRewardRetryService {
    private final MemberRewardRedemptionMapper mapper;
    private final MemberRewardEventService eventService;

    public MemberRewardRetryService(MemberRewardRedemptionMapper mapper, MemberRewardEventService eventService) {
        this.mapper = mapper;
        this.eventService = eventService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RetryAttempt start(Long redemptionId, Long operatorId) {
        MemberRewardRedemption redemption = mapper.selectByIdForUpdate(redemptionId);
        if (redemption == null) {
            throw BusinessException.notFound("积分兑换不存在");
        }
        if (!"FAILED".equals(redemption.status)) {
            throw BusinessException.conflict("只有 FAILED 兑换可授权重试");
        }
        String attemptKey = "REDEMPTION_RETRY:" + redemption.id + ":" + UUID.randomUUID();
        redemption.status = "PENDING";
        redemption.failureCode = null;
        redemption.failureMessage = null;
        redemption.updateTime = LocalDateTime.now();
        mapper.updateById(redemption);
        eventService.redemption(redemption, "REDEMPTION_RETRY_STARTED", attemptKey + ":STARTED",
            "FAILED", "PENDING", "STARTED", operatorId, "ADMIN", "AUTHORIZED_RETRY",
            "授权人员开始重试积分兑换");
        return new RetryAttempt(redemption.id, attemptKey, operatorId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MemberRewardRedemption finish(RetryAttempt attempt) {
        MemberRewardRedemption redemption = mapper.selectByIdForUpdate(attempt.redemptionId());
        if (redemption == null) {
            throw BusinessException.notFound("积分兑换不存在");
        }
        String reasonCode = redemption.failureCode == null ? redemption.reasonCode : redemption.failureCode;
        String reasonText = redemption.failureMessage == null ? redemption.reasonText : redemption.failureMessage;
        eventService.redemption(redemption, "REDEMPTION_RETRY_RESULT", attempt.attemptKey() + ":RESULT",
            "PENDING", redemption.status, redemption.status, attempt.operatorId(), "ADMIN",
            reasonCode == null ? "AUTHORIZED_RETRY_RESULT" : reasonCode,
            reasonText == null ? "授权重试已收敛" : reasonText);
        return redemption;
    }

    public record RetryAttempt(Long redemptionId, String attemptKey, Long operatorId) {
    }
}
