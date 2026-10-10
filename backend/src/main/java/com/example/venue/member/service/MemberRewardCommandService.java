package com.example.venue.member.service;

import com.example.venue.common.BusinessException;
import com.example.venue.member.entity.MemberRewardRedemption;
import com.example.venue.member.mapper.MemberRewardRedemptionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

@Service
public class MemberRewardCommandService {
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final MemberRewardRedemptionMapper mapper;

    public MemberRewardCommandService(MemberRewardRedemptionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MemberRewardRedemption findExisting(Long userId, String idempotencyKey, String requestHash) {
        if (userId == null || idempotencyKey == null || idempotencyKey.isBlank()
            || requestHash == null || requestHash.isBlank()) {
            throw BusinessException.badRequest("兑换命令参数不完整");
        }
        MemberRewardRedemption existing = mapper.findCommandForUpdate(userId, idempotencyKey);
        if (existing != null) {
            requireSameRequest(existing, requestHash);
        }
        return existing;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MemberRewardRedemption register(MemberRewardRedemption command) {
        if (command == null || command.userId == null || command.idempotencyKey == null
            || command.idempotencyKey.isBlank() || command.requestHash == null || command.requestHash.isBlank()) {
            throw BusinessException.badRequest("兑换命令参数不完整");
        }
        LocalDateTime now = LocalDateTime.now();
        if (command.redemptionNo == null) {
            command.redemptionNo = "MR" + now.format(NO_TIME)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        }
        command.status = "PENDING";
        command.sourceType = command.sourceType == null ? "USER" : command.sourceType;
        command.createdBy = command.createdBy == null ? command.userId : command.createdBy;
        command.createTime = now;
        command.updateTime = now;
        try {
            mapper.insert(command);
            return command;
        } catch (DuplicateKeyException duplicate) {
            MemberRewardRedemption existing = mapper.findCommandForUpdate(command.userId, command.idempotencyKey);
            if (existing == null) {
                throw duplicate;
            }
            requireSameRequest(existing, command.requestHash);
            return existing;
        }
    }

    private void requireSameRequest(MemberRewardRedemption existing, String requestHash) {
        if (!Objects.equals(existing.requestHash, requestHash)) {
            throw BusinessException.conflict("幂等键已用于不同请求");
        }
    }
}
