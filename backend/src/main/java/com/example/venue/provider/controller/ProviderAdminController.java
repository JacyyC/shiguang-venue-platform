package com.example.venue.provider.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.provider.dto.OpsMetricSnapshotResponse;
import com.example.venue.provider.dto.ProviderOverviewResponse;
import com.example.venue.provider.dto.ProviderQuery;
import com.example.venue.provider.dto.ProviderRefreshResponse;
import com.example.venue.provider.dto.RiskSnapshotResponse;
import com.example.venue.provider.dto.VenueObservationLocationRequest;
import com.example.venue.provider.dto.VenueObservationLocationResponse;
import com.example.venue.provider.dto.WeatherSnapshotResponse;
import com.example.venue.provider.entity.ProviderCallLog;
import com.example.venue.provider.service.ProviderAdminService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ProviderAdminController {
    private final ProviderAdminService providerAdminService;

    public ProviderAdminController(ProviderAdminService providerAdminService) {
        this.providerAdminService = providerAdminService;
    }

    @GetMapping("/api/admin/providers/overview")
    @PreAuthorize("hasAuthority('provider:observe')")
    public Result<ProviderOverviewResponse> overview() {
        return Result.ok(providerAdminService.overview());
    }

    @GetMapping("/api/admin/providers/calls")
    @PreAuthorize("hasAuthority('provider:observe')")
    public Result<PageResult<ProviderCallLog>> calls(@Valid ProviderQuery query) {
        return Result.ok(providerAdminService.callLogs(query));
    }

    @GetMapping("/api/admin/providers/weather/snapshots")
    @PreAuthorize("hasAuthority('provider:observe')")
    public Result<PageResult<WeatherSnapshotResponse>> weatherSnapshots(@Valid ProviderQuery query) {
        return Result.ok(providerAdminService.weatherSnapshots(query));
    }

    @GetMapping("/api/admin/providers/risk/snapshots")
    @PreAuthorize("hasAuthority('provider:observe')")
    public Result<PageResult<RiskSnapshotResponse>> riskSnapshots(@Valid ProviderQuery query) {
        return Result.ok(providerAdminService.riskSnapshots(query));
    }

    @GetMapping("/api/admin/providers/metrics/snapshots")
    @PreAuthorize("hasAuthority('provider:observe')")
    public Result<PageResult<OpsMetricSnapshotResponse>> metricSnapshots(@Valid ProviderQuery query) {
        return Result.ok(providerAdminService.metricSnapshots(query));
    }

    @AuditLog(module = "Provider观测", action = "刷新天气provider")
    @PostMapping("/api/admin/providers/weather/refresh")
    @PreAuthorize("hasAuthority('provider:refresh')")
    public Result<ProviderRefreshResponse> refreshWeather(@RequestParam Long venueId) {
        return Result.ok(providerAdminService.refreshWeather(venueId));
    }

    @AuditLog(module = "Provider观测", action = "刷新风险provider")
    @PostMapping("/api/admin/providers/risk/refresh")
    @PreAuthorize("hasAuthority('provider:refresh')")
    public Result<ProviderRefreshResponse> refreshRisk(@RequestParam(required = false) Long venueId) {
        return Result.ok(providerAdminService.refreshRisk(venueId));
    }

    @AuditLog(module = "Provider观测", action = "刷新指标provider")
    @PostMapping("/api/admin/providers/metrics/refresh")
    @PreAuthorize("hasAuthority('provider:refresh')")
    public Result<ProviderRefreshResponse> refreshMetrics() {
        return Result.ok(providerAdminService.refreshMetrics());
    }

    @GetMapping("/api/admin/providers/venues/locations")
    @PreAuthorize("hasAuthority('provider:observe')")
    public Result<List<VenueObservationLocationResponse>> locations() {
        return Result.ok(providerAdminService.locations());
    }

    @AuditLog(module = "Provider观测", action = "维护场馆观测位置")
    @PutMapping("/api/admin/providers/venues/{venueId}/location")
    @PreAuthorize("hasAuthority('provider:refresh')")
    public Result<VenueObservationLocationResponse> saveLocation(@PathVariable Long venueId,
                                                                 @Valid @RequestBody VenueObservationLocationRequest request) {
        return Result.ok(providerAdminService.saveLocation(venueId, request));
    }
}
