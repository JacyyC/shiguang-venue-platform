package com.example.venue.statistics.controller;

import com.example.venue.common.Result;
import com.example.venue.statistics.dto.ChartPoint;
import com.example.venue.statistics.dto.DashboardSummary;
import com.example.venue.statistics.service.StatisticsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/statistics")
@PreAuthorize("hasAnyAuthority('venue-admin:view','system:view')")
public class StatisticsController {
    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/dashboard")
    public Result<DashboardSummary> dashboard(@RequestParam(required = false) Long venueId) {
        return Result.ok(statisticsService.dashboard(venueId));
    }

    @GetMapping("/top-spaces")
    public Result<List<ChartPoint>> topSpaces(@RequestParam(required = false) Long venueId) {
        return Result.ok(statisticsService.topSpaces(venueId));
    }

    @GetMapping("/revenue")
    public Result<List<ChartPoint>> revenue(@RequestParam(required = false) Long venueId) {
        return Result.ok(statisticsService.revenueTrend(venueId));
    }
}
