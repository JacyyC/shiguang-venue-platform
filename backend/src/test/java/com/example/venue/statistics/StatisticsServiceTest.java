package com.example.venue.statistics;

import com.example.venue.common.CurrentUser;
import com.example.venue.statistics.dto.DashboardSummary;
import com.example.venue.statistics.mapper.StatisticsMapper;
import com.example.venue.statistics.service.StatisticsService;
import com.example.venue.venue.service.VenueAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatisticsServiceTest {
    @Mock
    private StatisticsMapper statisticsMapper;
    @Mock
    private VenueAccessService venueAccessService;

    private StatisticsService statisticsService;

    @BeforeEach
    void setUp() {
        statisticsService = new StatisticsService(statisticsMapper, venueAccessService);
        var user = new CurrentUser(1L, "admin", Set.of("SYSTEM_ADMIN"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void dashboardCalculatesOccupancyRateAndAllowsSystemWideScope() {
        when(statisticsMapper.todayReservations(null)).thenReturn(5L);
        when(statisticsMapper.pendingReviews(null)).thenReturn(2L);
        // AUD-P2-12 系统管理员 venueId 为 null，totalUsers 走全平台 sys_user 计数
        when(statisticsMapper.totalUsers(null)).thenReturn(9L);
        when(statisticsMapper.revenue(null)).thenReturn(BigDecimal.valueOf(1200));
        when(statisticsMapper.occupiedMinutesToday(null)).thenReturn(BigDecimal.valueOf(90));
        when(statisticsMapper.bookableMinutesToday(null)).thenReturn(BigDecimal.valueOf(180));

        DashboardSummary summary = statisticsService.dashboard(null);

        assertThat(summary.todayReservations).isEqualTo(5L);
        assertThat(summary.pendingReviews).isEqualTo(2L);
        assertThat(summary.totalUsers).isEqualTo(9L);
        assertThat(summary.occupancyRate).isEqualByComparingTo("50.00");
        verify(statisticsMapper).todayReservations(null);
        verify(statisticsMapper).totalUsers(null);
    }

    @Test
    void venueAdminDashboardScopesTotalUsersToVenue() {
        // AUD-P2-12 场馆管理员登录，只能查看所属场馆范围，不泄露全平台用户数
        var user = new CurrentUser(2L, "venue-admin", Set.of("VENUE_ADMIN"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
        Long venueId = 10L;

        when(statisticsMapper.todayReservations(venueId)).thenReturn(3L);
        when(statisticsMapper.pendingReviews(venueId)).thenReturn(1L);
        // totalUsers 以场馆 ID 调用，返回该场馆关联用户数而非全平台计数
        when(statisticsMapper.totalUsers(venueId)).thenReturn(7L);
        when(statisticsMapper.revenue(venueId)).thenReturn(BigDecimal.valueOf(500));
        when(statisticsMapper.occupiedMinutesToday(venueId)).thenReturn(BigDecimal.valueOf(60));
        when(statisticsMapper.bookableMinutesToday(venueId)).thenReturn(BigDecimal.valueOf(120));

        DashboardSummary summary = statisticsService.dashboard(venueId);

        assertThat(summary.totalUsers).isEqualTo(7L);
        // 确认 totalUsers 以场馆 ID 调用，场馆管理员不会触发全平台计数
        verify(statisticsMapper).totalUsers(venueId);
        verify(statisticsMapper, never()).totalUsers(null);
    }

    @Test
    void statisticsQueriesUseSettlementFactsAndInventoryConstraints() throws Exception {
        String revenue = StatisticsMapper.class.getMethod("revenue", Long.class).getAnnotation(Select.class).value()[0];
        String trend = StatisticsMapper.class.getMethod("revenueTrend", Long.class).getAnnotation(Select.class).value()[0];
        String bookable = StatisticsMapper.class.getMethod("bookableMinutesToday", Long.class)
            .getAnnotation(Select.class).value()[0];

        assertThat(revenue).contains("payment_record", "refund_record", "p.pay_time", "net_amount");
        assertThat(trend).contains("payment_record", "refund_record", "date_format(p.pay_time");
        assertThat(bookable).contains("venue_business_calendar", "venue_unavailable_block", "buffer_before_minutes");
    }

    // A26-P3-31 occupancyRate 分子分母口径对齐：分子必须与分母使用同一套 ACTIVE 场地过滤，
    // 且占用状态集合与 ReservationStatus.occupies() 一致（含 REFUNDING，不含 COMPLETED 等终态）。
    @Test
    void occupancyNumeratorAlignsWithDenominatorScopeAndDomainOccupyingSet() throws Exception {
        String occupied = StatisticsMapper.class.getMethod("occupiedMinutesToday", Long.class)
            .getAnnotation(Select.class).value()[0];
        String bookable = StatisticsMapper.class.getMethod("bookableMinutesToday", Long.class)
            .getAnnotation(Select.class).value()[0];

        // 分子必须 join venue_space 与 venue，并加 ACTIVE 场地过滤，与分母 space_window 一致
        assertThat(occupied).contains("join venue_space s", "join venue v");
        assertThat(occupied).contains("s.status = 'ACTIVE'", "v.status = 'ACTIVE'");
        assertThat(occupied).contains("s.deleted = 0", "v.deleted = 0");
        // 分子分母的场地过滤短语必须同源（同名字段、同样 'ACTIVE' 字面量）
        assertThat(bookable).contains("s.status = 'ACTIVE' and v.status = 'ACTIVE'");

        // 占用状态集合与 ReservationStatus.occupies() 对齐：含 REFUNDING（退款中但占用未释放）
        assertThat(occupied).contains("'REFUNDING'");
        // COMPLETED/REJECTED/CANCELED/EXPIRED/REFUNDED 是终态或已释放占用，不得计入分子
        assertThat(occupied).doesNotContain("'COMPLETED'", "'REJECTED'", "'CANCELED'", "'EXPIRED'", "'REFUNDED'");
    }
}
