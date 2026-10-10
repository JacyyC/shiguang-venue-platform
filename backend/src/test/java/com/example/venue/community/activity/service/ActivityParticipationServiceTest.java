package com.example.venue.community.activity.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.ResultCode;
import com.example.venue.community.activity.dto.ActivityRequests;
import com.example.venue.community.activity.entity.CommunityActivity;
import com.example.venue.community.activity.entity.CommunityActivityApplication;
import com.example.venue.community.activity.entity.CommunityActivityCommand;
import com.example.venue.community.activity.entity.CommunityActivityParticipation;
import com.example.venue.community.activity.mapper.CommunityActivityApplicationMapper;
import com.example.venue.community.activity.mapper.CommunityActivityMapper;
import com.example.venue.community.activity.mapper.CommunityActivityParticipationMapper;
import com.example.venue.community.activity.mapper.CommunityCompanionRequestMapper;
import com.example.venue.community.entity.CommunityGroupMember;
import com.example.venue.community.service.CommunityAccessService;
import com.example.venue.community.service.CommunityRateLimitService;
import com.example.venue.community.service.CommunityTextPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityParticipationServiceTest {
    private static final Long ORGANIZER_ID = 7L;
    private static final Long APPLICANT_ID = 9L;

    @Mock
    private CommunityActivityMapper activityMapper;
    @Mock
    private CommunityCompanionRequestMapper companionMapper;
    @Mock
    private CommunityActivityApplicationMapper applicationMapper;
    @Mock
    private CommunityActivityParticipationMapper participationMapper;
    @Mock
    private CommunityAccessService accessService;
    @Mock
    private CommunityTextPolicy textPolicy;
    @Mock
    private CommunityRateLimitService rateLimitService;
    @Mock
    private ActivityCommandService commandService;
    @Mock
    private ActivityStateEventService eventService;
    @Mock
    private ActivityNotificationService notificationService;

    private ActivityParticipationService service;

    @BeforeEach
    void setUp() {
        service = new ActivityParticipationService(
            activityMapper, companionMapper, applicationMapper, participationMapper,
            accessService, textPolicy, rateLimitService, commandService, eventService, notificationService);
    }

    @Test
    void applyUsesTheDedicatedParticipationRateLimitBucketAndNeverConsumesJoin() {
        CurrentUser applicant = user(APPLICANT_ID);
        CommunityActivity activity = activity();
        when(accessService.requireCurrentUser()).thenReturn(applicant);
        when(activityMapper.selectById(activity.id)).thenReturn(activity);
        when(activityMapper.selectByIdForUpdate(activity.id)).thenReturn(activity);
        when(accessService.requireActiveMemberForUpdate(activity.groupId, APPLICANT_ID))
            .thenReturn(activeMember(activity.groupId, APPLICANT_ID));
        when(textPolicy.fingerprint(any(Object[].class))).thenReturn("request-hash");
        when(commandService.claim(eq(APPLICANT_ID), eq("ACTIVITY_APPLY"), eq("apply-key"), any()))
            .thenReturn(claim("apply-key"));

        CommunityActivityApplication result = service.apply("ACTIVITY", activity.id, "apply-key");

        assertThat(result.status).isEqualTo("PENDING");
        verify(rateLimitService).check(APPLICANT_ID, CommunityRateLimitService.Action.APPLY);
        verify(rateLimitService, never()).check(anyLong(), eq(CommunityRateLimitService.Action.JOIN));
    }

    @Test
    void applyToAFullTargetIsRejectedByCapacityBeforeAnyRateLimitCheck() {
        CurrentUser applicant = user(APPLICANT_ID);
        CommunityActivity activity = activity();
        activity.capacityTotal = 2;
        activity.participantCount = 2;
        activity.status = "FULL";
        when(accessService.requireCurrentUser()).thenReturn(applicant);
        when(activityMapper.selectById(activity.id)).thenReturn(activity);
        when(activityMapper.selectByIdForUpdate(activity.id)).thenReturn(activity);
        when(accessService.requireActiveMemberForUpdate(activity.groupId, APPLICANT_ID))
            .thenReturn(activeMember(activity.groupId, APPLICANT_ID));
        when(textPolicy.fingerprint(any(Object[].class))).thenReturn("request-hash");
        when(commandService.claim(eq(APPLICANT_ID), eq("ACTIVITY_APPLY"), eq("apply-key"), any()))
            .thenReturn(claim("apply-key"));

        assertThatThrownBy(() -> service.apply("ACTIVITY", activity.id, "apply-key"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("名额已满");

        verify(rateLimitService, never()).check(anyLong(), any(CommunityRateLimitService.Action.class));
        verify(applicationMapper, never()).insert(any(CommunityActivityApplication.class));
    }

    @Test
    void acceptingBeyondCapacityIsRejectedByCapacityAndKeepsTheSlotCount() {
        CurrentUser organizer = user(ORGANIZER_ID);
        CommunityActivity activity = activity();
        activity.capacityTotal = 2;
        activity.participantCount = 2;
        CommunityActivityApplication application = application();
        when(accessService.requireCurrentUser()).thenReturn(organizer);
        when(applicationMapper.selectById(application.id)).thenReturn(application);
        when(activityMapper.selectById(activity.id)).thenReturn(activity);
        when(activityMapper.selectByIdForUpdate(activity.id)).thenReturn(activity);
        when(applicationMapper.selectByIdForUpdate(application.id)).thenReturn(application);
        when(textPolicy.fingerprint(any(Object[].class))).thenReturn("request-hash");
        when(commandService.claim(eq(ORGANIZER_ID), eq("APPLICATION_ACCEPT"), eq("accept-key"), any()))
            .thenReturn(claim("accept-key"));

        assertThatThrownBy(() -> service.accept(application.id, "accept-key"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("名额已满");

        assertThat(activity.participantCount).isEqualTo(2);
        verify(participationMapper, never()).insert(any(CommunityActivityParticipation.class));
    }

    @Test
    void applyRequiresAnActiveGroupMemberAfterContextLockAndBeforeTargetLock() {
        CurrentUser applicant = user(APPLICANT_ID);
        CommunityActivity activity = activity();
        when(accessService.requireCurrentUser()).thenReturn(applicant);
        when(activityMapper.selectById(activity.id)).thenReturn(activity);
        doThrow(BusinessException.forbidden("需要有效的小组成员身份"))
            .when(accessService).requireActiveMemberForUpdate(activity.groupId, APPLICANT_ID);

        assertForbidden(() -> service.apply("ACTIVITY", activity.id, "apply-key"));

        verify(accessService).requireActivityContextForUpdate(
            activity.groupId, activity.referencePostId, APPLICANT_ID);
        verify(activityMapper, never()).selectByIdForUpdate(activity.id);
        verify(applicationMapper, never()).insert(any(CommunityActivityApplication.class));
    }

    @Test
    void acceptRevalidatesTargetContextAndApplicantMembershipBeforeExposingOrJoining() {
        CurrentUser organizer = user(ORGANIZER_ID);
        CommunityActivity activity = activity();
        CommunityActivityApplication application = application();
        when(accessService.requireCurrentUser()).thenReturn(organizer);
        when(applicationMapper.selectById(application.id)).thenReturn(application);
        when(activityMapper.selectById(activity.id)).thenReturn(activity);
        when(activityMapper.selectByIdForUpdate(activity.id)).thenReturn(activity);
        when(applicationMapper.selectByIdForUpdate(application.id)).thenReturn(application);
        when(textPolicy.fingerprint(any(Object[].class))).thenReturn("request-hash");
        when(commandService.claim(eq(ORGANIZER_ID), eq("APPLICATION_ACCEPT"), eq("accept-key"), any()))
            .thenReturn(claim("accept-key"));
        doThrow(BusinessException.forbidden("需要有效的小组成员身份"))
            .when(accessService).requireActiveMemberForUpdate(activity.groupId, APPLICANT_ID);

        assertForbidden(() -> service.accept(application.id, "accept-key"));

        verify(accessService).requireActivityContextForUpdate(
            activity.groupId, activity.referencePostId, ORGANIZER_ID);
        verify(participationMapper, never()).findForUpdate(any(), anyLong(), anyLong());
        verify(activityMapper, never()).updateById(any(CommunityActivity.class));
    }

    @Test
    void hiddenGroupOrReferencePostStopsDecisionBeforeTargetAndApplicationLocks() {
        CurrentUser organizer = user(ORGANIZER_ID);
        CommunityActivity activity = activity();
        CommunityActivityApplication application = application();
        when(accessService.requireCurrentUser()).thenReturn(organizer);
        when(applicationMapper.selectById(application.id)).thenReturn(application);
        when(activityMapper.selectById(activity.id)).thenReturn(activity);
        doThrow(BusinessException.notFound("社区资源不存在"))
            .when(accessService).requireActivityContextForUpdate(
                activity.groupId, activity.referencePostId, ORGANIZER_ID);

        assertHidden(() -> service.accept(application.id, "accept-key"));

        verify(activityMapper, never()).selectByIdForUpdate(activity.id);
        verify(applicationMapper, never()).selectByIdForUpdate(application.id);
    }

    @Test
    void acceptedLastSlotUsesUniqueInsertBeforeLockingAndReactivatingExistingParticipation() {
        CurrentUser organizer = user(ORGANIZER_ID);
        CommunityActivity activity = activity();
        activity.capacityTotal = 2;
        activity.participantCount = 1;
        CommunityActivityApplication application = application();
        CommunityActivityParticipation former = new CommunityActivityParticipation();
        former.id = 301L;
        former.targetType = "ACTIVITY";
        former.targetId = activity.id;
        former.userId = APPLICANT_ID;
        former.status = "LEFT";
        former.versionNo = 1L;
        when(accessService.requireCurrentUser()).thenReturn(organizer);
        when(applicationMapper.selectById(application.id)).thenReturn(application);
        when(activityMapper.selectById(activity.id)).thenReturn(activity);
        when(activityMapper.selectByIdForUpdate(activity.id)).thenReturn(activity);
        when(applicationMapper.selectByIdForUpdate(application.id)).thenReturn(application);
        when(accessService.requireActiveMemberForUpdate(activity.groupId, APPLICANT_ID))
            .thenReturn(activeMember(activity.groupId, APPLICANT_ID));
        when(textPolicy.fingerprint(any(Object[].class))).thenReturn("request-hash");
        when(commandService.claim(eq(ORGANIZER_ID), eq("APPLICATION_ACCEPT"), eq("accept-key"), any()))
            .thenReturn(claim("accept-key"));
        doThrow(new DuplicateKeyException("existing participation"))
            .when(participationMapper).insert(any(CommunityActivityParticipation.class));
        when(participationMapper.findForUpdate("ACTIVITY", activity.id, APPLICANT_ID)).thenReturn(former);
        when(participationMapper.reactivate(eq(former.id), eq(1L), eq(2L),
            eq(application.id), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);

        CommunityActivityApplication result = service.accept(application.id, "accept-key");

        assertThat(result.status).isEqualTo("ACCEPTED");
        assertThat(activity.status).isEqualTo("FULL");
        assertThat(activity.participantCount).isEqualTo(2);
        assertThat(former.status).isEqualTo("ACTIVE");
        InOrder order = inOrder(activityMapper, applicationMapper, participationMapper);
        order.verify(activityMapper).selectByIdForUpdate(activity.id);
        order.verify(applicationMapper).selectByIdForUpdate(application.id);
        order.verify(participationMapper).insert(any(CommunityActivityParticipation.class));
        order.verify(participationMapper).findForUpdate("ACTIVITY", activity.id, APPLICANT_ID);
        order.verify(participationMapper).reactivate(eq(former.id), eq(1L), eq(2L),
            eq(application.id), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void inactiveOrBlockedApplicantCannotBeAcceptedButPendingApplicationCanStillBeRejected() {
        CurrentUser organizer = user(ORGANIZER_ID);
        CommunityActivity activity = activity();
        CommunityActivityApplication application = application();
        when(accessService.requireCurrentUser()).thenReturn(organizer);
        when(applicationMapper.selectById(application.id)).thenReturn(application);
        when(activityMapper.selectById(activity.id)).thenReturn(activity);
        when(activityMapper.selectByIdForUpdate(activity.id)).thenReturn(activity);
        when(applicationMapper.selectByIdForUpdate(application.id)).thenReturn(application);
        when(textPolicy.fingerprint(any(Object[].class))).thenReturn("request-hash");
        when(commandService.claim(eq(ORGANIZER_ID), eq("APPLICATION_REJECT"), eq("reject-key"), any()))
            .thenReturn(claim("reject-key"));
        ActivityRequests.Decision decision = new ActivityRequests.Decision();
        decision.reasonCode = "NO_LONGER_ELIGIBLE";
        CommunityActivityApplication result = service.reject(application.id, decision, "reject-key");

        assertThat(result.status).isEqualTo("REJECTED");
        assertThat(result.reasonCode).isEqualTo("NO_LONGER_ELIGIBLE");
        verify(accessService, never()).requireActiveMemberForUpdate(activity.groupId, APPLICANT_ID);
        verify(participationMapper, never()).findForUpdate(any(), anyLong(), anyLong());
    }

    @Test
    void applicationListRevalidatesContextAndQueriesOnlyActiveUnblockedMembers() {
        CommunityActivity activity = activity();
        CommunityActivityApplication application = application();
        when(accessService.requireCurrentUser()).thenReturn(user(ORGANIZER_ID));
        when(activityMapper.selectById(activity.id)).thenReturn(activity);
        when(applicationMapper.listByTarget(
            "ACTIVITY", activity.id, activity.groupId, ORGANIZER_ID, "PENDING", null, 21))
            .thenReturn(List.of(application));
        ActivityRequests.ApplicationQuery query = new ActivityRequests.ApplicationQuery();
        query.status = "PENDING";

        var page = service.applications("ACTIVITY", activity.id, query);

        assertThat(page.records()).containsExactly(application);
        verify(accessService).requireActivityContext(
            activity.groupId, activity.referencePostId, ORGANIZER_ID);
        verify(applicationMapper).listByTarget(
            "ACTIVITY", activity.id, activity.groupId, ORGANIZER_ID, "PENDING", null, 21);
    }

    @Test
    void viewerStateReadsOnlyTheAuthenticatedUsersApplicationAndParticipation() {
        CommunityActivity activity = activity();
        CommunityActivityApplication application = application();
        CommunityActivityParticipation participation = new CommunityActivityParticipation();
        participation.id = 61L;
        participation.targetType = "ACTIVITY";
        participation.targetId = activity.id;
        participation.userId = APPLICANT_ID;
        participation.roleType = "PARTICIPANT";
        participation.status = "ACTIVE";
        when(accessService.requireCurrentUser()).thenReturn(user(APPLICANT_ID));
        when(activityMapper.selectById(activity.id)).thenReturn(activity);
        when(applicationMapper.find("ACTIVITY", activity.id, APPLICANT_ID)).thenReturn(application);
        when(participationMapper.find("ACTIVITY", activity.id, APPLICANT_ID)).thenReturn(participation);

        var state = service.viewerState("ACTIVITY", activity.id);

        assertThat(state.application().id).isEqualTo(application.id);
        assertThat(state.participation().id).isEqualTo(participation.id);
        verify(accessService).requireActivityContext(
            activity.groupId, activity.referencePostId, APPLICANT_ID);
        verify(applicationMapper).find("ACTIVITY", activity.id, APPLICANT_ID);
        verify(participationMapper).find("ACTIVITY", activity.id, APPLICANT_ID);
    }

    private CommunityActivity activity() {
        CommunityActivity activity = new CommunityActivity();
        activity.id = 41L;
        activity.groupId = 11L;
        activity.referencePostId = 21L;
        activity.organizerUserId = ORGANIZER_ID;
        activity.startTime = LocalDateTime.of(2099, 8, 1, 9, 0);
        activity.endTime = LocalDateTime.of(2099, 8, 1, 11, 0);
        activity.capacityTotal = 4;
        activity.participantCount = 1;
        activity.status = "PUBLISHED";
        activity.versionNo = 1L;
        return activity;
    }

    private CommunityActivityApplication application() {
        CommunityActivityApplication application = new CommunityActivityApplication();
        application.id = 51L;
        application.targetType = "ACTIVITY";
        application.targetId = 41L;
        application.applicantUserId = APPLICANT_ID;
        application.status = "PENDING";
        application.versionNo = 0L;
        return application;
    }

    private CommunityGroupMember activeMember(Long groupId, Long userId) {
        CommunityGroupMember member = new CommunityGroupMember();
        member.groupId = groupId;
        member.userId = userId;
        member.status = "ACTIVE";
        return member;
    }

    private ActivityCommandService.Claim claim(String key) {
        CommunityActivityCommand command = new CommunityActivityCommand();
        command.id = 61L;
        command.idempotencyKey = key;
        command.status = "PROCESSING";
        return new ActivityCommandService.Claim(command, false);
    }

    private CurrentUser user(Long id) {
        return new CurrentUser(id, "user-" + id, Set.of("USER"), Set.of("community:access"));
    }

    private void assertForbidden(Runnable action) {
        assertCode(action, ResultCode.FORBIDDEN);
    }

    private void assertHidden(Runnable action) {
        assertCode(action, ResultCode.NOT_FOUND);
    }

    private void assertCode(Runnable action, ResultCode code) {
        assertThatThrownBy(action::run)
            .isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).getCode())
            .isEqualTo(code);
    }
}
