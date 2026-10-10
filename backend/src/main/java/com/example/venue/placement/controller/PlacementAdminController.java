package com.example.venue.placement.controller;

import com.example.venue.common.Result;
import com.example.venue.placement.dto.PlacementRequests;
import com.example.venue.placement.dto.PlacementResponses;
import com.example.venue.placement.service.PlacementAdminService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 宣传管理接口。挂在既有 /api/content-admin 工作区下，按动作逐方法鉴权：
 * placement:manage 读取版位/候选、预览来源、创建草稿；placement:publish 发布/暂停/下线；
 * placement:audit:view 读取有界决策/事件列表。content-admin:view 只守卫前端入口，不授予任何宣传 API。
 */
@RestController
@RequestMapping("/api/content-admin/placements")
public class PlacementAdminController {

    private final PlacementAdminService adminService;

    public PlacementAdminController(PlacementAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/slots")
    @PreAuthorize("hasAnyAuthority('placement:manage','placement:publish','placement:audit:view')")
    public Result<List<PlacementResponses.SlotResponse>> slots() {
        return Result.ok(adminService.listSlots());
    }

    @GetMapping("/candidates")
    @PreAuthorize("hasAnyAuthority('placement:manage','placement:publish','placement:audit:view')")
    public Result<List<PlacementResponses.CandidateVersionResponse>> candidates(
        @RequestParam(required = false) Long slotId,
        @RequestParam(required = false) Integer limit
    ) {
        return Result.ok(adminService.listCandidates(slotId, limit));
    }

    @PostMapping("/candidates")
    @PreAuthorize("hasAuthority('placement:manage')")
    public Result<PlacementResponses.CandidateVersionResponse> createCandidate(
        @Valid @RequestBody PlacementRequests.CreateCandidateRequest request
    ) {
        return Result.ok(adminService.createCandidate(request));
    }

    @GetMapping("/sources/preview")
    @PreAuthorize("hasAnyAuthority('placement:manage','placement:publish')")
    public Result<PlacementResponses.SourcePreviewResponse> previewSource(
        @RequestParam String sourceType,
        @RequestParam Long sourceId
    ) {
        return Result.ok(adminService.previewSource(sourceType, sourceId));
    }

    @PostMapping("/candidates/{candidateId}/publish")
    @PreAuthorize("hasAuthority('placement:publish')")
    public Result<PlacementResponses.CandidateVersionResponse> publish(@PathVariable Long candidateId) {
        return Result.ok(adminService.publishCandidate(candidateId));
    }

    @PostMapping("/candidates/{candidateId}/pause")
    @PreAuthorize("hasAuthority('placement:publish')")
    public Result<PlacementResponses.CandidateVersionResponse> pause(@PathVariable Long candidateId) {
        return Result.ok(adminService.pauseCandidate(candidateId));
    }

    @PostMapping("/candidates/{candidateId}/retire")
    @PreAuthorize("hasAuthority('placement:publish')")
    public Result<PlacementResponses.CandidateVersionResponse> retire(@PathVariable Long candidateId) {
        return Result.ok(adminService.retireCandidate(candidateId));
    }

    @GetMapping("/decisions")
    @PreAuthorize("hasAuthority('placement:audit:view')")
    public Result<List<PlacementResponses.DecisionAuditResponse>> decisions(
        @RequestParam(required = false) Long slotId,
        @RequestParam(required = false) Integer limit
    ) {
        return Result.ok(adminService.listDecisions(slotId, limit));
    }

    @GetMapping("/events")
    @PreAuthorize("hasAuthority('placement:audit:view')")
    public Result<List<PlacementResponses.EventAuditResponse>> events(
        @RequestParam(required = false) Long slotId,
        @RequestParam(required = false) Integer limit
    ) {
        return Result.ok(adminService.listEvents(slotId, limit));
    }

    @GetMapping("/decisions/{decisionId}/events")
    @PreAuthorize("hasAuthority('placement:audit:view')")
    public Result<List<PlacementResponses.EventAuditResponse>> decisionEvents(
        @PathVariable Long decisionId,
        @RequestParam(required = false) Integer limit
    ) {
        return Result.ok(adminService.listDecisionEvents(decisionId, limit));
    }
}
