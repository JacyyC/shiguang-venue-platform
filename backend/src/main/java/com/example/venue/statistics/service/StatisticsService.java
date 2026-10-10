package com.example.venue.statistics.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.Constants;
import com.example.venue.security.SecurityContext;
import com.example.venue.statistics.dto.ChartPoint;
import com.example.venue.statistics.dto.DashboardSummary;
import com.example.venue.statistics.mapper.StatisticsMapper;
import com.example.venue.venue.service.VenueAccessService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class StatisticsService {
    private final StatisticsMapper statisticsMapper;
    private final VenueAccessService venueAccessService;

    public StatisticsService(StatisticsMapper statisticsMapper, VenueAccessService venueAccessService) {
        this.statisticsMapper = statisticsMapper;
        this.venueAccessService = venueAccessService;
    }

    public DashboardSummary dashboard(Long venueId) {
        Long effectiveVenueId = normalizeVenueScope(venueId);
        DashboardSummary summary = new DashboardSummary();
        summary.todayReservations = statisticsMapper.todayReservations(effectiveVenueId);
        summary.pendingReviews = statisticsMapper.pendingReviews(effectiveVenueId);
        // AUD-P2-12 totalUsers 按场馆范围统计，场馆管理员不看到全平台用户数
        summary.totalUsers = statisticsMapper.totalUsers(effectiveVenueId);
        summary.revenue = statisticsMapper.revenue(effectiveVenueId);
        summary.occupancyRate = occupancyRate(effectiveVenueId);
        return summary;
    }

    public List<ChartPoint> topSpaces(Long venueId) {
        return statisticsMapper.topSpaces(normalizeVenueScope(venueId));
    }

    public List<ChartPoint> revenueTrend(Long venueId) {
        return statisticsMapper.revenueTrend(normalizeVenueScope(venueId));
    }

    private Long normalizeVenueScope(Long venueId) {
        var user = SecurityContext.currentUser();
        if (user == null) {
            throw BusinessException.forbidden("未登录");
        }
        if (user.hasRole(Constants.ROLE_SYSTEM_ADMIN)) {
            return venueId;
        }
        if (venueId == null) {
            throw BusinessException.badRequest("场馆 ID 必填");
        }
        venueAccessService.requireVenueManageAccess(venueId);
        return venueId;
    }

    private BigDecimal occupancyRate(Long venueId) {
        BigDecimal bookableMinutes = nullToZero(statisticsMapper.bookableMinutesToday(venueId));
        if (bookableMinutes.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal occupiedMinutes = nullToZero(statisticsMapper.occupiedMinutesToday(venueId));
        return occupiedMinutes.min(bookableMinutes).multiply(BigDecimal.valueOf(100))
            .divide(bookableMinutes, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
