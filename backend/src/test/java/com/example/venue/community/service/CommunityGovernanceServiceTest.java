package com.example.venue.community.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.ResultCode;
import com.example.venue.community.dto.CommunityRequests;
import com.example.venue.community.entity.CommunityComment;
import com.example.venue.community.entity.CommunityGroup;
import com.example.venue.community.entity.CommunityPost;
import com.example.venue.community.entity.CommunityReport;
import com.example.venue.community.enums.CommunityActorScope;
import com.example.venue.community.enums.CommunitySourceType;
import com.example.venue.community.mapper.CommunityCommentMapper;
import com.example.venue.community.mapper.CommunityGovernanceEventMapper;
import com.example.venue.community.mapper.CommunityGroupMapper;
import com.example.venue.community.mapper.CommunityPostMapper;
import com.example.venue.community.mapper.CommunityReportMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunityGovernanceServiceTest {
    @Mock
    private CommunityReportMapper reportMapper;
    @Mock
    private CommunityGovernanceEventMapper eventMapper;
    @Mock
    private CommunityGroupMapper groupMapper;
    @Mock
    private CommunityPostMapper postMapper;
    @Mock
    private CommunityCommentMapper commentMapper;
    @Mock
    private CommunityContentService contentService;
    @Mock
    private CommunityAccessService accessService;
    @Mock
    private CommunityGovernanceEventService eventService;

    private CommunityGovernanceService service;

    @BeforeEach
    void setUp() {
        service = new CommunityGovernanceService(
            reportMapper, eventMapper, groupMapper, postMapper, commentMapper,
            contentService, accessService, new CommunityTextPolicy(), eventService);
        when(accessService.requireCurrentUser()).thenReturn(operator(7L));
        authenticate("community:report:review", "community:content:moderate");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anAssignedReviewCannotBeClosedByAnotherOperator() {
        CommunityReport report = report(41L, "POST", 21L, "IN_REVIEW");
        report.assignedTo = 9L;
        when(reportMapper.selectByIdForUpdate(41L)).thenReturn(report);

        assertThatThrownBy(() -> service.decideReport(41L, decision("RESOLVE", "VIOLATION", "仅内部可见"), "review-key"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("其他内容运营人员")
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.FORBIDDEN);

        verify(reportMapper, never()).updateById(any(CommunityReport.class));
        verify(eventService, never()).record(any());
    }

    @Test
    void reportResolutionExtendsGovernedContentForOneHundredEightyDaysWithoutCopyingInternalNoteToEvent() {
        CommunityReport report = report(41L, "POST", 21L, "IN_REVIEW");
        report.assignedTo = 7L;
        CommunityPost hidden = new CommunityPost();
        hidden.id = 21L;
        hidden.groupId = 1L;
        hidden.status = "HIDDEN";
        hidden.versionNo = 1L;
        hidden.retainUntil = LocalDateTime.now().plusDays(20);
        when(reportMapper.selectByIdForUpdate(41L)).thenReturn(report);
        when(postMapper.selectByIdForUpdate(21L)).thenReturn(hidden);

        LocalDateTime before = LocalDateTime.now();
        service.decideReport(41L, decision("RESOLVE", "HARASSMENT", "举报人及处置内部备注"), "resolve-key");
        LocalDateTime after = LocalDateTime.now();

        assertThat(report.status).isEqualTo("RESOLVED");
        assertThat(report.internalNote).isEqualTo("举报人及处置内部备注");
        assertThat(report.reasonRetainUntil)
            .isBetween(before.plusDays(180).truncatedTo(ChronoUnit.MILLIS),
                after.plusDays(180).plusSeconds(1));
        assertThat(hidden.retainUntil).isEqualTo(report.reasonRetainUntil);
        verify(postMapper).updateById(hidden);

        ArgumentCaptor<CommunityGovernanceEventService.EventCommand> eventCaptor =
            ArgumentCaptor.forClass(CommunityGovernanceEventService.EventCommand.class);
        verify(eventService).record(eventCaptor.capture());
        CommunityGovernanceEventService.EventCommand event = eventCaptor.getValue();
        assertThat(event.reasonCode).isEqualTo("HARASSMENT");
        assertThat(event.reasonSummary).isEqualTo("HARASSMENT");
        assertThat(event.metadata).doesNotContainKey("internalNote");
        assertThat(event.metadata.toString()).doesNotContain("举报人及处置内部备注");
    }

    @Test
    void reportDecisionKeyReplaysOnlyTheBoundRequestAndRejectsAnotherAction() {
        CommunityReport report = report(41L, "POST", 21L, "OPEN");
        when(reportMapper.selectByIdForUpdate(41L)).thenReturn(report);
        CommunityRequests.ReportDecision take = decision("TAKE", "TRIAGE", "开始审核");
        String requestHash = new CommunityTextPolicy().fingerprint(41L, "TAKE", "TRIAGE", "开始审核");
        CommunityGovernanceEventService.RecordedRequest recorded =
            new CommunityGovernanceEventService.RecordedRequest(true, requestHash);
        when(eventService.findRecordedRequest(
            CommunitySourceType.CONTENT_OPERATOR, "REPORT_DECIDED", "REPORT", 41L, 7L,
            "REPORT_DECISION:41:shared-decision-key"))
            .thenReturn(null, recorded, recorded);

        assertThat(service.decideReport(41L, take, "shared-decision-key")).isSameAs(report);
        assertThat(service.decideReport(41L, take, "shared-decision-key")).isSameAs(report);

        assertThatThrownBy(() -> service.decideReport(
            41L, decision("RESOLVE", "VIOLATION", "完成审核"), "shared-decision-key"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不同举报审核请求")
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.CONFLICT);

        assertThat(report.status).isEqualTo("IN_REVIEW");
        assertThat(report.internalNote).isEqualTo("开始审核");
        verify(reportMapper).updateById(report);
        ArgumentCaptor<CommunityGovernanceEventService.EventCommand> eventCaptor =
            ArgumentCaptor.forClass(CommunityGovernanceEventService.EventCommand.class);
        verify(eventService).record(eventCaptor.capture());
        assertThat(eventCaptor.getValue().metadata)
            .containsEntry("requestHash", requestHash)
            .doesNotContainValue("开始审核");
    }

    @Test
    void differentDecisionKeysAdvanceTakeToResolveAndAppendBothEvents() {
        CommunityReport report = report(41L, "POST", 21L, "OPEN");
        when(reportMapper.selectByIdForUpdate(41L)).thenReturn(report);

        service.decideReport(41L, decision("TAKE", "TRIAGE", null), "take-key");
        service.decideReport(41L, decision("RESOLVE", "VIOLATION", null), "resolve-key");

        assertThat(report.status).isEqualTo("RESOLVED");
        assertThat(report.decisionCode).isEqualTo("VIOLATION");
        verify(reportMapper, org.mockito.Mockito.times(2)).updateById(report);
        ArgumentCaptor<CommunityGovernanceEventService.EventCommand> events =
            ArgumentCaptor.forClass(CommunityGovernanceEventService.EventCommand.class);
        verify(eventService, org.mockito.Mockito.times(2)).record(events.capture());
        assertThat(events.getAllValues())
            .extracting(event -> event.fromStatus + ">" + event.toStatus)
            .containsExactly("OPEN>IN_REVIEW", "IN_REVIEW>RESOLVED");
        assertThat(events.getAllValues())
            .extracting(event -> event.idempotencyKey)
            .containsExactly("REPORT_DECISION:41:take-key", "REPORT_DECISION:41:resolve-key");
    }

    @Test
    void contentOperatorCanHideAndRestoreGroupWithGovernedRetentionAndAppendOnlyEvents() {
        CommunityGroup active = group(1L, "ACTIVE");
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(active);

        LocalDateTime beforeHide = LocalDateTime.now();
        service.moderate("GROUP", 1L, false, moderation("ABUSE", null, null), "hide-key");

        assertThat(active.status).isEqualTo("HIDDEN");
        assertThat(active.hiddenAt).isNotNull();
        assertThat(active.retainUntil).isAfterOrEqualTo(beforeHide.plusDays(180));
        verify(groupMapper).updateById(active);

        CommunityGroup hidden = group(2L, "HIDDEN");
        hidden.hiddenAt = LocalDateTime.now().minusHours(1);
        hidden.deletedAt = hidden.hiddenAt;
        hidden.retainUntil = hidden.hiddenAt.plusDays(180);
        when(groupMapper.selectByIdForUpdate(2L)).thenReturn(hidden);

        service.moderate("GROUP", 2L, true, moderation("REVIEWED_SAFE", null, null), "restore-key");

        assertThat(hidden.status).isEqualTo("ACTIVE");
        assertThat(hidden.hiddenAt).isNull();
        assertThat(hidden.deletedAt).isNull();
        assertThat(hidden.retainUntil).isNull();

        ArgumentCaptor<CommunityGovernanceEventService.EventCommand> events =
            ArgumentCaptor.forClass(CommunityGovernanceEventService.EventCommand.class);
        verify(eventService, org.mockito.Mockito.times(2)).record(events.capture());
        assertThat(events.getAllValues())
            .extracting(command -> command.eventType)
            .containsExactly("GROUP_HIDDEN", "GROUP_RESTORED");
    }

    @Test
    void moderationInternalNoteIsStoredOnlyOnTheLinkedReportAndNeverOnGovernanceEvent() {
        CommunityReport report = report(41L, "GROUP", 1L, "IN_REVIEW");
        report.assignedTo = 7L;
        CommunityGroup active = group(1L, "ACTIVE");
        when(reportMapper.selectByIdForUpdate(41L)).thenReturn(report);
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(active);

        service.moderate(
            "GROUP", 1L, false,
            moderation("POLICY_VIOLATION", "内部调查说明，不得进入事件", 41L),
            "moderate-key");

        assertThat(report.internalNote).isEqualTo("内部调查说明，不得进入事件");
        verify(reportMapper).updateById(report);
        ArgumentCaptor<CommunityGovernanceEventService.EventCommand> eventCaptor =
            ArgumentCaptor.forClass(CommunityGovernanceEventService.EventCommand.class);
        verify(eventService).record(eventCaptor.capture());
        CommunityGovernanceEventService.EventCommand event = eventCaptor.getValue();
        assertThat(event.reasonSummary).isEqualTo("POLICY_VIOLATION");
        assertThat(event.metadata).doesNotContainKey("internalNote");
        assertThat(event.metadata.toString()).doesNotContain("内部调查说明");
    }

    @Test
    void moderateOnlyOperatorCannotAssociateOrRewriteAReport() {
        authenticate("community:content:moderate");
        CommunityReport report = report(41L, "POST", 21L, "IN_REVIEW");
        report.assignedTo = 7L;

        assertThatThrownBy(() -> service.moderate(
            "POST", 21L, false,
            moderation("SPAM", "不得改写的内部备注", 41L), "moderate-only"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.FORBIDDEN);

        assertThat(report.internalNote).isNull();
        verify(reportMapper, never()).selectByIdForUpdate(41L);
        verify(reportMapper, never()).updateById(any(CommunityReport.class));
        verify(contentService, never()).moderatePost(any(), any(Boolean.class), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reportCanBeAssociatedOnlyWithBothPermissionsAndTheActorsOwnInReviewAssignment() {
        CommunityReport others = report(41L, "POST", 21L, "IN_REVIEW");
        others.assignedTo = 9L;
        when(reportMapper.selectByIdForUpdate(41L)).thenReturn(others);

        assertThatThrownBy(() -> service.moderate(
            "POST", 21L, false, moderation("SPAM", "越权备注", 41L), "other-review"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.FORBIDDEN);
        assertThat(others.internalNote).isNull();

        CommunityReport own = report(42L, "POST", 21L, "IN_REVIEW");
        own.assignedTo = 7L;
        CommunityPost post = new CommunityPost();
        post.id = 21L;
        when(reportMapper.selectByIdForUpdate(42L)).thenReturn(own);
        when(postMapper.selectByIdForUpdate(21L)).thenReturn(post);

        service.moderate(
            "POST", 21L, false, moderation("SPAM", "本人审核备注", 42L), "own-review");

        assertThat(own.internalNote).isEqualTo("本人审核备注");
        verify(reportMapper).updateById(own);
        verify(contentService).moderatePost(
            post, false, operator(7L), CommunityActorScope.CONTENT, CommunitySourceType.CONTENT_OPERATOR, "SPAM",
            scopedModerationKey("POST", 21L, false, "own-review"), 42L);
    }

    @Test
    void reportReviewerMustInspectMatchingReportContentAndEveryInspectionAppendsEvidence() {
        authenticate("community:report:review");

        assertThatThrownBy(() -> service.inspect("POST", 21L, null))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.FORBIDDEN);

        CommunityReport mismatch = report(41L, "COMMENT", 31L, "OPEN");
        when(reportMapper.selectById(41L)).thenReturn(mismatch);
        assertThatThrownBy(() -> service.inspect("POST", 21L, 41L))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.BAD_REQUEST);

        CommunityReport matching = report(42L, "POST", 21L, "IN_REVIEW");
        matching.assignedTo = 7L;
        CommunityPost post = new CommunityPost();
        post.id = 21L;
        post.groupId = 1L;
        post.title = "被举报帖子";
        post.contentText = "待检查正文";
        post.status = "PUBLISHED";
        when(reportMapper.selectById(42L)).thenReturn(matching);
        when(postMapper.selectById(21L)).thenReturn(post);

        assertThat(service.inspect("POST", 21L, 42L).content).isEqualTo("待检查正文");
        assertThat(service.inspect("POST", 21L, 42L).content).isEqualTo("待检查正文");

        ArgumentCaptor<CommunityGovernanceEventService.EventCommand> events =
            ArgumentCaptor.forClass(CommunityGovernanceEventService.EventCommand.class);
        verify(eventService, org.mockito.Mockito.times(2)).record(events.capture());
        assertThat(events.getAllValues())
            .allSatisfy(event -> {
                assertThat(event.eventType).isEqualTo("CONTENT_INSPECTED");
                assertThat(event.subjectType).isEqualTo("POST");
                assertThat(event.subjectId).isEqualTo(21L);
                assertThat(event.reportId).isEqualTo(42L);
                assertThat(event.metadata).containsEntry("reportContext", true);
            });
        assertThat(events.getAllValues())
            .extracting(event -> event.idempotencyKey)
            .doesNotHaveDuplicates();
    }

    @Test
    void postAndCommentModerationAreDelegatedWithContentOperatorScope() {
        CommunityPost post = new CommunityPost();
        post.id = 21L;
        CommunityComment comment = new CommunityComment();
        comment.id = 31L;
        when(postMapper.selectByIdForUpdate(21L)).thenReturn(post);
        when(commentMapper.selectByIdForUpdate(31L)).thenReturn(comment);

        service.moderate("POST", 21L, false, moderation("SPAM", null, null), "post-hide");
        service.moderate("COMMENT", 31L, true, moderation("SAFE", null, null), "comment-restore");

        verify(contentService).moderatePost(
            post, false, operator(7L), CommunityActorScope.CONTENT, CommunitySourceType.CONTENT_OPERATOR, "SPAM",
            scopedModerationKey("POST", 21L, false, "post-hide"), null);
        verify(contentService).moderateComment(
            comment, true, operator(7L), CommunityActorScope.CONTENT, CommunitySourceType.CONTENT_OPERATOR, "SAFE",
            scopedModerationKey("COMMENT", 31L, true, "comment-restore"), null);
    }

    private CurrentUser operator(Long id) {
        return new CurrentUser(id, "operator-" + id, Set.of("CUSTOM_CONTENT_ROLE"),
            Set.of("community:report:review", "community:content:moderate"));
    }

    private CommunityReport report(Long id, String targetType, Long targetId, String status) {
        CommunityReport report = new CommunityReport();
        report.id = id;
        report.targetType = targetType;
        report.targetId = targetId;
        report.status = status;
        return report;
    }

    private CommunityGroup group(Long id, String status) {
        CommunityGroup group = new CommunityGroup();
        group.id = id;
        group.status = status;
        group.versionNo = 0L;
        return group;
    }

    private CommunityRequests.ReportDecision decision(String action, String code, String internalNote) {
        CommunityRequests.ReportDecision request = new CommunityRequests.ReportDecision();
        request.decision = action;
        request.decisionCode = code;
        request.internalNote = internalNote;
        return request;
    }

    private CommunityRequests.ModerationAction moderation(String code, String internalNote, Long reportId) {
        CommunityRequests.ModerationAction request = new CommunityRequests.ModerationAction();
        request.reasonCode = code;
        request.internalNote = internalNote;
        request.reportId = reportId;
        return request;
    }

    private void authenticate(String... authorities) {
        CurrentUser currentUser = new CurrentUser(7L, "operator-7", Set.of("CUSTOM_CONTENT_ROLE"),
            Set.copyOf(Arrays.asList(authorities)));
        List<SimpleGrantedAuthority> granted = Arrays.stream(authorities)
            .map(SimpleGrantedAuthority::new)
            .toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(currentUser, null, granted));
    }

    private String scopedModerationKey(String targetType, Long targetId, boolean restore, String rawKey) {
        return new CommunityTextPolicy().scopedIdempotencyKey(
            "CONTENT_MODERATE_" + targetType + "_" + targetId + "_" + restore, rawKey);
    }
}
