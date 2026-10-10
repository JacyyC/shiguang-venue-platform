package com.example.venue.community.activity.service;

import com.example.venue.common.BusinessException;
import com.example.venue.community.activity.entity.CommunityActivityCommand;
import com.example.venue.community.activity.mapper.CommunityActivityCommandMapper;
import com.example.venue.community.service.CommunityTextPolicy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ActivityCommandService {
    private final CommunityActivityCommandMapper mapper;
    private final CommunityTextPolicy textPolicy;

    public ActivityCommandService(CommunityActivityCommandMapper mapper, CommunityTextPolicy textPolicy) {
        this.mapper = mapper;
        this.textPolicy = textPolicy;
    }

    public Claim claim(Long actorUserId, String commandType, String rawKey, String requestHash) {
        String key = textPolicy.scopedIdempotencyKey(commandType, rawKey);
        CommunityActivityCommand command = new CommunityActivityCommand();
        command.actorUserId = actorUserId;
        command.commandType = commandType;
        command.idempotencyKey = key;
        command.requestHash = requestHash;
        command.status = "PROCESSING";
        command.createTime = LocalDateTime.now();
        try {
            mapper.insert(command);
            return new Claim(command, false);
        } catch (DuplicateKeyException duplicate) {
            // The unique insert serializes one concrete key. A missing-row FOR UPDATE here would
            // gap-lock neighboring keys and deadlock independent commands from the same actor.
            CommunityActivityCommand winner = mapper.findForUpdate(actorUserId, commandType, key);
            if (winner == null) {
                throw duplicate;
            }
            return replay(winner, requestHash);
        }
    }

    public void complete(Claim claim, String targetType, Long targetId, String resultStatus) {
        if (claim.replayed()) {
            return;
        }
        if (mapper.complete(claim.command().id, targetType, targetId, resultStatus, LocalDateTime.now()) != 1) {
            throw BusinessException.conflict("幂等命令已被其他请求处理");
        }
        claim.command().targetType = targetType;
        claim.command().targetId = targetId;
        claim.command().resultStatus = resultStatus;
        claim.command().status = "COMPLETED";
    }

    private Claim replay(CommunityActivityCommand existing, String requestHash) {
        if (!requestHash.equals(existing.requestHash)) {
            throw BusinessException.conflict("同一 Idempotency-Key 对应了不同请求");
        }
        if (!"COMPLETED".equals(existing.status)) {
            throw BusinessException.conflict("相同请求正在处理中");
        }
        return new Claim(existing, true);
    }

    public record Claim(CommunityActivityCommand command, boolean replayed) {
    }
}
