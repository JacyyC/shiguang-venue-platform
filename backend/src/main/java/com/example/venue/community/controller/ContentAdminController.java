package com.example.venue.community.controller;

import com.example.venue.common.Result;
import com.example.venue.community.dto.CommunityRequests;
import com.example.venue.community.dto.CommunityResponses;
import com.example.venue.community.dto.CursorPage;
import com.example.venue.community.service.CommunityGovernanceService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Function;

@RestController
@RequestMapping("/api/content-admin")
public class ContentAdminController {
    private final CommunityGovernanceService governanceService;

    public ContentAdminController(CommunityGovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    @GetMapping("/content/{targetType}/{targetId}")
    @PreAuthorize("hasAnyAuthority('community:report:review','community:content:moderate','community:content:restore')")
    public Result<CommunityResponses.AdminContent> inspectContent(
        @PathVariable String targetType,
        @PathVariable Long targetId,
        @RequestParam(required = false) Long reportId) {
        return Result.ok(governanceService.inspect(targetType, targetId, reportId));
    }

    @GetMapping("/reports")
    @PreAuthorize("hasAuthority('community:report:review')")
    public Result<CursorPage<CommunityResponses.AdminReport>> reports(
        @Valid CommunityRequests.AdminReportQuery query) {
        return Result.ok(mapPage(governanceService.reports(query), CommunityResponses::adminReport));
    }

    @PostMapping("/reports/{reportId}/decision")
    @PreAuthorize("hasAuthority('community:report:review')")
    public Result<CommunityResponses.UserReport> decideReport(
        @PathVariable Long reportId,
        @Valid @RequestBody CommunityRequests.ReportDecision request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.ok(CommunityResponses.userReport(
            governanceService.decideReport(reportId, request, idempotencyKey)));
    }

    @PostMapping("/content/{targetType}/{targetId}/hide")
    @PreAuthorize("hasAuthority('community:content:moderate')")
    public Result<Void> hideContent(
        @PathVariable String targetType,
        @PathVariable Long targetId,
        @Valid @RequestBody CommunityRequests.ModerationAction request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        governanceService.moderate(targetType, targetId, false, request, idempotencyKey);
        return Result.ok();
    }

    @PostMapping("/content/{targetType}/{targetId}/restore")
    @PreAuthorize("hasAuthority('community:content:restore')")
    public Result<Void> restoreContent(
        @PathVariable String targetType,
        @PathVariable Long targetId,
        @Valid @RequestBody CommunityRequests.ModerationAction request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        governanceService.moderate(targetType, targetId, true, request, idempotencyKey);
        return Result.ok();
    }

    @GetMapping("/audit/events")
    @PreAuthorize("hasAuthority('community:audit:view')")
    public Result<CursorPage<CommunityResponses.AdminEvent>> events(
        @Valid CommunityRequests.AdminEventQuery query) {
        return Result.ok(mapPage(governanceService.events(query), CommunityResponses::adminEvent));
    }

    private <S, T> CursorPage<T> mapPage(CursorPage<S> source, Function<S, T> converter) {
        return CursorPage.of(source.records().stream().map(converter).toList(),
            source.nextCursor(), source.hasMore());
    }
}
