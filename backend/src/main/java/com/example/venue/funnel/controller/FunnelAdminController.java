package com.example.venue.funnel.controller;

import com.example.venue.common.Result;
import com.example.venue.funnel.dto.FunnelEventResponse;
import com.example.venue.funnel.dto.FunnelOverviewQuery;
import com.example.venue.funnel.dto.FunnelOverviewResponse;
import com.example.venue.funnel.service.FunnelAnalyticsService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class FunnelAdminController {
    private final FunnelAnalyticsService analyticsService;

    public FunnelAdminController(FunnelAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/api/admin/funnel/overview")
    @PreAuthorize("hasAuthority('funnel:analytics:view')")
    public Result<FunnelOverviewResponse> overview(@Valid FunnelOverviewQuery query) {
        return Result.ok(analyticsService.overview(query));
    }

    @GetMapping("/api/admin/funnel/samples")
    @PreAuthorize("hasAuthority('funnel:analytics:view')")
    public Result<List<FunnelEventResponse>> samples(@Valid FunnelOverviewQuery query) {
        return Result.ok(analyticsService.samples(query));
    }
}
