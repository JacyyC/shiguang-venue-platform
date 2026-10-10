package com.example.venue.community.activity.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.community.activity.dto.ActivityRequests;
import com.example.venue.community.activity.dto.ActivityResponses;
import com.example.venue.community.activity.entity.CommunityActivity;
import com.example.venue.community.activity.entity.CommunityActivityParticipation;
import com.example.venue.community.activity.entity.CommunityCompanionRequest;
import com.example.venue.community.activity.mapper.CommunityActivityMapper;
import com.example.venue.community.activity.mapper.CommunityActivityParticipationMapper;
import com.example.venue.community.activity.mapper.CommunityCompanionRequestMapper;
import com.example.venue.community.dto.CursorPage;
import com.example.venue.community.service.CommunityAccessService;
import com.example.venue.community.service.CommunityTextPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CommunityActivityService {
    private static final Set<String> ACTIVITY_TYPES = Set.of(
        "STUDY", "SPORTS", "BOARD_GAME", "WORKSHOP", "OTHER");
    private static final Set<String> OPEN_STATUSES = Set.of("PUBLISHED", "FULL");
    private static final int LINKED_COMPANION_SYNC_BATCH_SIZE = 50;

    private final CommunityActivityMapper activityMapper;
    private final CommunityCompanionRequestMapper companionMapper;
    private final CommunityActivityParticipationMapper participationMapper;
    private final CommunityAccessService accessService;
    private final CommunityTextPolicy textPolicy;
    private final ActivityLocationPolicy locationPolicy;
    private final ActivityCommandService commandService;
    private final ActivityStateEventService eventService;
    private final ActivityNotificationService notificationService;

    public CommunityActivityService(CommunityActivityMapper activityMapper,
                                    CommunityCompanionRequestMapper companionMapper,
                                    CommunityActivityParticipationMapper participationMapper,
                                    CommunityAccessService accessService,
                                    CommunityTextPolicy textPolicy,
                                    ActivityLocationPolicy locationPolicy,
                                    ActivityCommandService commandService,
                                    ActivityStateEventService eventService,
                                    ActivityNotificationService notificationService) {
        this.activityMapper = activityMapper;
        this.companionMapper = companionMapper;
        this.participationMapper = participationMapper;
        this.accessService = accessService;
        this.textPolicy = textPolicy;
        this.locationPolicy = locationPolicy;
        this.commandService = commandService;
        this.eventService = eventService;
        this.notificationService = notificationService;
    }

    @Transactional
    public CommunityActivity createActivity(ActivityRequests.CreateActivity request, String rawKey) {
        CurrentUser actor = accessService.requireCurrentUser();
        if (request == null) {
            throw BusinessException.badRequest("活动请求不能为空");
        }
        String type = activityType(request.activityType);
        String title = textPolicy.required(request.title, 80, "活动标题");
        String description = textPolicy.optional(request.description, 1000, "活动说明");
        validateFutureRange(request.startTime, request.endTime);
        validateCapacity(request.capacityTotal, 1000);
        accessService.requireActivityCreationContext(request.groupId, request.referencePostId, actor.id());
        String hash = textPolicy.fingerprint(request.groupId, request.referencePostId, type, title, description,
            request.startTime, request.endTime, request.capacityTotal, locationFingerprint(request.location));
        ActivityCommandService.Claim claim = commandService.claim(
            actor.id(), "ACTIVITY_CREATE", rawKey, hash);
        if (claim.replayed()) {
            return requireReplayActivity(claim);
        }
        ActivityLocationPolicy.LocationDecision location = locationPolicy.resolve(
            request.location, actor.id(), request.startTime, request.endTime, request.capacityTotal);
        LocalDateTime now = LocalDateTime.now();
        CommunityActivity activity = new CommunityActivity();
        activity.activityNo = "ACT-" + compactUuid();
        activity.groupId = request.groupId;
        activity.referencePostId = request.referencePostId;
        activity.organizerUserId = actor.id();
        activity.activityType = type;
        activity.title = title;
        activity.description = description;
        activity.startTime = request.startTime;
        activity.endTime = request.endTime;
        applyLocation(activity, location);
        activity.capacityTotal = request.capacityTotal;
        activity.participantCount = 1;
        activity.status = "DRAFT";
        activity.versionNo = 0L;
        activity.createTime = now;
        activity.updateTime = now;
        activityMapper.insert(activity);
        CommunityActivityParticipation organizer = newParticipation(
            "ACTIVITY", activity.id, actor.id(), "ORGANIZER", null, now);
        participationMapper.insert(organizer);
        eventService.record("ACTIVITY_CREATED", "ACTIVITY", activity.id, actor.id(), "USER",
            null, "DRAFT", null, claim.command().idempotencyKey,
            Map.of("groupId", activity.groupId, "capacity", activity.capacityTotal,
                "locationCommitment", activity.locationCommitmentStatus));
        commandService.complete(claim, "ACTIVITY", activity.id, activity.status);
        return activity;
    }

    @Transactional(readOnly = true)
    public CommunityActivity getActivity(Long activityId) {
        CurrentUser actor = accessService.requireCurrentUser();
        CommunityActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw accessService.hiddenResource();
        }
        requireActivityVisible(activity, actor.id());
        return activity;
    }

    @Transactional(readOnly = true)
    public CursorPage<CommunityActivity> discoverActivities(ActivityRequests.DiscoveryQuery query) {
        CurrentUser actor = accessService.requireCurrentUser();
        ActivityRequests.DiscoveryQuery normalized = query == null ? new ActivityRequests.DiscoveryQuery() : query;
        int limit = normalizedLimit(normalized.limit);
        String keyword = normalizeSearchKeyword(normalized.keyword);
        String type = optionalActivityType(normalized.activityType);
        String areaCode = normalizeAreaCode(normalized.areaCode);
        List<CommunityActivity> rows = activityMapper.discover(
            actor.id(), keyword, type, normalized.fromTime, normalized.toTime, normalized.venueId,
            areaCode, normalized.cursorId, LocalDateTime.now(), limit + 1);
        return page(rows, limit, row -> row.id);
    }

    @Transactional
    public CommunityActivity publishActivity(Long activityId, String rawKey) {
        CurrentUser actor = accessService.requireCurrentUser();
        CommunityActivity activity = requireOwnedActivityForUpdate(activityId, actor.id());
        ActivityCommandService.Claim claim = commandService.claim(actor.id(), "ACTIVITY_PUBLISH", rawKey,
            textPolicy.fingerprint(activityId, "PUBLISH"));
        if (claim.replayed()) {
            return requireReplayActivity(claim);
        }
        if (!"DRAFT".equals(activity.status)) {
            throw BusinessException.conflict("活动当前状态不能发布");
        }
        validateFutureRange(activity.startTime, activity.endTime);
        String from = activity.status;
        activity.status = activity.participantCount.equals(activity.capacityTotal) ? "FULL" : "PUBLISHED";
        activity.publishedAt = LocalDateTime.now();
        activity.versionNo++;
        activity.updateTime = activity.publishedAt;
        activityMapper.updateById(activity);
        eventService.record("ACTIVITY_PUBLISHED", "ACTIVITY", activity.id, actor.id(), "ORGANIZER",
            from, activity.status, null, claim.command().idempotencyKey, Map.of("version", activity.versionNo));
        commandService.complete(claim, "ACTIVITY", activity.id, activity.status);
        return activity;
    }

    @Transactional
    public CommunityActivity updateActivityLocation(Long activityId,
                                                    ActivityRequests.UpdateLocation request,
                                                    String rawKey) {
        CurrentUser actor = accessService.requireCurrentUser();
        CommunityActivity activity = requireOwnedActivityForUpdate(activityId, actor.id());
        if (request == null || request.versionNo == null) {
            throw BusinessException.badRequest("地点更新必须携带版本");
        }
        ActivityCommandService.Claim claim = commandService.claim(actor.id(), "ACTIVITY_LOCATION_UPDATE", rawKey,
            textPolicy.fingerprint(activityId, request.versionNo, locationFingerprint(request.location)));
        if (claim.replayed()) {
            return requireReplayActivity(claim);
        }
        ActivityLocationPolicy.LocationDecision location = locationPolicy.resolve(
            request.location, actor.id(), activity.startTime, activity.endTime, activity.capacityTotal);
        if (!Set.of("DRAFT", "PUBLISHED", "FULL").contains(activity.status)) {
            throw BusinessException.conflict("活动当前状态不能更新地点");
        }
        if (!request.versionNo.equals(activity.versionNo)) {
            throw BusinessException.conflict("活动已被其他请求修改，请刷新后重试");
        }
        String fromCommitment = activity.locationCommitmentStatus;
        long expectedVersion = activity.versionNo;
        long nextCommitmentVersion = (activity.commitmentVersion == null ? 0L : activity.commitmentVersion) + 1L;
        applyLocation(activity, location);
        activity.versionNo++;
        activity.commitmentVersion = nextCommitmentVersion;
        activity.updateTime = LocalDateTime.now();
        if (activityMapper.updateLocation(activity.id, expectedVersion, activity.versionNo,
            activity.locationType, activity.venueId, activity.spaceId, activity.areaCode,
            activity.reservationId, activity.locationCommitmentStatus, activity.commitmentVersion,
            activity.updateTime) != 1) {
            throw BusinessException.conflict("活动已被其他请求修改，请刷新后重试");
        }
        synchronizeLinkedCompanionLocations(activity, actor.id());
        eventService.record("ACTIVITY_LOCATION_UPDATED", "LOCATION_COMMITMENT", activity.id,
            actor.id(), "ORGANIZER", fromCommitment, activity.locationCommitmentStatus, null,
            claim.command().idempotencyKey,
            Map.of("activityId", activity.id, "commitmentVersion", activity.commitmentVersion));
        commandService.complete(claim, "ACTIVITY", activity.id, activity.status);
        return activity;
    }

    @Transactional
    public CommunityActivity closeActivity(Long activityId, String reasonCode, String rawKey) {
        return finishActivity(activityId, reasonCode, rawKey, false);
    }

    @Transactional
    public CommunityActivity cancelActivity(Long activityId, String reasonCode, String rawKey) {
        return finishActivity(activityId, reasonCode, rawKey, true);
    }

    @Transactional
    public CommunityCompanionRequest createCompanion(ActivityRequests.CreateCompanionRequest request,
                                                      String rawKey) {
        CurrentUser actor = accessService.requireCurrentUser();
        if (request == null) {
            throw BusinessException.badRequest("找搭子请求不能为空");
        }
        String title = textPolicy.required(request.title, 80, "找搭子标题");
        String description = textPolicy.optional(request.description, 1000, "找搭子说明");
        validateCapacity(request.capacityTotal, 100);
        accessService.requireActivityCreationContext(request.groupId, request.referencePostId, actor.id());
        CommunityActivity linked = null;
        String type;
        LocalDateTime start;
        LocalDateTime end;
        ActivityLocationPolicy.LocationDecision location;
        if (request.activityId != null) {
            linked = activityMapper.selectByIdForUpdate(request.activityId);
            if (linked == null || !OPEN_STATUSES.contains(linked.status) || !request.groupId.equals(linked.groupId)) {
                throw accessService.hiddenResource();
            }
            requireActivityVisible(linked, actor.id());
            CommunityActivityParticipation member = participationMapper.find("ACTIVITY", linked.id, actor.id());
            if (member == null || !"ACTIVE".equals(member.status)) {
                throw BusinessException.forbidden("只有活动参与者可发布关联找搭子需求");
            }
            if (request.capacityTotal > linked.capacityTotal) {
                throw BusinessException.badRequest("关联需求人数不能超过活动人数");
            }
            type = linked.activityType;
            start = linked.startTime;
            end = linked.endTime;
            location = locationOf(linked);
        } else {
            type = activityType(request.activityType);
            start = request.windowStart;
            end = request.windowEnd;
            validateFutureRange(start, end);
            location = locationPolicy.resolve(request.location, actor.id(), start, end, request.capacityTotal);
        }
        String hash = textPolicy.fingerprint(request.groupId, request.activityId, request.referencePostId,
            type, title, description, start, end, request.capacityTotal, location);
        ActivityCommandService.Claim claim = commandService.claim(
            actor.id(), "COMPANION_CREATE", rawKey, hash);
        if (claim.replayed()) {
            return requireReplayCompanion(claim);
        }
        LocalDateTime now = LocalDateTime.now();
        CommunityCompanionRequest companion = new CommunityCompanionRequest();
        companion.requestNo = "CPR-" + compactUuid();
        companion.groupId = request.groupId;
        companion.activityId = linked == null ? null : linked.id;
        companion.referencePostId = request.referencePostId;
        companion.ownerUserId = actor.id();
        companion.activityType = type;
        companion.title = title;
        companion.description = description;
        companion.windowStart = start;
        companion.windowEnd = end;
        applyLocation(companion, location);
        companion.capacityTotal = request.capacityTotal;
        companion.participantCount = 1;
        companion.status = "DRAFT";
        companion.versionNo = 0L;
        companion.createTime = now;
        companion.updateTime = now;
        companionMapper.insert(companion);
        participationMapper.insert(newParticipation(
            "COMPANION_REQUEST", companion.id, actor.id(), "OWNER", null, now));
        eventService.record("COMPANION_REQUEST_CREATED", "COMPANION_REQUEST", companion.id,
            actor.id(), "USER", null, "DRAFT", null, claim.command().idempotencyKey,
            Map.of("groupId", companion.groupId, "linkedActivity", companion.activityId != null,
                "capacity", companion.capacityTotal, "locationCommitment", companion.locationCommitmentStatus));
        commandService.complete(claim, "COMPANION_REQUEST", companion.id, companion.status);
        return companion;
    }

    @Transactional(readOnly = true)
    public CommunityCompanionRequest getCompanion(Long requestId) {
        CurrentUser actor = accessService.requireCurrentUser();
        CommunityCompanionRequest request = companionMapper.selectById(requestId);
        if (request == null) {
            throw accessService.hiddenResource();
        }
        requireCompanionVisible(request, actor.id());
        return request;
    }

    @Transactional(readOnly = true)
    public CursorPage<CommunityCompanionRequest> discoverCompanions(ActivityRequests.DiscoveryQuery query) {
        CurrentUser actor = accessService.requireCurrentUser();
        ActivityRequests.DiscoveryQuery normalized = query == null ? new ActivityRequests.DiscoveryQuery() : query;
        int limit = normalizedLimit(normalized.limit);
        List<CommunityCompanionRequest> rows = companionMapper.discover(
            actor.id(), null, normalizeSearchKeyword(normalized.keyword),
            optionalActivityType(normalized.activityType), normalized.fromTime, normalized.toTime,
            normalized.venueId, null, normalizeAreaCode(normalized.areaCode), normalized.cursorId,
            LocalDateTime.now(), limit + 1);
        return page(rows, limit, row -> row.id);
    }

    @Transactional
    public CommunityCompanionRequest publishCompanion(Long requestId, String rawKey) {
        CurrentUser actor = accessService.requireCurrentUser();
        CommunityCompanionRequest request = requireOwnedCompanionForUpdate(requestId, actor.id());
        ActivityCommandService.Claim claim = commandService.claim(actor.id(), "COMPANION_PUBLISH", rawKey,
            textPolicy.fingerprint(requestId, "PUBLISH"));
        if (claim.replayed()) {
            return requireReplayCompanion(claim);
        }
        if (!"DRAFT".equals(request.status)) {
            throw BusinessException.conflict("找搭子需求当前状态不能发布");
        }
        validateFutureRange(request.windowStart, request.windowEnd);
        request.status = "PUBLISHED";
        request.publishedAt = LocalDateTime.now();
        request.versionNo++;
        request.updateTime = request.publishedAt;
        companionMapper.updateById(request);
        eventService.record("COMPANION_REQUEST_PUBLISHED", "COMPANION_REQUEST", request.id,
            actor.id(), "ORGANIZER", "DRAFT", request.status, null, claim.command().idempotencyKey,
            Map.of("version", request.versionNo));
        commandService.complete(claim, "COMPANION_REQUEST", request.id, request.status);
        return request;
    }

    @Transactional
    public CommunityCompanionRequest updateCompanionLocation(Long requestId,
                                                             ActivityRequests.UpdateLocation update,
                                                             String rawKey) {
        CurrentUser actor = accessService.requireCurrentUser();
        CommunityCompanionRequest request = requireOwnedCompanionForUpdate(requestId, actor.id());
        if (request.activityId != null) {
            throw BusinessException.badRequest("活动关联需求的地点由活动承诺决定");
        }
        if (update == null || update.versionNo == null) {
            throw BusinessException.badRequest("地点更新必须携带版本");
        }
        ActivityCommandService.Claim claim = commandService.claim(actor.id(), "COMPANION_LOCATION_UPDATE",
            rawKey, textPolicy.fingerprint(requestId, update.versionNo, locationFingerprint(update.location)));
        if (claim.replayed()) {
            return requireReplayCompanion(claim);
        }
        ActivityLocationPolicy.LocationDecision location = locationPolicy.resolve(
            update.location, actor.id(), request.windowStart, request.windowEnd, request.capacityTotal);
        if (!Set.of("DRAFT", "PUBLISHED", "FULL").contains(request.status)) {
            throw BusinessException.conflict("找搭子需求当前状态不能更新地点");
        }
        if (!update.versionNo.equals(request.versionNo)) {
            throw BusinessException.conflict("找搭子需求已被其他请求修改，请刷新后重试");
        }
        String from = request.locationCommitmentStatus;
        long expectedVersion = request.versionNo;
        long nextCommitmentVersion = (request.commitmentVersion == null ? 0L : request.commitmentVersion) + 1L;
        applyLocation(request, location);
        request.versionNo++;
        request.commitmentVersion = nextCommitmentVersion;
        request.updateTime = LocalDateTime.now();
        if (companionMapper.updateLocation(request.id, expectedVersion, request.versionNo,
            request.locationType, request.venueId, request.spaceId, request.areaCode,
            request.reservationId, request.locationCommitmentStatus, request.commitmentVersion,
            request.updateTime) != 1) {
            throw BusinessException.conflict("找搭子需求已被其他请求修改，请刷新后重试");
        }
        eventService.record("COMPANION_LOCATION_UPDATED", "LOCATION_COMMITMENT", request.id,
            actor.id(), "ORGANIZER", from, request.locationCommitmentStatus, null,
            claim.command().idempotencyKey,
            Map.of("requestId", request.id, "commitmentVersion", request.commitmentVersion));
        commandService.complete(claim, "COMPANION_REQUEST", request.id, request.status);
        return request;
    }

    @Transactional
    public CommunityCompanionRequest closeCompanion(Long requestId, String reasonCode, String rawKey) {
        return finishCompanion(requestId, reasonCode, rawKey, false);
    }

    @Transactional
    public CommunityCompanionRequest cancelCompanion(Long requestId, String reasonCode, String rawKey) {
        return finishCompanion(requestId, reasonCode, rawKey, true);
    }

    @Transactional
    public int expireDue(int requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit, 1), 100);
        LocalDateTime now = LocalDateTime.now();
        int changed = 0;
        for (Long id : activityMapper.findDueExpiryIds(now, limit)) {
            CommunityActivity activity = activityMapper.selectByIdForUpdate(id);
            if (activity != null && OPEN_STATUSES.contains(activity.status) && !activity.endTime.isAfter(now)) {
                List<CommunityActivityParticipation> participants = participationMapper.listActive(
                    "ACTIVITY", activity.id, null, activity.capacityTotal + 1);
                String from = activity.status;
                activity.status = "EXPIRED";
                activity.expiredAt = now;
                activity.versionNo++;
                activity.updateTime = now;
                activityMapper.updateById(activity);
                eventService.record("ACTIVITY_EXPIRED", "ACTIVITY", activity.id, null, "SYSTEM",
                    from, "EXPIRED", "END_TIME_REACHED", "ACTIVITY_EXPIRE:" + activity.id + ":" + activity.endTime,
                    Map.of("version", activity.versionNo));
                notifyParticipants(participants, null, "ACTIVITY_EXPIRED",
                    "活动已结束", "你参与的活动已到结束时间。", "ACTIVITY", activity.id,
                    "ACTIVITY_EXPIRE:" + activity.id + ":" + activity.versionNo);
                changed++;
            }
        }
        for (Long id : companionMapper.findDueExpiryIds(now, limit)) {
            CommunityCompanionRequest request = companionMapper.selectByIdForUpdate(id);
            if (request != null && OPEN_STATUSES.contains(request.status) && !request.windowEnd.isAfter(now)) {
                List<CommunityActivityParticipation> participants = participationMapper.listActive(
                    "COMPANION_REQUEST", request.id, null, request.capacityTotal + 1);
                String from = request.status;
                request.status = "EXPIRED";
                request.expiredAt = now;
                request.versionNo++;
                request.updateTime = now;
                companionMapper.updateById(request);
                eventService.record("COMPANION_REQUEST_EXPIRED", "COMPANION_REQUEST", request.id,
                    null, "SYSTEM", from, "EXPIRED", "END_TIME_REACHED",
                    "COMPANION_EXPIRE:" + request.id + ":" + request.windowEnd,
                    Map.of("version", request.versionNo));
                notifyParticipants(participants, null, "COMPANION_REQUEST_EXPIRED",
                    "找搭子需求已结束", "你参与的找搭子需求已到结束时间。", "COMPANION_REQUEST", request.id,
                    "COMPANION_EXPIRE:" + request.id + ":" + request.versionNo);
                changed++;
            }
        }
        return changed;
    }

    private CommunityActivity finishActivity(Long activityId, String rawReason, String rawKey, boolean cancel) {
        CurrentUser actor = accessService.requireCurrentUser();
        CommunityActivity activity = requireOwnedActivityForUpdate(activityId, actor.id());
        String commandType = cancel ? "ACTIVITY_CANCEL" : "ACTIVITY_CLOSE";
        String reason = reasonCode(rawReason, cancel ? "ORGANIZER_CANCELED" : "ORGANIZER_CLOSED");
        ActivityCommandService.Claim claim = commandService.claim(actor.id(), commandType, rawKey,
            textPolicy.fingerprint(activityId, reason));
        if (claim.replayed()) {
            return requireReplayActivity(claim);
        }
        if (cancel ? !Set.of("DRAFT", "PUBLISHED", "FULL").contains(activity.status)
            : !OPEN_STATUSES.contains(activity.status)) {
            throw BusinessException.conflict("活动当前状态不能" + (cancel ? "取消" : "关闭"));
        }
        List<CommunityActivityParticipation> participants = participationMapper.listActive(
            "ACTIVITY", activity.id, null, activity.capacityTotal + 1);
        String from = activity.status;
        LocalDateTime now = LocalDateTime.now();
        activity.status = cancel ? "CANCELED" : "CLOSED";
        activity.canceledAt = cancel ? now : null;
        activity.closedAt = cancel ? null : now;
        if (cancel) {
            participationMapper.cancelAll("ACTIVITY", activity.id, now);
            activity.participantCount = 0;
        }
        activity.versionNo++;
        activity.updateTime = now;
        activityMapper.updateById(activity);
        eventService.record(cancel ? "ACTIVITY_CANCELED" : "ACTIVITY_CLOSED", "ACTIVITY", activity.id,
            actor.id(), "ORGANIZER", from, activity.status, reason, claim.command().idempotencyKey,
            Map.of("participantCount", participants.size(), "version", activity.versionNo));
        notifyParticipants(participants, actor.id(), cancel ? "ACTIVITY_CANCELED" : "ACTIVITY_CLOSED",
            (cancel ? "活动已取消" : "活动已关闭"),
            (cancel ? "你参与的活动已由组织者取消。" : "你参与的活动已停止接受新申请。"),
            "ACTIVITY", activity.id, commandType + ":" + activity.id + ":" + activity.versionNo);
        commandService.complete(claim, "ACTIVITY", activity.id, activity.status);
        return activity;
    }

    private CommunityCompanionRequest finishCompanion(Long requestId, String rawReason,
                                                       String rawKey, boolean cancel) {
        CurrentUser actor = accessService.requireCurrentUser();
        CommunityCompanionRequest request = requireOwnedCompanionForUpdate(requestId, actor.id());
        String commandType = cancel ? "COMPANION_CANCEL" : "COMPANION_CLOSE";
        String reason = reasonCode(rawReason, cancel ? "OWNER_CANCELED" : "OWNER_CLOSED");
        ActivityCommandService.Claim claim = commandService.claim(actor.id(), commandType, rawKey,
            textPolicy.fingerprint(requestId, reason));
        if (claim.replayed()) {
            return requireReplayCompanion(claim);
        }
        if (cancel ? !Set.of("DRAFT", "PUBLISHED", "FULL").contains(request.status)
            : !OPEN_STATUSES.contains(request.status)) {
            throw BusinessException.conflict("找搭子需求当前状态不能" + (cancel ? "取消" : "关闭"));
        }
        List<CommunityActivityParticipation> participants = participationMapper.listActive(
            "COMPANION_REQUEST", request.id, null, request.capacityTotal + 1);
        String from = request.status;
        LocalDateTime now = LocalDateTime.now();
        request.status = cancel ? "CANCELED" : "CLOSED";
        request.canceledAt = cancel ? now : null;
        request.closedAt = cancel ? null : now;
        if (cancel) {
            participationMapper.cancelAll("COMPANION_REQUEST", request.id, now);
            request.participantCount = 0;
        }
        request.versionNo++;
        request.updateTime = now;
        companionMapper.updateById(request);
        eventService.record(cancel ? "COMPANION_REQUEST_CANCELED" : "COMPANION_REQUEST_CLOSED",
            "COMPANION_REQUEST", request.id, actor.id(), "ORGANIZER", from, request.status, reason,
            claim.command().idempotencyKey,
            Map.of("participantCount", participants.size(), "version", request.versionNo));
        notifyParticipants(participants, actor.id(),
            cancel ? "COMPANION_REQUEST_CANCELED" : "COMPANION_REQUEST_CLOSED",
            cancel ? "找搭子需求已取消" : "找搭子需求已关闭",
            cancel ? "你参与的找搭子需求已由发起人取消。" : "该找搭子需求已停止接受新申请。",
            "COMPANION_REQUEST", request.id, commandType + ":" + request.id + ":" + request.versionNo);
        commandService.complete(claim, "COMPANION_REQUEST", request.id, request.status);
        return request;
    }

    private CommunityActivity requireOwnedActivityForUpdate(Long id, Long actorUserId) {
        CommunityActivity activity = activityMapper.selectByIdForUpdate(id);
        if (activity == null || !actorUserId.equals(activity.organizerUserId)) {
            throw accessService.hiddenResource();
        }
        accessService.requireActivityContext(activity.groupId, activity.referencePostId, actorUserId);
        return activity;
    }

    /**
     * A linked companion request derives its place from the activity and intentionally has no
     * independent location-update endpoint. Keep the derived commitment in the same transaction
     * so a successful activity update cannot leave an open request advertising the old place.
     */
    private void synchronizeLinkedCompanionLocations(CommunityActivity activity, Long actorUserId) {
        long afterId = 0L;
        while (true) {
            List<CommunityCompanionRequest> linked = companionMapper.findOpenByActivityForUpdate(
                activity.id, afterId, LINKED_COMPANION_SYNC_BATCH_SIZE);
            if (linked.isEmpty()) {
                return;
            }
            for (CommunityCompanionRequest request : linked) {
                long expectedVersion = request.versionNo;
                long nextVersion = expectedVersion + 1L;
                long nextCommitmentVersion = (request.commitmentVersion == null ? 0L : request.commitmentVersion) + 1L;
                String fromCommitment = request.locationCommitmentStatus;
                LocalDateTime now = LocalDateTime.now();
                if (companionMapper.updateLocation(request.id, expectedVersion, nextVersion,
                    activity.locationType, activity.venueId, activity.spaceId, activity.areaCode,
                    activity.reservationId, activity.locationCommitmentStatus, nextCommitmentVersion,
                    now) != 1) {
                    throw BusinessException.conflict("关联找搭子需求已被其他请求修改，请刷新后重试");
                }
                eventService.record("COMPANION_LOCATION_SYNCHRONIZED", "LOCATION_COMMITMENT", request.id,
                    actorUserId, "ORGANIZER", fromCommitment, activity.locationCommitmentStatus,
                    "LINKED_ACTIVITY_LOCATION_UPDATED",
                    "ACTIVITY_LOCATION_SYNC:" + activity.id + ":" + activity.commitmentVersion + ":"
                        + request.id + ":" + nextCommitmentVersion,
                    Map.of("activityId", activity.id,
                        "activityCommitmentVersion", activity.commitmentVersion,
                        "companionCommitmentVersion", nextCommitmentVersion,
                        "locationCommitmentStatus", activity.locationCommitmentStatus));
                notifyLinkedCompanionParticipants(request, actorUserId, nextCommitmentVersion);
            }
            afterId = linked.get(linked.size() - 1).id;
        }
    }

    private void notifyLinkedCompanionParticipants(CommunityCompanionRequest request,
                                                   Long actorUserId,
                                                   long commitmentVersion) {
        List<CommunityActivityParticipation> participants = participationMapper.listActive(
            "COMPANION_REQUEST", request.id, null, request.capacityTotal + 1);
        for (CommunityActivityParticipation participant : participants) {
            if (!participant.userId.equals(actorUserId)) {
                notificationService.enqueue("COMPANION_LOCATION_SYNCHRONIZED",
                    "COMPANION_LOCATION_SYNCHRONIZED:" + request.id + ":" + commitmentVersion + ":"
                        + participant.userId,
                    participant.userId, "关联活动地点已更新",
                    "关联活动的地点承诺已更新，请查看最新地点状态。",
                    "COMPANION_REQUEST", request.id);
            }
        }
    }

    private CommunityCompanionRequest requireOwnedCompanionForUpdate(Long id, Long actorUserId) {
        CommunityCompanionRequest request = companionMapper.selectByIdForUpdate(id);
        if (request == null || !actorUserId.equals(request.ownerUserId)) {
            throw accessService.hiddenResource();
        }
        accessService.requireActivityContext(request.groupId, request.referencePostId, actorUserId);
        return request;
    }

    private void requireActivityVisible(CommunityActivity activity, Long viewerId) {
        accessService.requireActivityContext(activity.groupId, activity.referencePostId, viewerId);
        accessService.requireNoBlock(viewerId, activity.organizerUserId);
        if (!OPEN_STATUSES.contains(activity.status) && !activity.organizerUserId.equals(viewerId)) {
            CommunityActivityParticipation participation = participationMapper.find("ACTIVITY", activity.id, viewerId);
            if (participation == null || !"ACTIVE".equals(participation.status)) {
                throw accessService.hiddenResource();
            }
        }
    }

    private void requireCompanionVisible(CommunityCompanionRequest request, Long viewerId) {
        accessService.requireActivityContext(request.groupId, request.referencePostId, viewerId);
        accessService.requireNoBlock(viewerId, request.ownerUserId);
        if (request.activityId != null) {
            CommunityActivity linked = activityMapper.selectById(request.activityId);
            if (linked == null || !OPEN_STATUSES.contains(linked.status)) {
                throw accessService.hiddenResource();
            }
            requireActivityVisible(linked, viewerId);
        }
        if (!OPEN_STATUSES.contains(request.status) && !request.ownerUserId.equals(viewerId)) {
            CommunityActivityParticipation participation = participationMapper.find(
                "COMPANION_REQUEST", request.id, viewerId);
            if (participation == null || !"ACTIVE".equals(participation.status)) {
                throw accessService.hiddenResource();
            }
        }
    }

    private CommunityActivity requireReplayActivity(ActivityCommandService.Claim claim) {
        if (!"ACTIVITY".equals(claim.command().targetType)) {
            throw BusinessException.conflict("幂等请求结果类型不一致");
        }
        CommunityActivity activity = activityMapper.selectById(claim.command().targetId);
        if (activity == null) {
            throw BusinessException.conflict("幂等请求结果不存在");
        }
        return activity;
    }

    private CommunityCompanionRequest requireReplayCompanion(ActivityCommandService.Claim claim) {
        if (!"COMPANION_REQUEST".equals(claim.command().targetType)) {
            throw BusinessException.conflict("幂等请求结果类型不一致");
        }
        CommunityCompanionRequest request = companionMapper.selectById(claim.command().targetId);
        if (request == null) {
            throw BusinessException.conflict("幂等请求结果不存在");
        }
        return request;
    }

    private CommunityActivityParticipation newParticipation(String targetType, Long targetId, Long userId,
                                                            String role, Long applicationId,
                                                            LocalDateTime now) {
        CommunityActivityParticipation participation = new CommunityActivityParticipation();
        participation.participationNo = "PAR-" + compactUuid();
        participation.targetType = targetType;
        participation.targetId = targetId;
        participation.userId = userId;
        participation.roleType = role;
        participation.status = "ACTIVE";
        participation.applicationId = applicationId;
        participation.joinedAt = now;
        participation.versionNo = 0L;
        participation.createTime = now;
        participation.updateTime = now;
        return participation;
    }

    private void applyLocation(CommunityActivity target, ActivityLocationPolicy.LocationDecision source) {
        target.locationType = source.type();
        target.venueId = source.venueId();
        target.spaceId = source.spaceId();
        target.areaCode = source.areaCode();
        target.reservationId = source.reservationId();
        target.locationCommitmentStatus = source.commitmentStatus();
        target.commitmentVersion = source.commitmentVersion();
    }

    private void applyLocation(CommunityCompanionRequest target,
                               ActivityLocationPolicy.LocationDecision source) {
        target.locationType = source.type();
        target.venueId = source.venueId();
        target.spaceId = source.spaceId();
        target.areaCode = source.areaCode();
        target.reservationId = source.reservationId();
        target.locationCommitmentStatus = source.commitmentStatus();
        target.commitmentVersion = source.commitmentVersion();
    }

    private ActivityLocationPolicy.LocationDecision locationOf(CommunityActivity source) {
        return new ActivityLocationPolicy.LocationDecision(
            source.locationType, source.venueId, source.spaceId, source.areaCode, source.reservationId,
            source.locationCommitmentStatus, source.commitmentVersion);
    }

    private void notifyParticipants(List<CommunityActivityParticipation> participants,
                                    Long excludedUserId,
                                    String eventType,
                                    String title,
                                    String content,
                                    String businessType,
                                    Long businessId,
                                    String eventKeyPrefix) {
        for (CommunityActivityParticipation participant : participants) {
            if (!participant.userId.equals(excludedUserId)) {
                notificationService.enqueue(eventType, eventKeyPrefix + ":" + participant.userId,
                    participant.userId, title, content, businessType, businessId);
            }
        }
    }

    private String activityType(String raw) {
        String value = raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
        if (value == null || !ACTIVITY_TYPES.contains(value)) {
            throw BusinessException.badRequest("活动类型不合法");
        }
        return value;
    }

    private String optionalActivityType(String raw) {
        return raw == null || raw.isBlank() ? null : activityType(raw);
    }

    private String normalizeSearchKeyword(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > 80) {
            throw BusinessException.badRequest("搜索关键词长度不能超过80个字符");
        }
        return value;
    }

    private String normalizeAreaCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9_]{2,64}")) {
            throw BusinessException.badRequest("校区区域代码不合法");
        }
        return value;
    }

    private void validateFutureRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw BusinessException.badRequest("开始时间必须早于结束时间");
        }
        if (!end.isAfter(LocalDateTime.now())) {
            throw BusinessException.conflict("结束时间已过期");
        }
    }

    private void validateCapacity(int value, int max) {
        if (value < 2 || value > max) {
            throw BusinessException.badRequest("人数上限必须在 2 到 " + max + " 之间");
        }
    }

    private String reasonCode(String raw, String fallback) {
        String value = raw == null || raw.isBlank() ? fallback : raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9_]{1,64}")) {
            throw BusinessException.badRequest("原因代码不合法");
        }
        return value;
    }

    private Object locationFingerprint(ActivityRequests.Location location) {
        return location == null ? null : List.of(
            String.valueOf(location.type), String.valueOf(location.venueId), String.valueOf(location.spaceId),
            String.valueOf(location.areaCode), String.valueOf(location.reservationId));
    }

    private int normalizedLimit(int limit) {
        return limit <= 0 ? 20 : Math.min(limit, 50);
    }

    private <T> CursorPage<T> page(List<T> rows, int limit, IdExtractor<T> idExtractor) {
        boolean hasMore = rows.size() > limit;
        List<T> records = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        Long next = hasMore && !records.isEmpty() ? idExtractor.id(records.get(records.size() - 1)) : null;
        return CursorPage.of(records, next, hasMore);
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @FunctionalInterface
    private interface IdExtractor<T> {
        Long id(T value);
    }
}
