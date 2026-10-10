package com.example.venue.member;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.PageQuery;
import com.example.venue.member.entity.MemberRewardRedemption;
import com.example.venue.member.mapper.MemberRewardRedemptionMapper;
import com.example.venue.member.service.MemberRewardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberRewardHistoryQueryTest {
    @Mock private MemberRewardRedemptionMapper redemptionMapper;

    private MemberRewardService service;

    @BeforeEach
    void setUp() {
        service = new MemberRewardService(null, null, redemptionMapper, null, null, null, null, null,
            null, null, null, null, null, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(7L, "member-user", Set.of("USER"), Set.of("member-reward:me")), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void historyAppliesStatusFilterOnlyForKnownStatuses() {
        when(redemptionMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenAnswer(invocation -> {
            Page<MemberRewardRedemption> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0);
            return page;
        });

        service.history(new PageQuery(), "pending");
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);

        service.history(new PageQuery(), null);
        verify(redemptionMapper, org.mockito.Mockito.times(2)).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getAllValues().get(0).getSqlSegment()).contains("status =");
        assertThat(captor.getAllValues().get(0).getParamNameValuePairs().values()).contains("PENDING");
        assertThat(captor.getAllValues().get(1).getSqlSegment()).doesNotContain("status =");

        assertThatThrownBy(() -> service.history(new PageQuery(), "ARCHIVED"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("兑换记录状态");
    }
}
