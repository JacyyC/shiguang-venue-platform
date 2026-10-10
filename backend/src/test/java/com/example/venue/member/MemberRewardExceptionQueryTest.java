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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台异常兑换列表的状态入参合同（SHIGUANG-BATCH-04-REFACTOR-20260914 S03 / B4-M1）。
 *
 * <p>修复前该列表固定 {@code in(PENDING, FAILED, REJECTED)} 且无任何状态入参，而补偿端点只接受
 * {@code SUCCEEDED} 且券非空 —— 因此后台无法取得任何可补偿的兑换 id，补偿入口在真实数据下不可达。
 * 本测试锁定两点：默认范围必须保持原三态（不改变既有行为），显式入参时才按该状态精确过滤；
 * 非法值沿用用户侧同一 400 文案，不静默忽略。</p>
 */
@ExtendWith(MockitoExtension.class)
class MemberRewardExceptionQueryTest {
    @Mock private MemberRewardRedemptionMapper redemptionMapper;

    private MemberRewardService service;

    @BeforeEach
    void setUp() {
        service = new MemberRewardService(null, null, redemptionMapper, null, null, null, null, null,
            null, null, null, null, null, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(9L, "content-operator", Set.of("SYSTEM_ADMIN"),
                Set.of("member-reward:exception:manage")), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void stubEmptyPage() {
        when(redemptionMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenAnswer(invocation -> {
            Page<MemberRewardRedemption> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0);
            return page;
        });
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void defaultScopeKeepsTheOriginalThreeExceptionStatuses() {
        stubEmptyPage();
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);

        service.exceptions(new PageQuery(), null);
        service.exceptions(new PageQuery(), "   ");

        verify(redemptionMapper, times(2)).selectPage(any(Page.class), captor.capture());
        for (QueryWrapper<?> wrapper : captor.getAllValues()) {
            assertThat(wrapper.getSqlSegment()).contains("status IN");
            assertThat(wrapper.getParamNameValuePairs().values())
                .containsExactlyInAnyOrder("PENDING", "FAILED", "REJECTED");
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void explicitStatusFiltersExactlyAndMakesSucceededReachable() {
        stubEmptyPage();
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);

        // 小写入参也需要归一化：补偿的唯一合法前置 SUCCEEDED 必须在后台可查。
        service.exceptions(new PageQuery(), "succeeded");
        service.exceptions(new PageQuery(), "COMPENSATED");

        verify(redemptionMapper, times(2)).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getAllValues().get(0).getSqlSegment()).contains("status =");
        assertThat(captor.getAllValues().get(0).getParamNameValuePairs().values()).containsExactly("SUCCEEDED");
        assertThat(captor.getAllValues().get(1).getSqlSegment()).contains("status =");
        assertThat(captor.getAllValues().get(1).getParamNameValuePairs().values()).containsExactly("COMPENSATED");
    }

    @Test
    void unknownStatusIsRejectedWithTheSharedMessageInsteadOfBeingIgnored() {
        assertThatThrownBy(() -> service.exceptions(new PageQuery(), "ARCHIVED"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("兑换记录状态");

        verify(redemptionMapper, times(0)).selectPage(any(Page.class), any(QueryWrapper.class));
    }
}
