package com.example.venue.member;

import com.example.venue.common.BusinessException;
import com.example.venue.member.entity.MemberRewardRedemption;
import com.example.venue.member.mapper.MemberRewardRedemptionMapper;
import com.example.venue.member.service.MemberRewardCommandService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberRewardCommandServiceTest {
    @Mock private MemberRewardRedemptionMapper mapper;

    @Test
    void duplicateSameHashReturnsStableCommandButDifferentHashConflicts() {
        MemberRewardCommandService service = new MemberRewardCommandService(mapper);
        MemberRewardRedemption existing = command("hash-one");
        when(mapper.insert(any(MemberRewardRedemption.class)))
            .thenThrow(new DuplicateKeyException("duplicate command"));
        when(mapper.findCommandForUpdate(7L, "idem-1")).thenReturn(existing);

        assertThat(service.register(command("hash-one"))).isSameAs(existing);
        assertThatThrownBy(() -> service.register(command("hash-two")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("幂等键已用于不同请求");
    }

    private MemberRewardRedemption command(String hash) {
        MemberRewardRedemption command = new MemberRewardRedemption();
        command.userId = 7L;
        command.idempotencyKey = "idem-1";
        command.requestHash = hash;
        command.status = "PENDING";
        return command;
    }
}
