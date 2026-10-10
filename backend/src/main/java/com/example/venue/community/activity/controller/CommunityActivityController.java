package com.example.venue.community.activity.controller;

import com.example.venue.common.Result;
import com.example.venue.community.activity.dto.ActivityRequests;
import com.example.venue.community.activity.dto.ActivityResponses;
import com.example.venue.community.activity.service.ActivityParticipationService;
import com.example.venue.community.activity.service.CompanionMatchingService;
import com.example.venue.community.activity.service.CommunityActivityService;
import com.example.venue.community.activity.service.CommunityDirectoryService;
import com.example.venue.community.dto.CursorPage;
import com.example.venue.community.entity.CommunityGroup;
import com.example.venue.community.mapper.CommunityGroupMapper;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Validated
@RestController
@RequestMapping("/api/community")
@PreAuthorize("hasAuthority('community:access')")
public class CommunityActivityController {
    private final CommunityActivityService activityService;
    private final ActivityParticipationService participationService;
    private final CompanionMatchingService matchingService;
    private final CommunityDirectoryService directoryService;
    private final CommunityGroupMapper groupMapper;
    private final SysUserMapper userMapper;

    public CommunityActivityController(CommunityActivityService activityService,
                                       ActivityParticipationService participationService,
                                       CompanionMatchingService matchingService,
                                       CommunityDirectoryService directoryService,
                                       CommunityGroupMapper groupMapper,
                                       SysUserMapper userMapper) {
        this.activityService = activityService;
        this.participationService = participationService;
        this.matchingService = matchingService;
        this.directoryService = directoryService;
        this.groupMapper = groupMapper;
        this.userMapper = userMapper;
    }

    /** V02 日期目录：活动与找搭子统一时间序有界分页，计数为同口径真数。 */
    @GetMapping("/directory")
    public Result<ActivityResponses.DirectoryPage> directory(
        @Valid @ModelAttribute ActivityRequests.DirectoryQuery query
    ) {
        return Result.ok(directoryService.directory(query));
    }

    /** 本人发起（含草稿），按所有者权威读取。 */
    @GetMapping("/my/hosted")
    public Result<ActivityResponses.DirectoryPage> myHosted(
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return Result.ok(directoryService.myHosted(cursor, limit));
    }

    /** 发起者管理页指标：待处理申请数。 */
    @GetMapping("/application-metrics")
    public Result<ActivityResponses.ApplicationMetrics> applicationMetrics(
        @RequestParam String targetType,
        @RequestParam Long targetId
    ) {
        return Result.ok(directoryService.applicationMetrics(targetType, targetId));
    }

    @GetMapping("/activities")
    public Result<CursorPage<ActivityResponses.Activity>> activities(
        @Valid @ModelAttribute ActivityRequests.DiscoveryQuery query
    ) {
        return Result.ok(mapActivities(activityService.discoverActivities(query)));
    }

    @GetMapping("/activities/{activityId}")
    public Result<ActivityResponses.Activity> activity(@PathVariable Long activityId) {
        ActivityResponses.Activity detail = ActivityResponses.activity(activityService.getActivity(activityId));
        enrichActivitySources(List.of(detail));
        return Result.ok(detail);
    }

    @PostMapping("/activities")
    public Result<ActivityResponses.Activity> createActivity(
        @Valid @RequestBody ActivityRequests.CreateActivity request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.activity(activityService.createActivity(request, idempotencyKey)));
    }

    @PostMapping("/activities/{activityId}/publish")
    public Result<ActivityResponses.Activity> publishActivity(
        @PathVariable Long activityId,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.activity(activityService.publishActivity(activityId, idempotencyKey)));
    }

    @PutMapping("/activities/{activityId}/location")
    public Result<ActivityResponses.Activity> updateActivityLocation(
        @PathVariable Long activityId,
        @Valid @RequestBody ActivityRequests.UpdateLocation request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.activity(
            activityService.updateActivityLocation(activityId, request, idempotencyKey)));
    }

    @PostMapping("/activities/{activityId}/close")
    public Result<ActivityResponses.Activity> closeActivity(
        @PathVariable Long activityId,
        @RequestBody(required = false) ActivityRequests.Decision request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.activity(activityService.closeActivity(
            activityId, request == null ? null : request.reasonCode, idempotencyKey)));
    }

    @PostMapping("/activities/{activityId}/cancel")
    public Result<ActivityResponses.Activity> cancelActivity(
        @PathVariable Long activityId,
        @RequestBody(required = false) ActivityRequests.Decision request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.activity(activityService.cancelActivity(
            activityId, request == null ? null : request.reasonCode, idempotencyKey)));
    }

    @PostMapping("/activities/{activityId}/applications")
    public Result<ActivityResponses.Application> applyActivity(
        @PathVariable Long activityId,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.application(
            participationService.apply("ACTIVITY", activityId, idempotencyKey)));
    }

    @GetMapping("/activities/{activityId}/applications")
    public Result<CursorPage<ActivityResponses.Application>> activityApplications(
        @PathVariable Long activityId,
        @Valid @ModelAttribute ActivityRequests.ApplicationQuery query
    ) {
        return Result.ok(mapApplications(
            participationService.applications("ACTIVITY", activityId, query)));
    }

    @GetMapping("/activities/{activityId}/participants")
    public Result<CursorPage<ActivityResponses.Participation>> activityParticipants(
        @PathVariable Long activityId,
        @RequestParam(required = false) Long cursorId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return Result.ok(mapParticipations(
            participationService.participants("ACTIVITY", activityId, cursorId, limit)));
    }

    @GetMapping("/activities/{activityId}/my-state")
    public Result<ActivityResponses.ViewerState> activityViewerState(@PathVariable Long activityId) {
        return Result.ok(participationService.viewerState("ACTIVITY", activityId));
    }

    @PostMapping("/activities/{activityId}/leave")
    public Result<ActivityResponses.Participation> leaveActivity(
        @PathVariable Long activityId,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.participation(
            participationService.leave("ACTIVITY", activityId, idempotencyKey)));
    }

    @GetMapping("/companion-requests")
    public Result<CursorPage<ActivityResponses.CompanionRequest>> companionRequests(
        @Valid @ModelAttribute ActivityRequests.DiscoveryQuery query
    ) {
        return Result.ok(mapCompanions(activityService.discoverCompanions(query)));
    }

    @GetMapping("/companion-requests/{requestId}")
    public Result<ActivityResponses.CompanionRequest> companionRequest(@PathVariable Long requestId) {
        ActivityResponses.CompanionRequest detail = ActivityResponses.companion(activityService.getCompanion(requestId));
        enrichCompanionSources(List.of(detail));
        return Result.ok(detail);
    }

    @PostMapping("/companion-requests")
    public Result<ActivityResponses.CompanionRequest> createCompanion(
        @Valid @RequestBody ActivityRequests.CreateCompanionRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.companion(
            activityService.createCompanion(request, idempotencyKey)));
    }

    @PostMapping("/companion-requests/{requestId}/publish")
    public Result<ActivityResponses.CompanionRequest> publishCompanion(
        @PathVariable Long requestId,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.companion(
            activityService.publishCompanion(requestId, idempotencyKey)));
    }

    @PutMapping("/companion-requests/{requestId}/location")
    public Result<ActivityResponses.CompanionRequest> updateCompanionLocation(
        @PathVariable Long requestId,
        @Valid @RequestBody ActivityRequests.UpdateLocation request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.companion(
            activityService.updateCompanionLocation(requestId, request, idempotencyKey)));
    }

    @PostMapping("/companion-requests/{requestId}/close")
    public Result<ActivityResponses.CompanionRequest> closeCompanion(
        @PathVariable Long requestId,
        @RequestBody(required = false) ActivityRequests.Decision request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.companion(activityService.closeCompanion(
            requestId, request == null ? null : request.reasonCode, idempotencyKey)));
    }

    @PostMapping("/companion-requests/{requestId}/cancel")
    public Result<ActivityResponses.CompanionRequest> cancelCompanion(
        @PathVariable Long requestId,
        @RequestBody(required = false) ActivityRequests.Decision request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.companion(activityService.cancelCompanion(
            requestId, request == null ? null : request.reasonCode, idempotencyKey)));
    }

    @PostMapping("/companion-requests/{requestId}/applications")
    public Result<ActivityResponses.Application> applyCompanion(
        @PathVariable Long requestId,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.application(
            participationService.apply("COMPANION_REQUEST", requestId, idempotencyKey)));
    }

    @GetMapping("/companion-requests/{requestId}/applications")
    public Result<CursorPage<ActivityResponses.Application>> companionApplications(
        @PathVariable Long requestId,
        @Valid @ModelAttribute ActivityRequests.ApplicationQuery query
    ) {
        return Result.ok(mapApplications(
            participationService.applications("COMPANION_REQUEST", requestId, query)));
    }

    @GetMapping("/companion-requests/{requestId}/participants")
    public Result<CursorPage<ActivityResponses.Participation>> companionParticipants(
        @PathVariable Long requestId,
        @RequestParam(required = false) Long cursorId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return Result.ok(mapParticipations(
            participationService.participants("COMPANION_REQUEST", requestId, cursorId, limit)));
    }

    @GetMapping("/companion-requests/{requestId}/my-state")
    public Result<ActivityResponses.ViewerState> companionViewerState(@PathVariable Long requestId) {
        return Result.ok(participationService.viewerState("COMPANION_REQUEST", requestId));
    }

    @PostMapping("/companion-requests/{requestId}/leave")
    public Result<ActivityResponses.Participation> leaveCompanion(
        @PathVariable Long requestId,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.participation(
            participationService.leave("COMPANION_REQUEST", requestId, idempotencyKey)));
    }

    @PostMapping("/companion-requests/{requestId}/matches/refresh")
    public Result<ActivityResponses.MatchPage> refreshMatches(
        @PathVariable Long requestId,
        @Valid @RequestBody(required = false) ActivityRequests.RefreshMatches request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        int limit = request == null ? 20 : request.limit;
        return Result.ok(matchingService.refresh(requestId, limit, idempotencyKey));
    }

    @GetMapping("/companion-requests/{requestId}/matches")
    public Result<ActivityResponses.MatchPage> matches(
        @PathVariable Long requestId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return Result.ok(matchingService.matches(requestId, limit));
    }

    @PostMapping("/applications/{applicationId}/accept")
    public Result<ActivityResponses.Application> accept(
        @PathVariable Long applicationId,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.application(
            participationService.accept(applicationId, idempotencyKey)));
    }

    @PostMapping("/applications/{applicationId}/reject")
    public Result<ActivityResponses.Application> reject(
        @PathVariable Long applicationId,
        @Valid @RequestBody(required = false) ActivityRequests.Decision request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.application(
            participationService.reject(applicationId, request, idempotencyKey)));
    }

    @PostMapping("/applications/{applicationId}/withdraw")
    public Result<ActivityResponses.Application> withdraw(
        @PathVariable Long applicationId,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(ActivityResponses.application(
            participationService.withdraw(applicationId, idempotencyKey)));
    }

    private CursorPage<ActivityResponses.Activity> mapActivities(CursorPage<com.example.venue.community.activity.entity.CommunityActivity> page) {
        CursorPage<ActivityResponses.Activity> mapped = CursorPage.of(
            page.records().stream().map(ActivityResponses::activity).toList(),
            page.nextCursor(), page.hasMore());
        enrichActivitySources(mapped.records());
        return mapped;
    }

    private CursorPage<ActivityResponses.CompanionRequest> mapCompanions(CursorPage<com.example.venue.community.activity.entity.CommunityCompanionRequest> page) {
        CursorPage<ActivityResponses.CompanionRequest> mapped = CursorPage.of(
            page.records().stream().map(ActivityResponses::companion).toList(),
            page.nextCursor(), page.hasMore());
        enrichCompanionSources(mapped.records());
        return mapped;
    }

    /** 卡片“来源”展示：小组名 + 发起者昵称，批量 join，缺失时留空由前端回落默认文案。 */
    private void enrichActivitySources(List<ActivityResponses.Activity> records) {
        if (records == null || records.isEmpty()) return;
        Set<Long> groupIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (ActivityResponses.Activity record : records) {
            if (record.groupId != null) groupIds.add(record.groupId);
            if (record.organizerUserId != null) userIds.add(record.organizerUserId);
        }
        Map<Long, String> groupNames = groupNames(groupIds);
        Map<Long, String> nicknames = nicknames(userIds);
        for (ActivityResponses.Activity record : records) {
            record.groupName = groupNames.get(record.groupId);
            record.organizerName = nicknames.get(record.organizerUserId);
        }
    }

    private void enrichCompanionSources(List<ActivityResponses.CompanionRequest> records) {
        if (records == null || records.isEmpty()) return;
        Set<Long> groupIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (ActivityResponses.CompanionRequest record : records) {
            if (record.groupId != null) groupIds.add(record.groupId);
            if (record.ownerUserId != null) userIds.add(record.ownerUserId);
        }
        Map<Long, String> groupNames = groupNames(groupIds);
        Map<Long, String> nicknames = nicknames(userIds);
        for (ActivityResponses.CompanionRequest record : records) {
            record.groupName = groupNames.get(record.groupId);
            record.organizerName = nicknames.get(record.ownerUserId);
        }
    }

    private Map<Long, String> groupNames(Set<Long> groupIds) {
        Map<Long, String> names = new HashMap<>();
        if (groupIds.isEmpty()) return names;
        for (CommunityGroup group : groupMapper.selectBatchIds(groupIds)) {
            names.put(group.id, group.name);
        }
        return names;
    }

    private Map<Long, String> nicknames(Set<Long> userIds) {
        Map<Long, String> nicknames = new HashMap<>();
        if (userIds.isEmpty()) return nicknames;
        for (SysUser user : userMapper.selectBatchIds(userIds)) {
            nicknames.put(user.id, user.nickname == null || user.nickname.isBlank() ? user.username : user.nickname);
        }
        return nicknames;
    }

    private CursorPage<ActivityResponses.Application> mapApplications(CursorPage<com.example.venue.community.activity.entity.CommunityActivityApplication> page) {
        return CursorPage.of(page.records().stream().map(ActivityResponses::application).toList(),
            page.nextCursor(), page.hasMore());
    }

    private CursorPage<ActivityResponses.Participation> mapParticipations(CursorPage<com.example.venue.community.activity.entity.CommunityActivityParticipation> page) {
        return CursorPage.of(page.records().stream().map(ActivityResponses::participation).toList(),
            page.nextCursor(), page.hasMore());
    }
}
