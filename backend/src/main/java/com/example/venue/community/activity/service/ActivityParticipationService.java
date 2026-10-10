package com.example.venue.community.activity.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.community.activity.dto.ActivityRequests;
import com.example.venue.community.activity.dto.ActivityResponses;
import com.example.venue.community.activity.entity.CommunityActivity;
import com.example.venue.community.activity.entity.CommunityActivityApplication;
import com.example.venue.community.activity.entity.CommunityActivityParticipation;
import com.example.venue.community.activity.entity.CommunityCompanionRequest;
import com.example.venue.community.activity.mapper.CommunityActivityApplicationMapper;
import com.example.venue.community.activity.mapper.CommunityActivityMapper;
import com.example.venue.community.activity.mapper.CommunityActivityParticipationMapper;
import com.example.venue.community.activity.mapper.CommunityCompanionRequestMapper;
import com.example.venue.community.dto.CursorPage;
import com.example.venue.community.service.CommunityAccessService;
import com.example.venue.community.service.CommunityRateLimitService;
import com.example.venue.community.service.CommunityTextPolicy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ActivityParticipationService {
    private final CommunityActivityMapper activityMapper;
    private final CommunityCompanionRequestMapper companionMapper;
    private final CommunityActivityApplicationMapper applicationMapper;
    private final CommunityActivityParticipationMapper participationMapper;
    private final CommunityAccessService accessService;
    private final CommunityTextPolicy textPolicy;
    private final CommunityRateLimitService rateLimitService;
    private final ActivityCommandService commandService;
    private final ActivityStateEventService eventService;
    private final ActivityNotificationService notificationService;

    public ActivityParticipationService(CommunityActivityMapper activityMapper,
                                        CommunityCompanionRequestMapper companionMapper,
                                        CommunityActivityApplicationMapper applicationMapper,
                                        CommunityActivityParticipationMapper participationMapper,
                                        CommunityAccessService accessService,
                                        CommunityTextPolicy textPolicy,
                                        CommunityRateLimitService rateLimitService,
                                        ActivityCommandService commandService,
                                        ActivityStateEventService eventService,
                                        ActivityNotificationService notificationService) {
        this.activityMapper = activityMapper;
        this.companionMapper = companionMapper;
        this.applicationMapper = applicationMapper;
        this.participationMapper = participationMapper;
        this.accessService = accessService;
        this.textPolicy = textPolicy;
        this.rateLimitService = rateLimitService;
        this.commandService = commandService;
        this.eventService = eventService;
        this.notificationService = notificationService;
    }

    @Transactional
    public CommunityActivityApplication apply(String rawTargetType, Long targetId, String rawKey) {
        CurrentUser actor = accessService.requireCurrentUser();
        Target snapshot = readTarget(rawTargetType, targetId);
        lockTargetContext(snapshot, actor.id());
        accessService.requireActiveMemberForUpdate(snapshot.groupId(), actor.id());
        Target target = lockTarget(rawTargetType, targetId);
        requireVisible(target, actor.id());
        accessService.requireNoBlock(actor.id(), target.ownerUserId());
        if (actor.id().equals(target.ownerUserId())) {
            throw BusinessException.conflict("发起人不能申请自己的活动或需求");
        }
        ActivityCommandService.Claim claim = commandService.claim(actor.id(), "ACTIVITY_APPLY", rawKey,
            textPolicy.fingerprint(target.type(), target.id(), "APPLY"));
        if (claim.replayed()) {
            return replayApplication(claim);
        }
        requireAcceptingApplications(target);
        CommunityActivityParticipation participation = participationMapper.findForUpdate(
            target.type(), target.id(), actor.id());
        if (participation != null && "ACTIVE".equals(participation.status)) {
            throw BusinessException.conflict("已经是有效参与者");
        }
        CommunityActivityApplication application = applicationMapper.findForUpdate(
            target.type(), target.id(), actor.id());
        if (application != null && Set.of("PENDING", "ACCEPTED").contains(application.status)) {
            throw BusinessException.conflict("申请已存在或已接受");
        }
        rateLimitService.check(actor.id(), CommunityRateLimitService.Action.APPLY);
        LocalDateTime now = LocalDateTime.now();
        String from = application == null ? null : application.status;
        if (application == null) {
            application = new CommunityActivityApplication();
            application.applicationNo = "APP-" + compactUuid();
            application.targetType = target.type();
            application.targetId = target.id();
            application.applicantUserId = actor.id();
            application.versionNo = 0L;
            application.createTime = now;
        }
        application.status = "PENDING";
        application.reasonCode = null;
        application.appliedAt = now;
        application.decidedBy = null;
        application.decidedAt = null;
        application.withdrawnAt = null;
        application.updateTime = now;
        if (application.id == null) {
            applicationMapper.insert(application);
        } else {
            long expectedVersion = application.versionNo;
            application.versionNo++;
            if (applicationMapper.resetToPending(application.id, expectedVersion, application.versionNo,
                application.appliedAt, application.updateTime) != 1) {
                throw BusinessException.conflict("申请已被其他请求修改，请刷新后重试");
            }
        }
        eventService.record("APPLICATION_SUBMITTED", "APPLICATION", application.id, actor.id(), "USER",
            from, "PENDING", null, claim.command().idempotencyKey,
            Map.of("targetType", target.type(), "targetId", target.id()));
        notificationService.enqueue("COMMUNITY_APPLICATION_SUBMITTED",
            "COMMUNITY_APPLICATION_SUBMITTED:" + application.id + ":" + application.versionNo,
            target.ownerUserId(), "收到新的参与申请", "你的活动或找搭子需求收到新的参与申请。",
            target.type(), target.id());
        commandService.complete(claim, "APPLICATION", application.id, application.status);
        return application;
    }

    @Transactional
    public CommunityActivityApplication accept(Long applicationId, String rawKey) {
        return decide(applicationId, null, rawKey, true);
    }

    @Transactional
    public CommunityActivityApplication reject(Long applicationId,
                                               ActivityRequests.Decision request,
                                               String rawKey) {
        return decide(applicationId, request, rawKey, false);
    }

    @Transactional
    public CommunityActivityApplication withdraw(Long applicationId, String rawKey) {
        CurrentUser actor = accessService.requireCurrentUser();
        CommunityActivityApplication snapshot = applicationMapper.selectById(applicationId);
        if (snapshot == null || !actor.id().equals(snapshot.applicantUserId)) {
            throw accessService.hiddenResource();
        }
        Target target = lockTarget(snapshot.targetType, snapshot.targetId);
        CommunityActivityApplication application = applicationMapper.selectByIdForUpdate(applicationId);
        ActivityCommandService.Claim claim = commandService.claim(actor.id(), "APPLICATION_WITHDRAW", rawKey,
            textPolicy.fingerprint(applicationId, "WITHDRAW"));
        if (claim.replayed()) {
            return replayApplication(claim);
        }
        if (application == null || !"PENDING".equals(application.status)) {
            throw BusinessException.conflict("申请当前状态不能撤回");
        }
        application.status = "WITHDRAWN";
        application.withdrawnAt = LocalDateTime.now();
        application.versionNo++;
        application.updateTime = application.withdrawnAt;
        applicationMapper.updateById(application);
        eventService.record("APPLICATION_WITHDRAWN", "APPLICATION", application.id, actor.id(), "USER",
            "PENDING", "WITHDRAWN", null, claim.command().idempotencyKey,
            Map.of("targetType", target.type(), "targetId", target.id()));
        notificationService.enqueue("COMMUNITY_APPLICATION_WITHDRAWN",
            "COMMUNITY_APPLICATION_WITHDRAWN:" + application.id + ":" + application.versionNo,
            target.ownerUserId(), "参与申请已撤回", "一项活动或找搭子参与申请已由申请人撤回。",
            target.type(), target.id());
        commandService.complete(claim, "APPLICATION", application.id, application.status);
        return application;
    }

    @Transactional
    public CommunityActivityParticipation leave(String rawTargetType, Long targetId, String rawKey) {
        CurrentUser actor = accessService.requireCurrentUser();
        Target target = lockTarget(rawTargetType, targetId);
        ActivityCommandService.Claim claim = commandService.claim(actor.id(), "PARTICIPATION_LEAVE", rawKey,
            textPolicy.fingerprint(target.type(), target.id(), "LEAVE"));
        if (claim.replayed()) {
            return replayParticipation(claim);
        }
        CommunityActivityParticipation participation = participationMapper.findForUpdate(
            target.type(), target.id(), actor.id());
        if (participation == null || !"ACTIVE".equals(participation.status)) {
            throw BusinessException.conflict("当前不是有效参与者");
        }
        if (Set.of("ORGANIZER", "OWNER").contains(participation.roleType)) {
            throw BusinessException.badRequest("发起人不能通过参与退出接口离开");
        }
        LocalDateTime now = LocalDateTime.now();
        participation.status = "LEFT";
        participation.leftAt = now;
        participation.versionNo++;
        participation.updateTime = now;
        participationMapper.updateById(participation);
        decrementTarget(target, now);
        eventService.record("PARTICIPATION_LEFT", "PARTICIPATION", participation.id, actor.id(), "USER",
            "ACTIVE", "LEFT", null, claim.command().idempotencyKey,
            Map.of("targetType", target.type(), "targetId", target.id()));
        notificationService.enqueue("COMMUNITY_PARTICIPATION_LEFT",
            "COMMUNITY_PARTICIPATION_LEFT:" + participation.id + ":" + participation.versionNo,
            target.ownerUserId(), "参与者已退出", "一名参与者已退出你的活动或找搭子需求。",
            target.type(), target.id());
        commandService.complete(claim, "PARTICIPATION", participation.id, participation.status);
        return participation;
    }

    @Transactional(readOnly = true)
    public CursorPage<CommunityActivityApplication> applications(String rawTargetType,
                                                                 Long targetId,
                                                                 ActivityRequests.ApplicationQuery query) {
        CurrentUser actor = accessService.requireCurrentUser();
        Target target = readTarget(rawTargetType, targetId);
        if (!actor.id().equals(target.ownerUserId())) {
            throw accessService.hiddenResource();
        }
        requireVisible(target, actor.id());
        ActivityRequests.ApplicationQuery normalized = query == null
            ? new ActivityRequests.ApplicationQuery() : query;
        String status = applicationStatus(normalized.status);
        int limit = normalized.limit <= 0 ? 20 : Math.min(normalized.limit, 50);
        List<CommunityActivityApplication> rows = applicationMapper.listByTarget(
            target.type(), target.id(), target.groupId(), target.ownerUserId(), status,
            normalized.cursorId, limit + 1);
        return page(rows, limit, row -> row.id);
    }

    @Transactional(readOnly = true)
    public CursorPage<CommunityActivityParticipation> participants(String rawTargetType,
                                                                  Long targetId,
                                                                  Long cursorId,
                                                                  int requestedLimit) {
        CurrentUser actor = accessService.requireCurrentUser();
        Target target = readTarget(rawTargetType, targetId);
        requireVisible(target, actor.id());
        int limit = requestedLimit <= 0 ? 20 : Math.min(requestedLimit, 50);
        List<CommunityActivityParticipation> rows = participationMapper.listActiveVisibleTo(
            target.type(), target.id(), actor.id(), cursorId, limit + 1);
        return page(rows, limit, row -> row.id);
    }

    @Transactional(readOnly = true)
    public ActivityResponses.ViewerState viewerState(String rawTargetType, Long targetId) {
        CurrentUser actor = accessService.requireCurrentUser();
        Target target = readTarget(rawTargetType, targetId);
        requireVisible(target, actor.id());
        return ActivityResponses.viewerState(
            applicationMapper.find(target.type(), target.id(), actor.id()),
            participationMapper.find(target.type(), target.id(), actor.id())
        );
    }

    private CommunityActivityApplication decide(Long applicationId,
                                                ActivityRequests.Decision request,
                                                String rawKey,
                                                boolean accept) {
        CurrentUser actor = accessService.requireCurrentUser();
        CommunityActivityApplication snapshot = applicationMapper.selectById(applicationId);
        if (snapshot == null) {
            throw accessService.hiddenResource();
        }
        Target targetSnapshot = readTarget(snapshot.targetType, snapshot.targetId);
        if (!actor.id().equals(targetSnapshot.ownerUserId())) {
            throw accessService.hiddenResource();
        }
        lockTargetContext(targetSnapshot, actor.id());
        Target target = lockTarget(snapshot.targetType, snapshot.targetId);
        if (!actor.id().equals(target.ownerUserId())) {
            throw accessService.hiddenResource();
        }
        CommunityActivityApplication application = applicationMapper.selectByIdForUpdate(applicationId);
        String reason = accept ? null : reasonCode(request == null ? null : request.reasonCode, "NOT_SELECTED");
        String commandType = accept ? "APPLICATION_ACCEPT" : "APPLICATION_REJECT";
        ActivityCommandService.Claim claim = commandService.claim(actor.id(), commandType, rawKey,
            textPolicy.fingerprint(applicationId, accept, reason));
        if (claim.replayed()) {
            return replayApplication(claim);
        }
        if (application == null || !"PENDING".equals(application.status)) {
            throw BusinessException.conflict("申请已处理");
        }
        LocalDateTime now = LocalDateTime.now();
        if (accept) {
            accessService.requireActiveMemberForUpdate(target.groupId(), application.applicantUserId);
            accessService.requireNoBlock(actor.id(), application.applicantUserId);
            requireAcceptingApplications(target);
            if (target.participantCount() >= target.capacityTotal()) {
                throw BusinessException.conflict("名额已满");
            }
            CommunityActivityParticipation participation = newParticipation(target, application, now);
            try {
                participationMapper.insert(participation);
            } catch (DuplicateKeyException duplicate) {
                // Insert intentions for independent target/user keys can coexist. Looking up a missing
                // key with FOR UPDATE first would gap-lock neighboring keys and deadlock batch accepts.
                participation = participationMapper.findForUpdate(
                    target.type(), target.id(), application.applicantUserId);
                if (participation == null) {
                    throw duplicate;
                }
                if ("ACTIVE".equals(participation.status)) {
                    throw BusinessException.conflict("申请人已经是有效参与者");
                }
                long expectedVersion = participation.versionNo;
                participation.status = "ACTIVE";
                participation.applicationId = application.id;
                participation.joinedAt = now;
                participation.leftAt = null;
                participation.versionNo++;
                participation.updateTime = now;
                if (participationMapper.reactivate(participation.id, expectedVersion, participation.versionNo,
                    participation.applicationId, participation.joinedAt, participation.updateTime) != 1) {
                    throw BusinessException.conflict("参与关系已被其他请求修改，请刷新后重试");
                }
            }
            incrementTarget(target, now);
            eventService.record("PARTICIPATION_JOINED", "PARTICIPATION", participation.id,
                application.applicantUserId, "ORGANIZER", null, "ACTIVE", null,
                claim.command().idempotencyKey,
                Map.of("targetType", target.type(), "targetId", target.id(),
                    "participantCount", target.participantCount() + 1));
        }
        application.status = accept ? "ACCEPTED" : "REJECTED";
        application.reasonCode = reason;
        application.decidedBy = actor.id();
        application.decidedAt = now;
        application.versionNo++;
        application.updateTime = now;
        applicationMapper.updateById(application);
        eventService.record(accept ? "APPLICATION_ACCEPTED" : "APPLICATION_REJECTED",
            "APPLICATION", application.id, actor.id(), "ORGANIZER", "PENDING", application.status,
            reason, claim.command().idempotencyKey,
            Map.of("targetType", target.type(), "targetId", target.id()));
        notificationService.enqueue(
            accept ? "COMMUNITY_APPLICATION_ACCEPTED" : "COMMUNITY_APPLICATION_REJECTED",
            (accept ? "COMMUNITY_APPLICATION_ACCEPTED:" : "COMMUNITY_APPLICATION_REJECTED:")
                + application.id + ":" + application.versionNo,
            application.applicantUserId,
            accept ? "参与申请已接受" : "参与申请未通过",
            accept ? "你的参与申请已被接受。" : "你的参与申请未被接受，请查看申请状态。",
            target.type(), target.id());
        commandService.complete(claim, "APPLICATION", application.id, application.status);
        return application;
    }

    private Target lockTarget(String rawType, Long id) {
        String type = targetType(rawType);
        if ("ACTIVITY".equals(type)) {
            CommunityActivity activity = activityMapper.selectByIdForUpdate(id);
            if (activity == null) {
                throw accessService.hiddenResource();
            }
            return Target.activity(activity);
        }
        CommunityCompanionRequest request = companionMapper.selectByIdForUpdate(id);
        if (request == null) {
            throw accessService.hiddenResource();
        }
        return Target.companion(request);
    }

    private Target readTarget(String rawType, Long id) {
        String type = targetType(rawType);
        if ("ACTIVITY".equals(type)) {
            CommunityActivity activity = activityMapper.selectById(id);
            if (activity == null) {
                throw accessService.hiddenResource();
            }
            return Target.activity(activity);
        }
        CommunityCompanionRequest request = companionMapper.selectById(id);
        if (request == null) {
            throw accessService.hiddenResource();
        }
        return Target.companion(request);
    }

    private void requireVisible(Target target, Long viewerId) {
        accessService.requireActivityContext(target.groupId(), target.referencePostId(), viewerId);
        accessService.requireNoBlock(viewerId, target.ownerUserId());
        requireLinkedActivityVisible(target, viewerId, false);
        if (!Set.of("PUBLISHED", "FULL").contains(target.status())
            && !viewerId.equals(target.ownerUserId())) {
            CommunityActivityParticipation participation = participationMapper.find(
                target.type(), target.id(), viewerId);
            if (participation == null || !"ACTIVE".equals(participation.status)) {
                throw accessService.hiddenResource();
            }
        }
    }

    private void lockTargetContext(Target target, Long viewerId) {
        accessService.requireActivityContextForUpdate(target.groupId(), target.referencePostId(), viewerId);
        requireLinkedActivityVisible(target, viewerId, true);
    }

    private void requireLinkedActivityVisible(Target target, Long viewerId, boolean forUpdate) {
        if (target.linkedActivityId() == null) {
            return;
        }
        CommunityActivity linked = forUpdate
            ? activityMapper.selectByIdForUpdate(target.linkedActivityId())
            : activityMapper.selectById(target.linkedActivityId());
        if (linked == null || !Set.of("PUBLISHED", "FULL").contains(linked.status)
            || !target.groupId().equals(linked.groupId) || !linked.endTime.isAfter(LocalDateTime.now())) {
            throw accessService.hiddenResource();
        }
        if (forUpdate) {
            accessService.requireActivityContextForUpdate(linked.groupId, linked.referencePostId, viewerId);
        } else {
            accessService.requireActivityContext(linked.groupId, linked.referencePostId, viewerId);
        }
        accessService.requireNoBlock(viewerId, linked.organizerUserId);
    }

    private void requireAcceptingApplications(Target target) {
        if (!"PUBLISHED".equals(target.status())) {
            if ("FULL".equals(target.status())) {
                throw BusinessException.conflict("名额已满");
            }
            throw BusinessException.conflict("活动或需求当前不接受申请");
        }
        if (!target.endTime().isAfter(LocalDateTime.now())) {
            throw BusinessException.conflict("活动或需求已过期");
        }
    }

    private void incrementTarget(Target target, LocalDateTime now) {
        int count = target.participantCount() + 1;
        String status = count >= target.capacityTotal() ? "FULL" : "PUBLISHED";
        if (target.activity() != null) {
            CommunityActivity activity = target.activity();
            activity.participantCount = count;
            activity.status = status;
            activity.versionNo++;
            activity.updateTime = now;
            activityMapper.updateById(activity);
        } else {
            CommunityCompanionRequest request = target.companion();
            request.participantCount = count;
            request.status = status;
            request.versionNo++;
            request.updateTime = now;
            companionMapper.updateById(request);
        }
    }

    private void decrementTarget(Target target, LocalDateTime now) {
        int count = Math.max(0, target.participantCount() - 1);
        String status = "FULL".equals(target.status()) && target.endTime().isAfter(now)
            ? "PUBLISHED" : target.status();
        if (target.activity() != null) {
            CommunityActivity activity = target.activity();
            activity.participantCount = count;
            activity.status = status;
            activity.versionNo++;
            activity.updateTime = now;
            activityMapper.updateById(activity);
        } else {
            CommunityCompanionRequest request = target.companion();
            request.participantCount = count;
            request.status = status;
            request.versionNo++;
            request.updateTime = now;
            companionMapper.updateById(request);
        }
    }

    private CommunityActivityParticipation newParticipation(Target target,
                                                            CommunityActivityApplication application,
                                                            LocalDateTime now) {
        CommunityActivityParticipation participation = new CommunityActivityParticipation();
        participation.participationNo = "PAR-" + compactUuid();
        participation.targetType = target.type();
        participation.targetId = target.id();
        participation.userId = application.applicantUserId;
        participation.roleType = "PARTICIPANT";
        participation.status = "ACTIVE";
        participation.applicationId = application.id;
        participation.joinedAt = now;
        participation.versionNo = 0L;
        participation.createTime = now;
        participation.updateTime = now;
        return participation;
    }

    private CommunityActivityApplication replayApplication(ActivityCommandService.Claim claim) {
        if (!"APPLICATION".equals(claim.command().targetType)) {
            throw BusinessException.conflict("幂等请求结果类型不一致");
        }
        CommunityActivityApplication result = applicationMapper.selectById(claim.command().targetId);
        if (result == null) {
            throw BusinessException.conflict("幂等申请结果不存在");
        }
        return result;
    }

    private CommunityActivityParticipation replayParticipation(ActivityCommandService.Claim claim) {
        if (!"PARTICIPATION".equals(claim.command().targetType)) {
            throw BusinessException.conflict("幂等请求结果类型不一致");
        }
        CommunityActivityParticipation result = participationMapper.selectById(claim.command().targetId);
        if (result == null) {
            throw BusinessException.conflict("幂等参与结果不存在");
        }
        return result;
    }

    private String targetType(String raw) {
        String value = raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVITY", "COMPANION_REQUEST").contains(value)) {
            throw BusinessException.badRequest("申请目标类型不合法");
        }
        return value;
    }

    private String applicationStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING", "ACCEPTED", "REJECTED", "WITHDRAWN").contains(value)) {
            throw BusinessException.badRequest("申请状态不合法");
        }
        return value;
    }

    private String reasonCode(String raw, String fallback) {
        String value = raw == null || raw.isBlank() ? fallback : raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9_]{1,64}")) {
            throw BusinessException.badRequest("原因代码不合法");
        }
        return value;
    }

    private <T> CursorPage<T> page(List<T> rows, int limit, IdExtractor<T> extractor) {
        boolean hasMore = rows.size() > limit;
        List<T> records = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        Long next = hasMore && !records.isEmpty() ? extractor.id(records.get(records.size() - 1)) : null;
        return CursorPage.of(records, next, hasMore);
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record Target(String type,
                          Long id,
                          Long groupId,
                          Long referencePostId,
                          Long ownerUserId,
                          Long linkedActivityId,
                          String status,
                          int capacityTotal,
                          int participantCount,
                          LocalDateTime endTime,
                          CommunityActivity activity,
                          CommunityCompanionRequest companion) {
        private static Target activity(CommunityActivity source) {
            return new Target("ACTIVITY", source.id, source.groupId, source.referencePostId,
                source.organizerUserId, null, source.status, source.capacityTotal, source.participantCount,
                source.endTime, source, null);
        }

        private static Target companion(CommunityCompanionRequest source) {
            return new Target("COMPANION_REQUEST", source.id, source.groupId, source.referencePostId,
                source.ownerUserId, source.activityId, source.status, source.capacityTotal, source.participantCount,
                source.windowEnd, null, source);
        }
    }

    @FunctionalInterface
    private interface IdExtractor<T> {
        Long id(T value);
    }
}
