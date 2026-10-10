package com.example.venue.venue.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.resource.dto.ResourceRuleChangeRequest;
import com.example.venue.resource.dto.ResourceRuleResponse;
import com.example.venue.venue.dto.SaveMaintenanceRequest;
import com.example.venue.venue.dto.SaveSpaceRequest;
import com.example.venue.venue.dto.SaveVenueRequest;
import com.example.venue.venue.dto.VenueQuery;
import com.example.venue.venue.dto.VenueFacetsResponse;
import com.example.venue.venue.dto.VenueListItemResponse;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueFacilityMaintenance;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.dto.VenueSpaceResponse;
import com.example.venue.venue.service.VenueService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class VenueController {
    private final VenueService venueService;

    public VenueController(VenueService venueService) {
        this.venueService = venueService;
    }

    @GetMapping("/api/venues")
    public Result<PageResult<VenueListItemResponse>> list(@Valid VenueQuery query) {
        return Result.ok(venueService.list(query));
    }

    @GetMapping("/api/venues/facets")
    public Result<VenueFacetsResponse> facets() {
        return Result.ok(venueService.facets());
    }

    @GetMapping("/api/venues/{id}")
    public Result<Venue> detail(@PathVariable Long id) {
        return Result.ok(venueService.detail(id));
    }

    @GetMapping("/api/venues/{id}/spaces")
    public Result<List<VenueSpaceResponse>> spaces(@PathVariable Long id) {
        return Result.ok(venueService.spaces(id));
    }

    @GetMapping("/api/admin/venues/manageable")
    @PreAuthorize("hasAnyAuthority('venue-admin:view','system:view')")
    public Result<List<Venue>> manageableVenues() {
        return Result.ok(venueService.manageableVenues());
    }

    @AuditLog(module = "场馆管理", action = "新增场馆")
    @PostMapping("/api/admin/venues")
    @PreAuthorize("hasAuthority('system:view')")
    public Result<Venue> createVenue(@Valid @RequestBody SaveVenueRequest request) {
        return Result.ok(venueService.saveVenue(request));
    }

    @AuditLog(module = "场馆管理", action = "修改场馆")
    @PutMapping("/api/admin/venues/{id}")
    @PreAuthorize("hasAuthority('venue:space:manage')")
    public Result<Venue> updateVenue(@PathVariable Long id, @Valid @RequestBody SaveVenueRequest request) {
        return Result.ok(venueService.updateVenue(id, request));
    }

    @AuditLog(module = "场地管理", action = "新增场地")
    @PostMapping("/api/admin/spaces")
    @PreAuthorize("hasAuthority('venue:space:manage')")
    public Result<VenueSpace> createSpace(@Valid @RequestBody SaveSpaceRequest request) {
        return Result.ok(venueService.saveSpace(request));
    }

    @AuditLog(module = "场地管理", action = "修改场地")
    @PutMapping("/api/admin/spaces/{id}")
    @PreAuthorize("hasAuthority('venue:space:manage')")
    public Result<VenueSpace> updateSpace(@PathVariable Long id, @Valid @RequestBody SaveSpaceRequest request) {
        return Result.ok(venueService.updateSpace(id, request));
    }

    @AuditLog(module = "场地管理", action = "修改场地状态")
    @PutMapping("/api/admin/spaces/{id}/status")
    @PreAuthorize("hasAuthority('venue:space:manage')")
    public Result<Void> updateSpaceStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        venueService.updateSpaceStatus(id, body.get("status"));
        return Result.ok();
    }

    @GetMapping("/api/admin/spaces/{id}/resource-rule")
    @PreAuthorize("hasAuthority('venue:space:manage')")
    public Result<ResourceRuleResponse> resourceRule(@PathVariable Long id) {
        return Result.ok(venueService.manageableResourceRule(id));
    }

    @AuditLog(module = "场地管理", action = "配置资源预订规则")
    @PutMapping("/api/admin/spaces/{id}/resource-rule")
    @PreAuthorize("hasAuthority('venue:space:manage')")
    public Result<ResourceRuleResponse> configureResourceRule(
        @PathVariable Long id,
        @RequestBody ResourceRuleChangeRequest request
    ) {
        return Result.ok(venueService.configureResourceRule(id, request));
    }

    @GetMapping("/api/admin/spaces/{id}/maintenances")
    @PreAuthorize("hasAuthority('venue:space:manage')")
    public Result<List<VenueFacilityMaintenance>> maintenances(@PathVariable Long id) {
        return Result.ok(venueService.maintenances(id));
    }

    @AuditLog(module = "场地管理", action = "新增维护时段")
    @PostMapping("/api/admin/spaces/{id}/maintenances")
    @PreAuthorize("hasAuthority('venue:space:manage')")
    public Result<VenueFacilityMaintenance> createMaintenance(@PathVariable Long id, @Valid @RequestBody SaveMaintenanceRequest request) {
        request.spaceId = id;
        return Result.ok(venueService.createMaintenance(request));
    }

    @AuditLog(module = "场地管理", action = "取消维护时段")
    @PutMapping("/api/admin/maintenances/{id}/cancel")
    @PreAuthorize("hasAuthority('venue:space:manage')")
    public Result<Void> cancelMaintenance(@PathVariable Long id) {
        venueService.cancelMaintenance(id);
        return Result.ok();
    }
}
