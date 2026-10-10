package com.example.venue.advertising.controller;

import com.example.venue.advertising.dto.AdvertisingRequests;
import com.example.venue.advertising.dto.AdvertisingResponses;
import com.example.venue.advertising.service.AdvertisingAdminService;
import com.example.venue.advertising.dto.AdvertisingAnalysisResponses;
import com.example.venue.advertising.service.AdvertisingAnalysisService;
import com.example.venue.common.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商业广告运营接口。每个动作使用独立叶子权限，content-admin:view 不直接授予广告写权限。
 * 本 Stage 只提供事实和生命周期管理，不提供首页决策、事件或归因接口。
 */
@RestController
@RequestMapping("/api/content-admin/advertising")
public class AdvertisingAdminController {

    private static final String READ_AUTHORITIES =
        "hasAnyAuthority('advertising:manage','advertising:review','advertising:publish',"
            + "'advertising:analysis:view','advertising:audit:view')";

    private final AdvertisingAdminService adminService;
    private AdvertisingAnalysisService analysisService;

    public AdvertisingAdminController(AdvertisingAdminService adminService) {
        this.adminService = adminService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAnalysisService(AdvertisingAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @GetMapping("/analysis/overview")
    @PreAuthorize("hasAuthority('advertising:analysis:view')")
    public Result<AdvertisingAnalysisResponses.Overview> analysisOverview(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime until
    ) {
        return Result.ok(analysisService.overview(from, until));
    }

    @GetMapping("/advertisers")
    @PreAuthorize(READ_AUTHORITIES)
    public Result<List<AdvertisingResponses.AdvertiserResponse>> advertisers(
        @RequestParam(required = false) Integer limit
    ) {
        return Result.ok(adminService.listAdvertisers(limit));
    }

    @PostMapping("/advertisers")
    @PreAuthorize("hasAuthority('advertising:manage')")
    public Result<AdvertisingResponses.AdvertiserResponse> createAdvertiser(
        @Valid @RequestBody AdvertisingRequests.CreateAdvertiserRequest request
    ) {
        return Result.ok(adminService.createAdvertiser(request));
    }

    @PostMapping("/advertisers/{advertiserId}/suspend")
    @PreAuthorize("hasAuthority('advertising:publish')")
    public Result<AdvertisingResponses.AdvertiserResponse> suspendAdvertiser(
        @PathVariable Long advertiserId,
        @Valid @RequestBody(required = false) AdvertisingRequests.TransitionRequest request
    ) {
        return Result.ok(adminService.suspendAdvertiser(advertiserId, request));
    }

    @PostMapping("/advertisers/{advertiserId}/retire")
    @PreAuthorize("hasAuthority('advertising:publish')")
    public Result<AdvertisingResponses.AdvertiserResponse> retireAdvertiser(
        @PathVariable Long advertiserId,
        @Valid @RequestBody(required = false) AdvertisingRequests.TransitionRequest request
    ) {
        return Result.ok(adminService.retireAdvertiser(advertiserId, request));
    }

    @GetMapping("/campaigns")
    @PreAuthorize(READ_AUTHORITIES)
    public Result<List<AdvertisingResponses.CampaignResponse>> campaigns(
        @RequestParam(required = false) Long venueId,
        @RequestParam(required = false) Integer limit
    ) {
        return Result.ok(adminService.listCampaigns(venueId, limit));
    }

    @PostMapping("/campaigns")
    @PreAuthorize("hasAuthority('advertising:manage')")
    public Result<AdvertisingResponses.CampaignResponse> createCampaign(
        @Valid @RequestBody AdvertisingRequests.CreateCampaignRequest request
    ) {
        return Result.ok(adminService.createCampaign(request));
    }

    @PostMapping("/campaigns/{campaignId}/submit-review")
    @PreAuthorize("hasAuthority('advertising:manage')")
    public Result<AdvertisingResponses.CampaignResponse> submitCampaignReview(
        @PathVariable Long campaignId
    ) {
        return Result.ok(adminService.submitCampaignReview(campaignId));
    }

    @PostMapping("/campaigns/{campaignId}/approve")
    @PreAuthorize("hasAuthority('advertising:review')")
    public Result<AdvertisingResponses.CampaignResponse> approveCampaign(
        @PathVariable Long campaignId
    ) {
        return Result.ok(adminService.approveCampaign(campaignId));
    }

    @PostMapping("/campaigns/{campaignId}/reject")
    @PreAuthorize("hasAuthority('advertising:review')")
    public Result<AdvertisingResponses.CampaignResponse> rejectCampaign(
        @PathVariable Long campaignId,
        @Valid @RequestBody AdvertisingRequests.ReviewDecisionRequest request
    ) {
        return Result.ok(adminService.rejectCampaign(campaignId, request));
    }

    @PostMapping("/campaigns/{campaignId}/publish")
    @PreAuthorize("hasAuthority('advertising:publish')")
    public Result<AdvertisingResponses.CampaignResponse> publishCampaign(
        @PathVariable Long campaignId
    ) {
        return Result.ok(adminService.publishCampaign(campaignId));
    }

    @PostMapping("/campaigns/{campaignId}/pause")
    @PreAuthorize("hasAuthority('advertising:publish')")
    public Result<AdvertisingResponses.CampaignResponse> pauseCampaign(
        @PathVariable Long campaignId,
        @Valid @RequestBody(required = false) AdvertisingRequests.TransitionRequest request
    ) {
        return Result.ok(adminService.pauseCampaign(campaignId, request));
    }

    @PostMapping("/campaigns/{campaignId}/retire")
    @PreAuthorize("hasAuthority('advertising:publish')")
    public Result<AdvertisingResponses.CampaignResponse> retireCampaign(
        @PathVariable Long campaignId,
        @Valid @RequestBody(required = false) AdvertisingRequests.TransitionRequest request
    ) {
        return Result.ok(adminService.retireCampaign(campaignId, request));
    }

    @GetMapping("/campaigns/{campaignId}/creatives")
    @PreAuthorize(READ_AUTHORITIES)
    public Result<List<AdvertisingResponses.CreativeResponse>> creatives(
        @PathVariable Long campaignId,
        @RequestParam(required = false) Integer limit
    ) {
        return Result.ok(adminService.listCreatives(campaignId, limit));
    }

    @PostMapping("/creatives")
    @PreAuthorize("hasAuthority('advertising:manage')")
    public Result<AdvertisingResponses.CreativeResponse> createCreative(
        @Valid @RequestBody AdvertisingRequests.CreateCreativeRequest request
    ) {
        return Result.ok(adminService.createCreative(request));
    }

    @PostMapping("/creatives/{creativeId}/submit-review")
    @PreAuthorize("hasAuthority('advertising:manage')")
    public Result<AdvertisingResponses.CreativeResponse> submitCreativeReview(
        @PathVariable Long creativeId
    ) {
        return Result.ok(adminService.submitCreativeReview(creativeId));
    }

    @PostMapping("/creatives/{creativeId}/approve")
    @PreAuthorize("hasAuthority('advertising:review')")
    public Result<AdvertisingResponses.CreativeResponse> approveCreative(
        @PathVariable Long creativeId
    ) {
        return Result.ok(adminService.approveCreative(creativeId));
    }

    @PostMapping("/creatives/{creativeId}/reject")
    @PreAuthorize("hasAuthority('advertising:review')")
    public Result<AdvertisingResponses.CreativeResponse> rejectCreative(
        @PathVariable Long creativeId,
        @Valid @RequestBody AdvertisingRequests.ReviewDecisionRequest request
    ) {
        return Result.ok(adminService.rejectCreative(creativeId, request));
    }

    @PostMapping("/creatives/{creativeId}/publish")
    @PreAuthorize("hasAuthority('advertising:publish')")
    public Result<AdvertisingResponses.CreativeResponse> publishCreative(
        @PathVariable Long creativeId
    ) {
        return Result.ok(adminService.publishCreative(creativeId));
    }

    @PostMapping("/creatives/{creativeId}/pause")
    @PreAuthorize("hasAuthority('advertising:publish')")
    public Result<AdvertisingResponses.CreativeResponse> pauseCreative(
        @PathVariable Long creativeId,
        @Valid @RequestBody(required = false) AdvertisingRequests.TransitionRequest request
    ) {
        return Result.ok(adminService.pauseCreative(creativeId, request));
    }

    @PostMapping("/creatives/{creativeId}/retire")
    @PreAuthorize("hasAuthority('advertising:publish')")
    public Result<AdvertisingResponses.CreativeResponse> retireCreative(
        @PathVariable Long creativeId,
        @Valid @RequestBody(required = false) AdvertisingRequests.TransitionRequest request
    ) {
        return Result.ok(adminService.retireCreative(creativeId, request));
    }

    @GetMapping("/audits")
    @PreAuthorize("hasAuthority('advertising:audit:view')")
    public Result<List<AdvertisingResponses.AuditResponse>> audits(
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) Long entityId,
        @RequestParam(required = false) Long venueId,
        @RequestParam(required = false) Integer limit
    ) {
        return Result.ok(adminService.listAudits(entityType, entityId, venueId, limit));
    }
}
