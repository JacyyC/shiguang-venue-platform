package com.example.venue.community.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.dto.CommunityRequests;
import com.example.venue.community.dto.CommunityResponses;
import com.example.venue.community.dto.CursorPage;
import com.example.venue.community.entity.CommunityComment;
import com.example.venue.community.entity.CommunityGroup;
import com.example.venue.community.entity.CommunityPost;
import com.example.venue.community.entity.CommunityReport;
import com.example.venue.community.enums.CommunityActorScope;
import com.example.venue.community.enums.CommunityContentStatus;
import com.example.venue.community.enums.CommunityGroupStatus;
import com.example.venue.community.enums.CommunityReportStatus;
import com.example.venue.community.enums.CommunitySourceType;
import com.example.venue.community.mapper.CommunityCommentMapper;
import com.example.venue.community.mapper.CommunityGovernanceEventMapper;
import com.example.venue.community.mapper.CommunityGroupMapper;
import com.example.venue.community.mapper.CommunityPostMapper;
import com.example.venue.community.mapper.CommunityReportMapper;
import com.example.venue.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CommunityGovernanceService {
    private final CommunityReportMapper reportMapper;
    private final CommunityGovernanceEventMapper eventMapper;
    private final CommunityGroupMapper groupMapper;
    private final CommunityPostMapper postMapper;
    private final CommunityCommentMapper commentMapper;
    private final CommunityContentService contentService;
    private final CommunityAccessService accessService;
    private final CommunityTextPolicy textPolicy;
    private final CommunityGovernanceEventService eventService;

    public CommunityGovernanceService(CommunityReportMapper reportMapper,
                                      CommunityGovernanceEventMapper eventMapper,
                                      CommunityGroupMapper groupMapper,
                                      CommunityPostMapper postMapper,
                                      CommunityCommentMapper commentMapper,
                                      CommunityContentService contentService,
                                      CommunityAccessService accessService,
                                      CommunityTextPolicy textPolicy,
                                      CommunityGovernanceEventService eventService) {
        this.reportMapper = reportMapper;
        this.eventMapper = eventMapper;
        this.groupMapper = groupMapper;
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.contentService = contentService;
        this.accessService = accessService;
        this.textPolicy = textPolicy;
        this.eventService = eventService;
    }

    @Transactional(readOnly = true)
    public CursorPage<CommunityQueryRows.ReportRow> reports(CommunityRequests.AdminReportQuery query) {
        accessService.requireCurrentUser();
        CommunityRequests.AdminReportQuery safe = query == null ? new CommunityRequests.AdminReportQuery() : query;
        int limit = normalizedLimit(safe.limit, 20, 100);
        String status = optionalCode(safe.status,
            List.of(CommunityReportStatus.OPEN.name(), CommunityReportStatus.IN_REVIEW.name(),
                CommunityReportStatus.RESOLVED.name(), CommunityReportStatus.DISMISSED.name()), "举报状态");
        String targetType = optionalCode(safe.targetType,
            List.of("GROUP", "POST", "COMMENT"), "举报对象类型");
        List<CommunityQueryRows.ReportRow> rows = reportMapper.listForAdmin(
            status, targetType, safe.cursorId, limit + 1);
        return page(rows, limit, row -> row.id);
    }

    @Transactional
    public CommunityResponses.AdminContent inspect(String targetType, Long targetId, Long reportId) {
        CurrentUser actor = accessService.requireCurrentUser();
        String normalizedType = requiredCode(targetType, List.of("GROUP", "POST", "COMMENT"), "内容类型");
        CommunityReport report = reportId == null ? null : requireMatchingReport(reportId, normalizedType, targetId);
        boolean canInspectWithoutReport = SecurityContext.hasAuthority("community:content:moderate")
            || SecurityContext.hasAuthority("community:content:restore");
        if (report == null && !canInspectWithoutReport) {
            throw BusinessException.forbidden("举报审核人员只能检查关联举报的内容");
        }
        CommunityResponses.AdminContent content;
        String status;
        if ("GROUP".equals(normalizedType)) {
            CommunityGroup group = groupMapper.selectById(targetId);
            if (group == null || CommunityGroupStatus.PURGED.matches(group.status)) {
                throw BusinessException.notFound("内容不存在");
            }
            content = CommunityResponses.adminContent(group);
            status = group.status;
        } else if ("POST".equals(normalizedType)) {
            CommunityPost post = postMapper.selectById(targetId);
            if (post == null || CommunityContentStatus.PURGED.matches(post.status)) {
                throw BusinessException.notFound("内容不存在");
            }
            content = CommunityResponses.adminContent(post);
            status = post.status;
        } else {
            CommunityComment comment = commentMapper.selectById(targetId);
            if (comment == null || CommunityContentStatus.PURGED.matches(comment.status)) {
                throw BusinessException.notFound("内容不存在");
            }
            content = CommunityResponses.adminContent(comment);
            status = comment.status;
        }
        record("CONTENT_INSPECTED", normalizedType, targetId, reportId, actor, status, status,
            "CONTENT_INSPECT:" + UUID.randomUUID(), "CONTENT_REVIEW",
            Map.of("reportContext", reportId != null));
        return content;
    }

    @Transactional
    public CommunityReport decideReport(Long reportId, CommunityRequests.ReportDecision request,
                                        String rawIdempotencyKey) {
        CurrentUser actor = accessService.requireCurrentUser();
        if (request == null) {
            throw BusinessException.badRequest("举报审核参数不能为空");
        }
        String key = textPolicy.idempotencyKey(rawIdempotencyKey);
        String decision = requiredCode(request.decision, List.of("TAKE", "RESOLVE", "DISMISS"), "审核动作");
        String decisionCode = textPolicy.required(request.decisionCode, 64, "审核结论代码")
            .toUpperCase(Locale.ROOT);
        String internalNote = textPolicy.optional(request.internalNote, 500, "内部备注");
        String requestHash = textPolicy.fingerprint(reportId, decision, decisionCode, internalNote);
        String eventKey = "REPORT_DECISION:" + reportId + ":" + key;
        CommunityReport report = reportMapper.selectByIdForUpdate(reportId);
        if (report == null) {
            throw BusinessException.notFound("举报记录不存在");
        }
        CommunityGovernanceEventService.RecordedRequest recordedRequest = eventService.findRecordedRequest(
            CommunitySourceType.CONTENT_OPERATOR, "REPORT_DECIDED", "REPORT", report.id, actor.id(), eventKey);
        if (recordedRequest != null && recordedRequest.recorded()) {
            if (!requestHash.equals(recordedRequest.requestHash())) {
                throw BusinessException.conflict("同一 Idempotency-Key 对应了不同举报审核请求");
            }
            return report;
        }
        LocalDateTime now = LocalDateTime.now();
        String fromStatus = report.status;
        if ("TAKE".equals(decision)) {
            if (!CommunityReportStatus.OPEN.matches(report.status)) {
                throw BusinessException.conflict("该举报当前不能进入审核");
            }
            report.status = CommunityReportStatus.IN_REVIEW.name();
            report.assignedTo = actor.id();
            report.decisionCode = null;
            report.internalNote = internalNote;
        } else {
            if (!CommunityReportStatus.any(report.status, CommunityReportStatus.OPEN,
                CommunityReportStatus.IN_REVIEW)) {
                throw BusinessException.conflict("该举报已经结案");
            }
            if (CommunityReportStatus.IN_REVIEW.matches(report.status) && report.assignedTo != null
                && !actor.id().equals(report.assignedTo)) {
                throw BusinessException.forbidden("该举报已由其他内容运营人员审核");
            }
            report.status = "RESOLVE".equals(decision)
                ? CommunityReportStatus.RESOLVED.name() : CommunityReportStatus.DISMISSED.name();
            report.assignedTo = actor.id();
            report.decisionCode = decisionCode;
            report.internalNote = internalNote;
            report.closedAt = now;
            report.reasonRetainUntil = now.plusDays(180);
            extendTargetRetention(report.targetType, report.targetId, report.reasonRetainUntil, now);
        }
        report.updateTime = now;
        reportMapper.updateById(report);
        record("REPORT_DECIDED", "REPORT", report.id, report.id, actor, fromStatus, report.status,
            eventKey, decisionCode,
            Map.of("decision", decision, "targetType", report.targetType, "targetId", report.targetId,
                "requestHash", requestHash));
        return report;
    }

    @Transactional
    public void moderate(String targetType, Long targetId, boolean restore,
                         CommunityRequests.ModerationAction request, String rawIdempotencyKey) {
        CurrentUser actor = accessService.requireCurrentUser();
        String normalizedType = requiredCode(targetType, List.of("GROUP", "POST", "COMMENT"), "内容类型");
        String key = textPolicy.scopedIdempotencyKey(
            "CONTENT_MODERATE_" + normalizedType + "_" + targetId + "_" + restore, rawIdempotencyKey);
        String reasonCode = textPolicy.required(request == null ? null : request.reasonCode, 64, "处置原因代码")
            .toUpperCase(Locale.ROOT);
        String internalNote = textPolicy.optional(request == null ? null : request.internalNote, 500, "内部备注");
        Long reportId = request == null ? null : request.reportId;
        String eventType = normalizedType + (restore ? "_RESTORED" : "_HIDDEN");
        CommunityReport report = requireModerationReport(reportId, normalizedType, targetId, actor.id());
        if (report == null && internalNote != null) {
            throw BusinessException.badRequest("内部备注必须关联本人审核中的举报");
        }
        if (report != null && internalNote != null) {
            report.internalNote = internalNote;
            report.updateTime = LocalDateTime.now();
            reportMapper.updateById(report);
        }
        String eventKey = key;
        if ("POST".equals(normalizedType)) {
            CommunityPost post = postMapper.selectByIdForUpdate(targetId);
            if (post == null) {
                throw BusinessException.notFound("内容不存在");
            }
            if (eventService.isRecorded(
                CommunitySourceType.CONTENT_OPERATOR, eventType, "POST", post.id, actor.id(), key)) {
                return;
            }
            contentService.moderatePost(post, restore, actor, CommunityActorScope.CONTENT,
                CommunitySourceType.CONTENT_OPERATOR,
                reasonCode, eventKey, reportId);
            return;
        }
        if ("COMMENT".equals(normalizedType)) {
            CommunityComment comment = commentMapper.selectByIdForUpdate(targetId);
            if (comment == null) {
                throw BusinessException.notFound("内容不存在");
            }
            if (eventService.isRecorded(
                CommunitySourceType.CONTENT_OPERATOR, eventType, "COMMENT", comment.id, actor.id(), key)) {
                return;
            }
            contentService.moderateComment(comment, restore, actor, CommunityActorScope.CONTENT,
                CommunitySourceType.CONTENT_OPERATOR,
                reasonCode, eventKey, reportId);
            return;
        }
        moderateGroup(targetId, restore, actor, reasonCode, eventKey, reportId);
    }

    @Transactional(readOnly = true)
    public CursorPage<CommunityQueryRows.EventRow> events(CommunityRequests.AdminEventQuery query) {
        accessService.requireCurrentUser();
        CommunityRequests.AdminEventQuery safe = query == null ? new CommunityRequests.AdminEventQuery() : query;
        int limit = normalizedLimit(safe.limit, 20, 100);
        String subjectType = optionalCode(safe.subjectType,
            List.of("GROUP", "MEMBER", "POST", "COMMENT", "BLOCK", "REPORT", "USER"), "治理对象类型");
        List<CommunityQueryRows.EventRow> rows = eventMapper.listForAdmin(
            subjectType, safe.subjectId, safe.reportId, safe.cursorId, limit + 1);
        return page(rows, limit, row -> row.id);
    }

    private void moderateGroup(Long groupId, boolean restore, CurrentUser actor, String reasonCode,
                               String eventKey, Long reportId) {
        CommunityGroup group = groupMapper.selectByIdForUpdate(groupId);
        if (group == null) {
            throw BusinessException.notFound("小组不存在");
        }
        String eventType = restore ? "GROUP_RESTORED" : "GROUP_HIDDEN";
        if (eventService.isRecorded(
            CommunitySourceType.CONTENT_OPERATOR, eventType, "GROUP", group.id, actor.id(), eventKey)) {
            return;
        }
        String fromStatus = group.status;
        LocalDateTime now = LocalDateTime.now();
        if (restore) {
            if (!CommunityGroupStatus.HIDDEN.matches(group.status)) {
                throw BusinessException.conflict("小组当前状态不能恢复");
            }
            group.status = CommunityGroupStatus.ACTIVE.name();
            group.hiddenAt = null;
            group.deletedAt = null;
            group.retainUntil = null;
        } else {
            if (!CommunityGroupStatus.ACTIVE.matches(group.status)) {
                throw BusinessException.conflict("小组当前状态不能隐藏");
            }
            group.status = CommunityGroupStatus.HIDDEN.name();
            group.hiddenAt = now;
            group.deletedAt = now;
            group.retainUntil = now.plusDays(180);
        }
        group.versionNo = group.versionNo + 1;
        group.updateTime = now;
        groupMapper.updateById(group);
        record(restore ? "GROUP_RESTORED" : "GROUP_HIDDEN", "GROUP", group.id, reportId,
            actor, fromStatus, group.status, eventKey, reasonCode,
            Map.of("retentionDays", restore ? 0 : 180));
    }

    private CommunityReport requireMatchingReport(Long reportId, String targetType, Long targetId) {
        if (reportId == null) {
            return null;
        }
        CommunityReport report = reportMapper.selectById(reportId);
        if (report == null || !targetType.equals(report.targetType) || !targetId.equals(report.targetId)) {
            throw BusinessException.badRequest("关联举报与处置对象不一致");
        }
        return report;
    }

    private CommunityReport requireModerationReport(Long reportId, String targetType, Long targetId,
                                                     Long actorId) {
        if (reportId == null) {
            return null;
        }
        if (!SecurityContext.hasAuthority("community:report:review")) {
            throw BusinessException.forbidden("关联举报处置需要举报审核权限");
        }
        CommunityReport report = reportMapper.selectByIdForUpdate(reportId);
        if (report == null || !targetType.equals(report.targetType) || !targetId.equals(report.targetId)) {
            throw BusinessException.badRequest("关联举报与处置对象不一致");
        }
        if (!CommunityReportStatus.IN_REVIEW.matches(report.status) || !actorId.equals(report.assignedTo)) {
            throw BusinessException.forbidden("只能关联由本人审核中的举报");
        }
        return report;
    }

    private void extendTargetRetention(String targetType, Long targetId, LocalDateTime retainUntil,
                                       LocalDateTime now) {
        if ("POST".equals(targetType)) {
            CommunityPost post = postMapper.selectByIdForUpdate(targetId);
            if (post != null && CommunityContentStatus.any(post.status,
                CommunityContentStatus.HIDDEN, CommunityContentStatus.DELETED)
                && (post.retainUntil == null || post.retainUntil.isBefore(retainUntil))) {
                post.deletedAt = post.deletedAt == null ? now : post.deletedAt;
                post.retainUntil = retainUntil;
                post.updateTime = now;
                postMapper.updateById(post);
            }
            return;
        }
        if ("COMMENT".equals(targetType)) {
            CommunityComment comment = commentMapper.selectByIdForUpdate(targetId);
            if (comment != null && CommunityContentStatus.any(comment.status,
                CommunityContentStatus.HIDDEN, CommunityContentStatus.DELETED)
                && (comment.retainUntil == null || comment.retainUntil.isBefore(retainUntil))) {
                comment.deletedAt = comment.deletedAt == null ? now : comment.deletedAt;
                comment.retainUntil = retainUntil;
                comment.updateTime = now;
                commentMapper.updateById(comment);
            }
            return;
        }
        CommunityGroup group = groupMapper.selectByIdForUpdate(targetId);
        if (group != null && CommunityGroupStatus.any(group.status,
            CommunityGroupStatus.HIDDEN, CommunityGroupStatus.DELETED)
            && (group.retainUntil == null || group.retainUntil.isBefore(retainUntil))) {
            group.deletedAt = group.deletedAt == null ? now : group.deletedAt;
            group.retainUntil = retainUntil;
            group.updateTime = now;
            groupMapper.updateById(group);
        }
    }

    private void record(String eventType, String subjectType, Long subjectId, Long reportId,
                        CurrentUser actor, String fromStatus, String toStatus,
                        String idempotencyKey, String reasonCode, Map<String, Object> metadata) {
        CommunityGovernanceEventService.EventCommand command = new CommunityGovernanceEventService.EventCommand();
        command.eventType = eventType;
        command.subjectType = subjectType;
        command.subjectId = subjectId;
        command.reportId = reportId;
        command.actorUserId = actor.id();
        command.actorScope = CommunityActorScope.CONTENT;
        command.sourceType = CommunitySourceType.CONTENT_OPERATOR;
        command.fromStatus = fromStatus;
        command.toStatus = toStatus;
        command.reasonCode = reasonCode;
        command.reasonSummary = reasonCode;
        command.resultStatus = "SUCCEEDED";
        command.idempotencyKey = idempotencyKey;
        command.metadata = metadata;
        eventService.record(command);
    }

    private String optionalCode(String raw, List<String> accepted, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return requiredCode(raw, accepted, field);
    }

    private String requiredCode(String raw, List<String> accepted, String field) {
        String value = raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
        if (value == null || !accepted.contains(value)) {
            throw BusinessException.badRequest(field + "不合法");
        }
        return value;
    }

    private int normalizedLimit(int requested, int defaultValue, int max) {
        return requested <= 0 ? defaultValue : Math.min(requested, max);
    }

    private <T> CursorPage<T> page(List<T> source, int limit, IdExtractor<T> extractor) {
        boolean hasMore = source.size() > limit;
        List<T> records = hasMore ? List.copyOf(source.subList(0, limit)) : List.copyOf(source);
        Long nextCursor = hasMore && !records.isEmpty() ? extractor.id(records.get(records.size() - 1)) : null;
        return CursorPage.of(records, nextCursor, hasMore);
    }

    @FunctionalInterface
    private interface IdExtractor<T> {
        Long id(T value);
    }
}
