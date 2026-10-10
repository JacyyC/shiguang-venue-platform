package com.example.venue.community.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.ResultCode;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.dto.CommunityRequests;
import com.example.venue.community.entity.CommunityComment;
import com.example.venue.community.entity.CommunityGroup;
import com.example.venue.community.entity.CommunityGroupMember;
import com.example.venue.community.entity.CommunityPost;
import com.example.venue.community.entity.CommunityUserBlock;
import com.example.venue.community.enums.CommunityActorScope;
import com.example.venue.community.enums.CommunitySourceType;
import com.example.venue.community.mapper.CommunityCommentMapper;
import com.example.venue.community.mapper.CommunityGroupMapper;
import com.example.venue.community.mapper.CommunityGroupMemberMapper;
import com.example.venue.community.mapper.CommunityPostMapper;
import com.example.venue.community.mapper.CommunityReportMapper;
import com.example.venue.community.mapper.CommunityUserBlockMapper;
import com.example.venue.system.entity.SysDictData;
import com.example.venue.system.mapper.SysDictDataMapper;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunityServiceTest {
    private static final Long USER_ID = 7L;

    @Mock
    private CommunityGroupMapper groupMapper;
    @Mock
    private CommunityGroupMemberMapper memberMapper;
    @Mock
    private SysDictDataMapper dictDataMapper;
    @Mock
    private CommunityPostMapper postMapper;
    @Mock
    private CommunityCommentMapper commentMapper;
    @Mock
    private CommunityReportMapper reportMapper;
    @Mock
    private CommunityUserBlockMapper blockMapper;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private CommunityRateLimitService rateLimitService;
    @Mock
    private CommunityGovernanceEventService eventService;

    private CommunityGroupService groupService;
    private CommunityContentService contentService;

    @BeforeEach
    void setUp() {
        CommunityAccessService accessService = new CommunityAccessService(
            groupMapper, memberMapper, postMapper, commentMapper, blockMapper);
        CommunityTextPolicy textPolicy = new CommunityTextPolicy();
        groupService = new CommunityGroupService(
            groupMapper, memberMapper, dictDataMapper, accessService, textPolicy, rateLimitService, eventService);
        contentService = new CommunityContentService(
            postMapper, commentMapper, reportMapper, blockMapper, userMapper,
            accessService, textPolicy, rateLimitService, eventService);
        authenticate(USER_ID, "USER");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousUsersCannotReadGroupsOrPosts() {
        SecurityContextHolder.clearContext();

        assertForbidden(() -> groupService.search(new CommunityRequests.GroupQuery()), "请先登录");
        assertForbidden(() -> contentService.searchPosts(new CommunityRequests.PostQuery()), "请先登录");

        verifyNoInteractions(groupMapper, postMapper);
    }

    @Test
    void ordinaryUsersCanReadPlatformGroupsButMembersOnlyPostsRemainHiddenFromNonMembers() {
        CommunityGroup platformGroup = group(1L, 8L, "PLATFORM_VISIBLE");
        when(groupMapper.selectById(1L)).thenReturn(platformGroup);
        when(blockMapper.countActiveEitherDirection(USER_ID, 8L)).thenReturn(0L);

        CommunityQueryRows.GroupRow detail = new CommunityQueryRows.GroupRow();
        detail.id = 1L;
        detail.currentMemberRole = "MEMBER";
        detail.currentMemberStatus = "ACTIVE";
        detail.memberCount = 3L;
        when(groupMapper.findDetail(1L, USER_ID)).thenReturn(detail);

        assertThat(groupService.get(1L)).isSameAs(detail);

        CommunityGroup membersOnlyGroup = group(2L, 9L, "MEMBERS_ONLY");
        when(groupMapper.selectById(2L)).thenReturn(membersOnlyGroup);
        when(blockMapper.countActiveEitherDirection(USER_ID, 9L)).thenReturn(0L);

        CommunityQueryRows.GroupRow membersOnlyDetail = new CommunityQueryRows.GroupRow();
        membersOnlyDetail.id = 2L;
        membersOnlyDetail.visibility = "MEMBERS_ONLY";
        when(groupMapper.findDetail(2L, USER_ID)).thenReturn(membersOnlyDetail);
        assertThat(groupService.get(2L)).isSameAs(membersOnlyDetail);

        // 详情聚合查询走 SQL 可见性谓词：非成员读取仅成员小组帖子时返回 null，服务层统一 404。
        when(postMapper.findVisibleDetail(USER_ID, 20L)).thenReturn(null);
        assertThatThrownBy(() -> contentService.getPost(20L))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    void activeMembersCanCreatePostsAndTheRateLimitIsAppliedBeforePersistence() {
        CommunityGroup group = group(1L, 8L, "MEMBERS_ONLY");
        CommunityGroupMember member = member(1L, USER_ID, "MEMBER");
        when(groupMapper.selectById(1L)).thenReturn(group);
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(group);
        when(blockMapper.countActiveEitherDirection(USER_ID, 8L)).thenReturn(0L);
        when(memberMapper.findByGroupAndUser(1L, USER_ID)).thenReturn(member);
        when(memberMapper.findByGroupAndUserForUpdate(1L, USER_ID)).thenReturn(member);

        CommunityPost result = contentService.createPost(createPost(1L, "学习计划", "周末一起复习"), "post-key");

        ArgumentCaptor<CommunityPost> postCaptor = ArgumentCaptor.forClass(CommunityPost.class);
        verify(rateLimitService).check(USER_ID, CommunityRateLimitService.Action.POST);
        verify(postMapper).insert(postCaptor.capture());
        assertThat(result).isSameAs(postCaptor.getValue());
        assertThat(result.groupId).isEqualTo(1L);
        assertThat(result.authorUserId).isEqualTo(USER_ID);
        assertThat(result.status).isEqualTo("PUBLISHED");
        assertThat(result.versionNo).isZero();
    }

    @Test
    void venueAdministratorRoleAloneDoesNotGrantMembershipOrGroupManagement() {
        authenticate(12L, "VENUE_ADMIN");
        CommunityGroup group = group(1L, 8L, "PLATFORM_VISIBLE");
        when(groupMapper.selectById(1L)).thenReturn(group);
        when(blockMapper.countActiveEitherDirection(12L, 8L)).thenReturn(0L);
        when(memberMapper.findByGroupAndUser(1L, 12L)).thenReturn(null);

        assertForbidden(
            () -> contentService.createPost(createPost(1L, "场馆管理员发帖", "不应凭角色越权"), "venue-key"),
            "需要有效的小组成员身份");
        assertForbidden(() -> groupService.members(1L, "PENDING", null, 20), "需要有效的小组成员身份");

        verify(postMapper, never()).insert(any(CommunityPost.class));
    }

    @Test
    void groupAdministratorCanModerateOwnGroupButNotAnotherGroupsContent() {
        CommunityPost ownGroupPost = post(20L, 1L, 8L, "PUBLISHED");
        when(postMapper.selectByIdForUpdate(20L)).thenReturn(ownGroupPost);
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(group(1L, 8L, "PLATFORM_VISIBLE"));
        when(memberMapper.findByGroupAndUserForUpdate(1L, USER_ID))
            .thenReturn(member(1L, USER_ID, "ADMIN"));

        contentService.groupModerate(1L, "POST", 20L, false, moderation("SPAM"), "hide-own");

        assertThat(ownGroupPost.status).isEqualTo("HIDDEN");
        assertThat(ownGroupPost.hiddenScope).isEqualTo("GROUP");
        assertThat(ownGroupPost.retainUntil).isAfter(ownGroupPost.hiddenAt.plusDays(179));
        verify(postMapper).updateById(ownGroupPost);

        CommunityPost otherGroupPost = post(21L, 2L, 9L, "PUBLISHED");
        when(groupMapper.selectByIdForUpdate(2L)).thenReturn(group(2L, 9L, "PLATFORM_VISIBLE"));
        when(postMapper.selectByIdForUpdate(21L)).thenReturn(otherGroupPost);
        when(memberMapper.findByGroupAndUserForUpdate(2L, USER_ID)).thenReturn(null);

        assertForbidden(
            () -> contentService.groupModerate(2L, "POST", 21L, false, moderation("SPAM"), "hide-other"),
            "需要有效的小组成员身份");
        assertThat(otherGroupPost.status).isEqualTo("PUBLISHED");
        verify(postMapper, never()).updateById(otherGroupPost);
    }

    @Test
    void groupAdministratorCannotRestorePostHiddenByPlatformModeration() {
        CommunityPost platformHidden = post(22L, 1L, 8L, "HIDDEN");
        platformHidden.hiddenScope = "PLATFORM";
        when(postMapper.selectByIdForUpdate(22L)).thenReturn(platformHidden);
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(group(1L, 8L, "PLATFORM_VISIBLE"));
        when(memberMapper.findByGroupAndUserForUpdate(1L, USER_ID))
            .thenReturn(member(1L, USER_ID, "ADMIN"));

        assertThatThrownBy(
            () -> contentService.groupModerate(1L, "POST", 22L, true, moderation("REVIEWED_SAFE"), "restore-platform-post"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.FORBIDDEN);

        assertThat(platformHidden.status).isEqualTo("HIDDEN");
        assertThat(platformHidden.hiddenScope).isEqualTo("PLATFORM");
        verify(postMapper, never()).updateById(platformHidden);
    }

    @Test
    void groupAdministratorCannotRestoreCommentHiddenByPlatformModeration() {
        CommunityComment platformHidden = new CommunityComment();
        platformHidden.id = 32L;
        platformHidden.groupId = 1L;
        platformHidden.postId = 20L;
        platformHidden.authorUserId = 8L;
        platformHidden.status = "HIDDEN";
        platformHidden.hiddenScope = "PLATFORM";
        platformHidden.versionNo = 1L;
        when(commentMapper.selectById(32L)).thenReturn(platformHidden);
        when(commentMapper.selectByIdForUpdate(32L)).thenReturn(platformHidden);
        when(postMapper.selectByIdForUpdate(20L)).thenReturn(post(20L, 1L, 8L, "PUBLISHED"));
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(group(1L, 8L, "PLATFORM_VISIBLE"));
        when(memberMapper.findByGroupAndUserForUpdate(1L, USER_ID))
            .thenReturn(member(1L, USER_ID, "ADMIN"));

        assertThatThrownBy(
            () -> contentService.groupModerate(1L, "COMMENT", 32L, true, moderation("REVIEWED_SAFE"), "restore-platform-comment"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.FORBIDDEN);

        assertThat(platformHidden.status).isEqualTo("HIDDEN");
        assertThat(platformHidden.hiddenScope).isEqualTo("PLATFORM");
        verify(commentMapper, never()).updateById(platformHidden);
    }

    @Test
    void pendingMembershipListExcludesBlockedUsersInEitherDirection() throws Exception {
        Method method = CommunityGroupMemberMapper.class.getMethod(
            "listByGroup", Long.class, Long.class, String.class, Long.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());
        assertThat(sql)
            .contains("b.blocker_user_id = #{viewerUserId} and b.blocked_user_id = m.user_id")
            .contains("b.blocked_user_id = #{viewerUserId} and b.blocker_user_id = m.user_id");

        when(groupMapper.selectById(1L)).thenReturn(group(1L, USER_ID, "PLATFORM_VISIBLE"));
        when(memberMapper.findByGroupAndUser(1L, USER_ID)).thenReturn(member(1L, USER_ID, "ADMIN"));
        when(memberMapper.listByGroup(USER_ID, 1L, "PENDING", null, 21)).thenReturn(List.of());

        assertThat(groupService.members(1L, "PENDING", null, 20).records()).isEmpty();

        verify(memberMapper).listByGroup(USER_ID, 1L, "PENDING", null, 21);
    }

    @Test
    void pendingMembershipDecisionIsRejectedAfterEitherUserBlocksTheOther() {
        CommunityGroupMember manager = member(1L, USER_ID, "ADMIN");
        CommunityGroupMember pending = member(1L, 9L, "MEMBER");
        pending.id = 109L;
        pending.status = "PENDING";
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(group(1L, USER_ID, "PLATFORM_VISIBLE"));
        when(memberMapper.findByGroupAndUserForUpdate(1L, USER_ID)).thenReturn(manager);
        when(memberMapper.selectById(109L)).thenReturn(pending);
        when(memberMapper.findByGroupAndUserForUpdate(1L, 9L)).thenReturn(pending);
        when(blockMapper.countActiveEitherDirection(USER_ID, 9L)).thenReturn(1L);

        CommunityRequests.MembershipDecision request = new CommunityRequests.MembershipDecision();
        request.decision = "APPROVE";

        assertThatThrownBy(() -> groupService.decideMembership(1L, 109L, request, "blocked-decision"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.NOT_FOUND);

        assertThat(pending.status).isEqualTo("PENDING");
        verify(memberMapper, never()).updateById(pending);
        verifyNoInteractions(eventService);
    }

    @Test
    void roleChangesLockTheMemberAndKeepEveryRealTransitionWhileReplaysStayIdempotent() {
        CommunityGroupMember owner = member(1L, USER_ID, "OWNER");
        CommunityGroupMember target = member(1L, 9L, "MEMBER");
        target.id = 109L;
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(group(1L, USER_ID, "PLATFORM_VISIBLE"));
        when(memberMapper.findByGroupAndUserForUpdate(1L, USER_ID)).thenReturn(owner);
        when(memberMapper.selectById(109L)).thenReturn(target);
        when(memberMapper.findByGroupAndUserForUpdate(1L, 9L)).thenReturn(target);

        CommunityRequests.ChangeMemberRole admin = changeRole("ADMIN");
        CommunityRequests.ChangeMemberRole ordinaryMember = changeRole("MEMBER");

        assertThat(groupService.changeRole(1L, 109L, admin, "promote-1").memberRole)
            .isEqualTo("ADMIN");
        assertThat(groupService.changeRole(1L, 109L, admin, "promote-1").memberRole)
            .isEqualTo("ADMIN");
        assertThat(groupService.changeRole(1L, 109L, ordinaryMember, "demote-1").memberRole)
            .isEqualTo("MEMBER");
        assertThat(groupService.changeRole(1L, 109L, admin, "promote-2").memberRole)
            .isEqualTo("ADMIN");

        assertThatThrownBy(() -> groupService.changeRole(1L, 109L, ordinaryMember, "promote-2"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.CONFLICT);

        verify(memberMapper, times(5)).findByGroupAndUserForUpdate(1L, 9L);
        verify(memberMapper, times(3)).updateById(target);
        ArgumentCaptor<CommunityGovernanceEventService.EventCommand> events =
            ArgumentCaptor.forClass(CommunityGovernanceEventService.EventCommand.class);
        verify(eventService, times(3)).record(events.capture());
        assertThat(events.getAllValues())
            .extracting(event -> event.fromStatus + ">" + event.toStatus)
            .containsExactly("MEMBER>ADMIN", "ADMIN>MEMBER", "MEMBER>ADMIN");
        assertThat(events.getAllValues())
            .extracting(event -> event.idempotencyKey)
            .doesNotHaveDuplicates();

        CommunityTextPolicy policy = new CommunityTextPolicy();
        assertThat(target.lastIdempotencyKey)
            .isEqualTo(policy.scopedIdempotencyKey("CHANGE_MEMBER_ROLE", "promote-2"));
        assertThat(target.lastRequestHash).isEqualTo(policy.fingerprint(1L, 109L, "ADMIN"));
    }

    @Test
    void delayedReplayOfAnEarlierRoleChangeCannotMutateStateWithoutNewEvidence() {
        CommunityTextPolicy policy = new CommunityTextPolicy();
        CommunityGroupMember owner = member(1L, USER_ID, "OWNER");
        CommunityGroupMember target = member(1L, 9L, "MEMBER");
        target.id = 109L;
        target.lastIdempotencyKey = policy.scopedIdempotencyKey("CHANGE_MEMBER_ROLE", "demote-later");
        target.lastRequestHash = policy.fingerprint(1L, 109L, "MEMBER");
        String earlierKey = policy.scopedIdempotencyKey("CHANGE_MEMBER_ROLE", "promote-earlier");
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(group(1L, USER_ID, "PLATFORM_VISIBLE"));
        when(memberMapper.findByGroupAndUserForUpdate(1L, USER_ID)).thenReturn(owner);
        when(memberMapper.selectById(109L)).thenReturn(target);
        when(memberMapper.findByGroupAndUserForUpdate(1L, 9L)).thenReturn(target);
        when(eventService.isRecorded(
            CommunitySourceType.GROUP_ADMIN, "MEMBER_ROLE_CHANGED", "MEMBER", 109L, USER_ID, earlierKey))
            .thenReturn(true);

        assertThatThrownBy(
            () -> groupService.changeRole(1L, 109L, changeRole("ADMIN"), "promote-earlier"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("角色变更已处理")
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.CONFLICT);

        assertThat(target.memberRole).isEqualTo("MEMBER");
        assertThat(target.lastIdempotencyKey)
            .isEqualTo(policy.scopedIdempotencyKey("CHANGE_MEMBER_ROLE", "demote-later"));
        verify(memberMapper, never()).updateById(target);
        verify(eventService, never()).record(any());
    }

    @Test
    void inactiveGroupStopsEveryMembershipWriteBeforeMemberRowsAreLocked() {
        CommunityGroup deleted = group(1L, USER_ID, "PLATFORM_VISIBLE");
        deleted.status = "DELETED";
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(deleted);

        CommunityRequests.MembershipDecision decision = new CommunityRequests.MembershipDecision();
        decision.decision = "APPROVE";

        assertHidden(() -> groupService.join(1L, "join-after-delete"));
        assertHidden(() -> groupService.decideMembership(1L, 109L, decision, "decide-after-delete"));
        assertHidden(() -> groupService.changeRole(1L, 109L, changeRole("ADMIN"), "role-after-delete"));
        assertHidden(() -> groupService.removeMember(1L, 109L, "remove-after-delete"));
        assertHidden(() -> groupService.leave(1L, "leave-after-delete"));

        verify(groupMapper, times(5)).selectByIdForUpdate(1L);
        verifyNoInteractions(memberMapper, rateLimitService, eventService);
    }

    @Test
    void deletingAReportedGroupNeverShortensItsLongerGovernanceRetentionDeadline() {
        CommunityGroup reportedGroup = group(1L, USER_ID, "PLATFORM_VISIBLE");
        LocalDateTime governanceDeadline = LocalDateTime.now().plusDays(180);
        reportedGroup.retainUntil = governanceDeadline;
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(reportedGroup);

        groupService.delete(1L, "delete-reported-group");

        assertThat(reportedGroup.status).isEqualTo("DELETED");
        assertThat(reportedGroup.retainUntil).isEqualTo(governanceDeadline);
        verify(groupMapper).updateById(reportedGroup);
    }

    @Test
    void deletedGroupReplaysOnlyForItsOriginalOwnerAndOriginalKey() {
        CommunityGroup deleted = group(1L, USER_ID, "PLATFORM_VISIBLE");
        deleted.status = "DELETED";
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(deleted);
        when(eventService.isRecorded(
            CommunitySourceType.GROUP_ADMIN, "GROUP_DELETED", "GROUP", 1L, USER_ID,
            "DELETE_GROUP:1:delete-key"))
            .thenReturn(true);

        groupService.delete(1L, "delete-key");

        assertThatThrownBy(() -> groupService.delete(1L, "another-key"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.CONFLICT);

        authenticate(9L, "USER");
        assertThatThrownBy(() -> groupService.delete(1L, "delete-key"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.NOT_FOUND);

        verify(groupMapper, never()).updateById(deleted);
        verify(eventService, never()).record(any());
    }

    @Test
    void oldJoinKeyCannotReenterAfterJoinAndLeaveCycle() {
        CommunityGroup group = group(1L, 8L, "PLATFORM_VISIBLE");
        CommunityGroupMember member = member(1L, USER_ID, "MEMBER");
        member.status = "LEFT";
        member.lastIdempotencyKey = "previous-transition";
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(group);
        when(blockMapper.countActiveEitherDirection(USER_ID, 8L)).thenReturn(0L);
        when(memberMapper.findByGroupAndUserForUpdate(1L, USER_ID)).thenReturn(member);
        when(eventService.isRecorded(
            CommunitySourceType.USER, "MEMBERSHIP_REQUESTED", "MEMBER", member.id, USER_ID,
            "JOIN_GROUP:1:old-join"))
            .thenReturn(false, true);

        groupService.join(1L, "old-join");
        groupService.leave(1L, "leave-key");

        assertThatThrownBy(() -> groupService.join(1L, "old-join"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("入组请求已处理")
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.CONFLICT);

        assertThat(member.status).isEqualTo("LEFT");
        verify(memberMapper, times(2)).updateById(member);
    }

    @Test
    void oldRemoveKeyCannotRemoveMemberAgainAfterRejoin() {
        CommunityGroup group = group(1L, USER_ID, "PLATFORM_VISIBLE");
        CommunityGroupMember owner = member(1L, USER_ID, "OWNER");
        CommunityGroupMember target = member(1L, 9L, "MEMBER");
        target.id = 109L;
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(group);
        when(memberMapper.findByGroupAndUserForUpdate(1L, USER_ID)).thenReturn(owner);
        when(memberMapper.selectById(109L)).thenReturn(target);
        when(memberMapper.findByGroupAndUserForUpdate(1L, 9L)).thenReturn(target);
        when(blockMapper.countActiveEitherDirection(9L, USER_ID)).thenReturn(0L);
        String removeKey = new CommunityTextPolicy().scopedIdempotencyKey("REMOVE_MEMBER", "old-remove");
        when(eventService.isRecorded(
            CommunitySourceType.GROUP_ADMIN, "MEMBER_REMOVED", "MEMBER", 109L, USER_ID, removeKey))
            .thenReturn(false, true);

        groupService.removeMember(1L, 109L, "old-remove");
        authenticate(9L, "USER");
        groupService.join(1L, "rejoin-key");
        authenticate(USER_ID, "USER");

        assertThatThrownBy(() -> groupService.removeMember(1L, 109L, "old-remove"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("成员移除已处理")
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.CONFLICT);

        assertThat(target.status).isEqualTo("ACTIVE");
        verify(memberMapper, times(2)).updateById(target);
    }

    @Test
    void oldBlockKeyCannotBlockAgainAfterUnblock() {
        SysUser target = new SysUser();
        target.id = 9L;
        target.status = "ACTIVE";
        target.deleted = 0;
        CommunityUserBlock block = new CommunityUserBlock();
        block.id = 19L;
        block.blockerUserId = USER_ID;
        block.blockedUserId = 9L;
        block.status = "REMOVED";
        block.lastIdempotencyKey = "previous-transition";
        when(userMapper.selectById(9L)).thenReturn(target);
        when(blockMapper.findPairForUpdate(USER_ID, 9L)).thenReturn(block);
        String blockKey = new CommunityTextPolicy().scopedIdempotencyKey("BLOCK", "old-block");
        when(eventService.isRecorded(
            CommunitySourceType.USER, "USER_BLOCKED", "BLOCK", 19L, USER_ID, blockKey))
            .thenReturn(false, true);

        contentService.block(9L, "old-block");
        contentService.unblock(9L, "unblock-key");

        assertThatThrownBy(() -> contentService.block(9L, "old-block"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("屏蔽请求已处理")
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.CONFLICT);

        assertThat(block.status).isEqualTo("REMOVED");
        verify(blockMapper, times(2)).updateById(block);
    }

    @Test
    void platformModerationUpgradesGroupHiddenContentWithoutShorteningRetention() {
        CurrentUser operator = new CurrentUser(USER_ID, "content-operator", Set.of(), Set.of());
        LocalDateTime hiddenAt = LocalDateTime.now().minusDays(12);
        LocalDateTime deletedAt = hiddenAt.plusHours(1);
        LocalDateTime retainUntil = LocalDateTime.now().plusDays(240);
        CommunityPost post = post(20L, 1L, 8L, "HIDDEN");
        post.hiddenScope = "GROUP";
        post.hiddenAt = hiddenAt;
        post.deletedAt = deletedAt;
        post.retainUntil = retainUntil;
        CommunityComment comment = new CommunityComment();
        comment.id = 31L;
        comment.postId = 20L;
        comment.groupId = 1L;
        comment.authorUserId = 9L;
        comment.status = "HIDDEN";
        comment.hiddenScope = "GROUP";
        comment.hiddenAt = hiddenAt;
        comment.deletedAt = deletedAt;
        comment.retainUntil = retainUntil;
        comment.versionNo = 0L;

        contentService.moderatePost(
            post, false, operator, CommunityActorScope.CONTENT, CommunitySourceType.CONTENT_OPERATOR,
            "PLATFORM_POLICY", "post-upgrade", null);
        contentService.moderateComment(
            comment, false, operator, CommunityActorScope.CONTENT, CommunitySourceType.CONTENT_OPERATOR,
            "PLATFORM_POLICY", "comment-upgrade", null);

        assertThat(post.hiddenScope).isEqualTo("PLATFORM");
        assertThat(comment.hiddenScope).isEqualTo("PLATFORM");
        assertThat(post.hiddenAt).isEqualTo(hiddenAt);
        assertThat(comment.hiddenAt).isEqualTo(hiddenAt);
        assertThat(post.deletedAt).isEqualTo(deletedAt);
        assertThat(comment.deletedAt).isEqualTo(deletedAt);
        assertThat(post.retainUntil).isEqualTo(retainUntil);
        assertThat(comment.retainUntil).isEqualTo(retainUntil);

        assertThatThrownBy(() -> contentService.moderatePost(
            post, false, operator, CommunityActorScope.CONTENT, CommunitySourceType.CONTENT_OPERATOR,
            "ANOTHER_REASON", "another-hide", null))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.CONFLICT);
        assertThatThrownBy(() -> contentService.moderateComment(
            comment, true, operator, CommunityActorScope.GROUP, CommunitySourceType.GROUP_ADMIN,
            "SAFE", "group-restore", null))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.FORBIDDEN);

        verify(postMapper).updateById(post);
        verify(commentMapper).updateById(comment);
    }

    @Test
    void crossGroupPathCannotBeUsedToModerateAContentIdFromAnotherGroup() {
        CommunityComment comment = new CommunityComment();
        comment.id = 31L;
        comment.groupId = 2L;
        comment.postId = 20L;
        comment.authorUserId = 8L;
        comment.status = "PUBLISHED";
        comment.versionNo = 0L;
        when(groupMapper.selectByIdForUpdate(1L)).thenReturn(group(1L, 8L, "PLATFORM_VISIBLE"));
        when(commentMapper.selectById(31L)).thenReturn(comment);

        assertThatThrownBy(
            () -> contentService.groupModerate(1L, "COMMENT", 31L, false, moderation("OFF_TOPIC"), "cross"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.NOT_FOUND);

        verifyNoInteractions(eventService);
        verify(commentMapper, never()).updateById(any(CommunityComment.class));
    }

    @Test
    void eitherDirectionOfAUserBlockMakesContentInvisible() throws Exception {
        Method method = CommunityUserBlockMapper.class.getMethod(
            "countActiveEitherDirection", Long.class, Long.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());
        assertThat(sql)
            .contains("blocker_user_id = #{firstUserId} and blocked_user_id = #{secondUserId}")
            .contains("blocker_user_id = #{secondUserId} and blocked_user_id = #{firstUserId}");

        // 双向屏蔽由详情聚合 SQL 谓词收口：查询返回 null 即 404，不再回退到实体加载。
        when(postMapper.findVisibleDetail(USER_ID, 20L)).thenReturn(null);

        assertThatThrownBy(() -> contentService.getPost(20L))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.NOT_FOUND);
        verify(groupMapper, never()).selectById(any());
        verify(postMapper, never()).selectById(any());
    }

    @Test
    void repeatedCreatePostKeyReturnsTheSameResultOnlyForTheSameParameters() {
        CommunityRequests.CreatePost original = createPost(1L, "学习计划", "周末复习两小时");
        CommunityTextPolicy policy = new CommunityTextPolicy();
        CommunityPost existing = post(20L, 1L, USER_ID, "PUBLISHED");
        existing.idempotencyKey = "same-key";
        existing.requestHash = policy.fingerprint(1L, original.title, original.content);
        when(postMapper.findByAuthorAndIdempotency(USER_ID, "same-key")).thenReturn(existing);

        assertThat(contentService.createPost(original, "same-key")).isSameAs(existing);

        CommunityRequests.CreatePost changed = createPost(1L, "另一标题", original.content);
        assertThatThrownBy(() -> contentService.createPost(changed, "same-key"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.CONFLICT);

        verify(postMapper, never()).insert(any(CommunityPost.class));
        verifyNoInteractions(rateLimitService);
    }

    @Test
    void createGroupRequiresAKnownCategoryFromTheDictionary() {
        CommunityRequests.CreateGroup blank = createGroup("晨读会");
        blank.category = " ";
        assertThatThrownBy(() -> groupService.create(blank, "key-cat-blank"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("小组类别不能为空");

        CommunityRequests.CreateGroup unknown = createGroup("晨读会");
        unknown.category = "MAGIC";
        when(dictDataMapper.findByType(CommunityGroupService.GROUP_CATEGORY_DICT_TYPE))
            .thenReturn(List.of(dictValue("STUDY"), dictValue("SPORTS")));
        assertThatThrownBy(() -> groupService.create(unknown, "key-cat-unknown"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("小组类别不合法");

        verify(groupMapper, never()).insert(any(CommunityGroup.class));
        verify(memberMapper, never()).insert(any(CommunityGroupMember.class));
    }

    @Test
    void createGroupPersistsCategoryAndBindsItToRequestIdempotency() {
        when(dictDataMapper.findByType(CommunityGroupService.GROUP_CATEGORY_DICT_TYPE))
            .thenReturn(List.of(dictValue("STUDY"), dictValue("SPORTS")));

        CommunityRequests.CreateGroup request = createGroup("晨读会");
        request.category = "STUDY";
        CommunityGroup created = groupService.create(request, "key-cat-create");

        assertThat(created.categoryCode).isEqualTo("STUDY");

        // 同一 Idempotency-Key 下换类别视为不同请求：指纹必须包含 category。
        CommunityTextPolicy policy = new CommunityTextPolicy();
        CommunityGroup winner = group(1L, USER_ID, "PLATFORM_VISIBLE");
        winner.idempotencyKey = "key-cat-create";
        winner.requestHash = policy.fingerprint("晨读会", null, "PLATFORM_VISIBLE", "DIRECT", "SPORTS");
        when(groupMapper.findByOwnerAndIdempotency(USER_ID, "key-cat-create")).thenReturn(winner);

        assertThatThrownBy(() -> groupService.create(createGroup("晨读会"), "key-cat-create"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    void searchNormalizesCategoryFilterAndRoutesMineQueriesToMembershipDimension() {
        CommunityRequests.GroupQuery mineOther = new CommunityRequests.GroupQuery();
        mineOther.category = " other ";
        mineOther.mine = true;
        when(groupMapper.searchMine(eq(USER_ID), isNull(), eq("OTHER"), eq("ACTIVE"), isNull(), anyInt()))
            .thenReturn(List.of());

        assertThat(groupService.search(mineOther).records()).isEmpty();
        verify(groupMapper).searchMine(eq(USER_ID), isNull(), eq("OTHER"), eq("ACTIVE"), isNull(), eq(21));

        CommunityRequests.GroupQuery byCategory = new CommunityRequests.GroupQuery();
        byCategory.category = "SPORTS";
        when(groupMapper.searchVisible(eq(USER_ID), isNull(), eq("SPORTS"), isNull(), eq(21)))
            .thenReturn(List.of());
        groupService.search(byCategory);
        verify(groupMapper).searchVisible(eq(USER_ID), isNull(), eq("SPORTS"), isNull(), eq(21));

        // 未指定 mine 时不得走成员维度查询。
        verify(groupMapper, times(1)).searchMine(any(), any(), any(), any(), any(), anyInt());
        verify(groupMapper, times(1)).searchVisible(any(), any(), any(), any(), anyInt());
    }

    @Test
    void mineMemberStatusIsValidatedAndForwardedToTheMembershipQuery() {
        CommunityRequests.GroupQuery pending = new CommunityRequests.GroupQuery();
        pending.mine = true;
        pending.memberStatus = " pending ";
        when(groupMapper.searchMine(eq(USER_ID), isNull(), isNull(), eq("PENDING"), isNull(), anyInt()))
            .thenReturn(List.of());
        assertThat(groupService.search(pending).records()).isEmpty();
        verify(groupMapper).searchMine(eq(USER_ID), isNull(), isNull(), eq("PENDING"), isNull(), eq(21));

        CommunityRequests.GroupQuery any = new CommunityRequests.GroupQuery();
        any.mine = true;
        any.memberStatus = "ANY";
        when(groupMapper.searchMine(eq(USER_ID), isNull(), isNull(), eq("ANY"), isNull(), anyInt()))
            .thenReturn(List.of());
        assertThat(groupService.search(any).records()).isEmpty();
        verify(groupMapper).searchMine(eq(USER_ID), isNull(), isNull(), eq("ANY"), isNull(), eq(21));

        // memberStatus 必须配合 mine=true；REJECTED/LEFT/REMOVED 不对用户侧开放枚举。
        CommunityRequests.GroupQuery withoutMine = new CommunityRequests.GroupQuery();
        withoutMine.memberStatus = "PENDING";
        assertThatThrownBy(() -> groupService.search(withoutMine))
            .isInstanceOf(BusinessException.class)
            .hasMessage("memberStatus 需配合 mine=true 使用");

        CommunityRequests.GroupQuery removed = new CommunityRequests.GroupQuery();
        removed.mine = true;
        removed.memberStatus = "REMOVED";
        assertThatThrownBy(() -> groupService.search(removed))
            .isInstanceOf(BusinessException.class)
            .hasMessage("成员状态筛选不合法");
    }

    @Test
    void postSearchValidatesVisibilityScopeAndNormalizesCategory() {
        CommunityRequests.PostQuery feed = new CommunityRequests.PostQuery();
        feed.visibilityScope = " platform_visible ";
        feed.category = " other ";
        when(postMapper.searchVisible(eq(USER_ID), isNull(), isNull(), isNull(), eq("OTHER"), eq(true), isNull(), anyInt()))
            .thenReturn(List.of());
        assertThat(contentService.searchPosts(feed).records()).isEmpty();
        verify(postMapper).searchVisible(eq(USER_ID), isNull(), isNull(), isNull(), eq("OTHER"), eq(true), isNull(), eq(21));

        CommunityRequests.PostQuery illegalScope = new CommunityRequests.PostQuery();
        illegalScope.visibilityScope = "MEMBERS_ONLY";
        assertThatThrownBy(() -> contentService.searchPosts(illegalScope))
            .isInstanceOf(BusinessException.class)
            .hasMessage("可见范围筛选不合法");
    }

    @Test
    void getPostReturnsTheAggregatedRowWithAuthorAndGroupFields() {
        CommunityQueryRows.PostRow row = new CommunityQueryRows.PostRow();
        row.id = 20L;
        row.groupId = 2L;
        row.groupName = "窗边共学";
        row.groupVisibility = "MEMBERS_ONLY";
        row.authorUserId = 8L;
        row.authorNickname = "乔木";
        row.title = "周三晚上的共读安排";
        when(postMapper.findVisibleDetail(USER_ID, 20L)).thenReturn(row);

        assertThat(contentService.getPost(20L)).isSameAs(row);
    }

    @Test
    void moderationStatusListingIsGuardedAndRoutedToTheModerationQuery() {
        CommunityRequests.PostQuery withoutGroup = new CommunityRequests.PostQuery();
        withoutGroup.status = "HIDDEN";
        assertThatThrownBy(() -> contentService.searchPosts(withoutGroup))
            .isInstanceOf(BusinessException.class)
            .hasMessage("内容状态筛选需配合 groupId 使用");

        CommunityRequests.PostQuery illegal = new CommunityRequests.PostQuery();
        illegal.groupId = 1L;
        illegal.status = "DELETED";
        assertThatThrownBy(() -> contentService.searchPosts(illegal))
            .isInstanceOf(BusinessException.class)
            .hasMessage("内容状态筛选不合法");

        // 普通成员查询 HIDDEN/ALL：requireGroupManager 收口，403 且不下发治理查询。
        when(groupMapper.selectById(1L)).thenReturn(group(1L, 8L, "PLATFORM_VISIBLE"));
        when(memberMapper.findByGroupAndUser(1L, USER_ID))
            .thenReturn(member(1L, USER_ID, "MEMBER"));
        CommunityRequests.PostQuery hidden = new CommunityRequests.PostQuery();
        hidden.groupId = 1L;
        hidden.status = "HIDDEN";
        assertForbidden(() -> contentService.searchPosts(hidden), "仅组主或组管理员可执行该操作");
        verify(postMapper, never()).listForModeration(any(), any(), any(), anyInt());

        // OWNER 放行：路由到组内治理查询并透传游标边界。
        when(memberMapper.findByGroupAndUser(1L, USER_ID))
            .thenReturn(member(1L, USER_ID, "OWNER"));
        when(postMapper.listForModeration(eq(1L), eq("ALL"), isNull(), anyInt()))
            .thenReturn(List.of());
        CommunityRequests.PostQuery all = new CommunityRequests.PostQuery();
        all.groupId = 1L;
        all.status = "ALL";
        assertThat(contentService.searchPosts(all).records()).isEmpty();
        verify(postMapper).listForModeration(eq(1L), eq("ALL"), isNull(), eq(21));
    }

    private SysDictData dictValue(String value) {
        SysDictData data = new SysDictData();
        data.dictType = CommunityGroupService.GROUP_CATEGORY_DICT_TYPE;
        data.value = value;
        data.status = "ACTIVE";
        return data;
    }

    private CommunityRequests.CreateGroup createGroup(String name) {
        CommunityRequests.CreateGroup request = new CommunityRequests.CreateGroup();
        request.name = name;
        request.visibility = "PLATFORM_VISIBLE";
        request.joinPolicy = "DIRECT";
        request.category = "STUDY";
        return request;
    }

    private void assertForbidden(Runnable action, String message) {
        assertThatThrownBy(action::run)
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(message)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.FORBIDDEN);
    }

    private void assertHidden(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    private void authenticate(Long id, String role) {
        CurrentUser currentUser = new CurrentUser(id, "community-user-" + id, Set.of(role), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(currentUser, null, List.of()));
    }

    private CommunityGroup group(Long id, Long ownerUserId, String visibility) {
        CommunityGroup group = new CommunityGroup();
        group.id = id;
        group.ownerUserId = ownerUserId;
        group.visibility = visibility;
        group.joinPolicy = "DIRECT";
        group.status = "ACTIVE";
        group.versionNo = 0L;
        return group;
    }

    private CommunityGroupMember member(Long groupId, Long userId, String role) {
        CommunityGroupMember member = new CommunityGroupMember();
        member.id = groupId * 100 + userId;
        member.groupId = groupId;
        member.userId = userId;
        member.memberRole = role;
        member.status = "ACTIVE";
        return member;
    }

    private CommunityPost post(Long id, Long groupId, Long authorUserId, String status) {
        CommunityPost post = new CommunityPost();
        post.id = id;
        post.groupId = groupId;
        post.authorUserId = authorUserId;
        post.status = status;
        post.versionNo = 0L;
        return post;
    }

    private CommunityRequests.CreatePost createPost(Long groupId, String title, String content) {
        CommunityRequests.CreatePost request = new CommunityRequests.CreatePost();
        request.groupId = groupId;
        request.title = title;
        request.content = content;
        return request;
    }

    private CommunityRequests.ModerationAction moderation(String reasonCode) {
        CommunityRequests.ModerationAction request = new CommunityRequests.ModerationAction();
        request.reasonCode = reasonCode;
        return request;
    }

    private CommunityRequests.ChangeMemberRole changeRole(String role) {
        CommunityRequests.ChangeMemberRole request = new CommunityRequests.ChangeMemberRole();
        request.role = role;
        return request;
    }
}
