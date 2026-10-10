package com.example.venue.member.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.venue.member.entity.MemberRewardRedemption;
import com.example.venue.member.mapper.MemberRewardRedemptionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class MemberRewardFailureService {
    private final MemberRewardRedemptionMapper mapper;
    private final MemberRewardEventService eventService;

    public MemberRewardFailureService(MemberRewardRedemptionMapper mapper, MemberRewardEventService eventService) {
        this.mapper = mapper;
        this.eventService = eventService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MemberRewardRedemption markFailed(Long id, String code) {
        mapper.update(null, new UpdateWrapper<MemberRewardRedemption>()
            .eq("id", id).eq("status", "PENDING")
            .set("status", "FAILED")
            .set("failure_code", code)
            .set("failure_message", "兑换执行失败，可由授权人员重试")
            .set("update_time", LocalDateTime.now()));
        MemberRewardRedemption current = mapper.selectByIdForUpdate(id);
        if (current != null && "FAILED".equals(current.status)) {
            eventService.redemption(current, "REDEMPTION_FAILED", "REDEMPTION_FAILED:" + id,
                "PENDING", "FAILED", "FAILED", null, "SYSTEM", code,
                "兑换执行失败，可由授权人员重试");
        }
        return current;
    }
}
