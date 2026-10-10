package com.example.venue.community.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.community.entity.CommunityComment;
import com.example.venue.community.entity.CommunityGroup;
import com.example.venue.community.entity.CommunityGroupMember;
import com.example.venue.community.entity.CommunityPost;
import com.example.venue.community.enums.CommunityContentStatus;
import com.example.venue.community.enums.CommunityGroupStatus;
import com.example.venue.community.enums.CommunityMemberStatus;
import com.example.venue.community.mapper.CommunityCommentMapper;
import com.example.venue.community.mapper.CommunityGroupMapper;
import com.example.venue.community.mapper.CommunityGroupMemberMapper;
import com.example.venue.community.mapper.CommunityPostMapper;
import com.example.venue.community.mapper.CommunityUserBlockMapper;
import com.example.venue.security.SecurityContext;
import org.springframework.stereotype.Service;

@Service
public class CommunityAccessService {
    private final CommunityGroupMapper groupMapper;
    private final CommunityGroupMemberMapper memberMapper;
    private final CommunityPostMapper postMapper;
    private final CommunityCommentMapper commentMapper;
    private final CommunityUserBlockMapper blockMapper;

    public CommunityAccessService(CommunityGroupMapper groupMapper,
                                  CommunityGroupMemberMapper memberMapper,
                                  CommunityPostMapper postMapper,
                                  CommunityCommentMapper commentMapper,
                                  CommunityUserBlockMapper blockMapper) {
        this.groupMapper = groupMapper;
        this.memberMapper = memberMapper;
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.blockMapper = blockMapper;
    }

    public CurrentUser requireCurrentUser() {
        CurrentUser user = SecurityContext.currentUser();
        if (user == null || user.id() == null) {
            throw BusinessException.forbidden("请先登录");
        }
        return user;
    }

    public CommunityGroup requireVisibleGroup(Long groupId, Long userId) {
        return requireVisibleGroup(groupMapper.selectById(groupId), userId);
    }

    public CommunityGroup requireVisibleGroupForUpdate(Long groupId, Long userId) {
        return requireVisibleGroup(groupMapper.selectByIdForUpdate(groupId), userId);
    }

    private CommunityGroup requireVisibleGroup(CommunityGroup group, Long userId) {
        if (group == null || !CommunityGroupStatus.ACTIVE.matches(group.status)
            || isBlocked(userId, group.ownerUserId)) {
            throw hiddenResource();
        }
        return group;
    }

    public CommunityGroup requireActiveGroup(Long groupId) {
        CommunityGroup group = groupMapper.selectById(groupId);
        if (group == null || !CommunityGroupStatus.ACTIVE.matches(group.status)) {
            throw hiddenResource();
        }
        return group;
    }

    public CommunityGroup requireActiveGroupForUpdate(Long groupId) {
        CommunityGroup group = groupMapper.selectByIdForUpdate(groupId);
        if (group == null || !CommunityGroupStatus.ACTIVE.matches(group.status)) {
            throw hiddenResource();
        }
        return group;
    }

    public CommunityGroupMember requireActiveMember(Long groupId, Long userId) {
        return requireActiveMember(memberMapper.findByGroupAndUser(groupId, userId));
    }

    public CommunityGroupMember requireActiveMemberForUpdate(Long groupId, Long userId) {
        return requireActiveMember(memberMapper.findByGroupAndUserForUpdate(groupId, userId));
    }

    private CommunityGroupMember requireActiveMember(CommunityGroupMember member) {
        if (member == null || !CommunityMemberStatus.ACTIVE.matches(member.status)) {
            throw BusinessException.forbidden("需要有效的小组成员身份");
        }
        return member;
    }

    public CommunityGroupMember requireGroupManager(Long groupId, Long userId) {
        requireActiveGroup(groupId);
        CommunityGroupMember member = requireActiveMember(groupId, userId);
        return requireManagerRole(member);
    }

    public CommunityGroupMember requireGroupManagerForUpdate(Long groupId, Long userId) {
        return requireManagerRole(requireActiveMemberForUpdate(groupId, userId));
    }

    private CommunityGroupMember requireManagerRole(CommunityGroupMember member) {
        if (!"OWNER".equals(member.memberRole) && !"ADMIN".equals(member.memberRole)) {
            throw BusinessException.forbidden("仅组主或组管理员可执行该操作");
        }
        return member;
    }

    public boolean isActiveMember(Long groupId, Long userId) {
        CommunityGroupMember member = memberMapper.findByGroupAndUser(groupId, userId);
        return member != null && CommunityMemberStatus.ACTIVE.matches(member.status);
    }

    public CommunityPost requireVisiblePost(Long postId, Long userId) {
        CommunityPost post = postMapper.selectById(postId);
        if (post == null || !CommunityContentStatus.PUBLISHED.matches(post.status)
            || isBlocked(userId, post.authorUserId)) {
            throw hiddenResource();
        }
        CommunityGroup group = requireVisibleGroup(post.groupId, userId);
        if ("MEMBERS_ONLY".equals(group.visibility) && !isActiveMember(group.id, userId)) {
            throw hiddenResource();
        }
        return post;
    }

    public CommunityPost requirePublishedPostForUpdate(Long postId, Long groupId, Long userId) {
        CommunityPost post = postMapper.selectByIdForUpdate(postId);
        if (post == null || !groupId.equals(post.groupId)
            || !CommunityContentStatus.PUBLISHED.matches(post.status)
            || isBlocked(userId, post.authorUserId)) {
            throw hiddenResource();
        }
        return post;
    }

    public CommunityComment requirePublishedCommentForUpdate(Long commentId, Long postId,
                                                              Long groupId, Long userId) {
        CommunityComment comment = commentMapper.selectByIdForUpdate(commentId);
        if (comment == null || !postId.equals(comment.postId) || !groupId.equals(comment.groupId)
            || !CommunityContentStatus.PUBLISHED.matches(comment.status)
            || isBlocked(userId, comment.authorUserId)) {
            throw hiddenResource();
        }
        return comment;
    }

    public CommunityComment requireVisibleComment(Long commentId, Long userId) {
        CommunityComment comment = commentMapper.selectById(commentId);
        if (comment == null || !CommunityContentStatus.PUBLISHED.matches(comment.status)
            || isBlocked(userId, comment.authorUserId)) {
            throw hiddenResource();
        }
        requireVisiblePost(comment.postId, userId);
        return comment;
    }

    public void requireNoBlock(Long firstUserId, Long secondUserId) {
        if (isBlocked(firstUserId, secondUserId)) {
            throw hiddenResource();
        }
    }

    public CommunityGroup requireActivityContext(Long groupId, Long referencePostId, Long userId) {
        CommunityGroup group = requireVisibleGroup(groupId, userId);
        if ("MEMBERS_ONLY".equals(group.visibility) && !isActiveMember(groupId, userId)) {
            throw hiddenResource();
        }
        if (referencePostId != null) {
            CommunityPost post = requireVisiblePost(referencePostId, userId);
            if (!groupId.equals(post.groupId)) {
                throw hiddenResource();
            }
        }
        return group;
    }

    public CommunityGroup requireActivityCreationContext(Long groupId, Long referencePostId, Long userId) {
        CommunityGroup group = requireActivityContext(groupId, referencePostId, userId);
        requireActiveMember(groupId, userId);
        return group;
    }

    public CommunityGroup requireActivityContextForUpdate(Long groupId, Long referencePostId, Long userId) {
        CommunityGroup group = requireVisibleGroupForUpdate(groupId, userId);
        if (referencePostId != null) {
            requirePublishedPostForUpdate(referencePostId, groupId, userId);
        }
        if ("MEMBERS_ONLY".equals(group.visibility)) {
            requireActiveMemberForUpdate(groupId, userId);
        }
        return group;
    }

    public boolean isBlocked(Long firstUserId, Long secondUserId) {
        return firstUserId != null && secondUserId != null && !firstUserId.equals(secondUserId)
            && blockMapper.countActiveEitherDirection(firstUserId, secondUserId) > 0;
    }

    public BusinessException hiddenResource() {
        return BusinessException.notFound("社区资源不存在");
    }
}
