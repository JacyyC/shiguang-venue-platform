package com.example.venue.community.dto;

import com.example.venue.community.entity.CommunityComment;
import com.example.venue.community.entity.CommunityGroup;
import com.example.venue.community.entity.CommunityGroupMember;
import com.example.venue.community.entity.CommunityPost;
import com.example.venue.community.entity.CommunityReport;

import java.time.LocalDateTime;

public final class CommunityResponses {
    private CommunityResponses() {
    }

    public static Group group(CommunityGroup source) {
        Group target = new Group();
        target.id = source.id;
        target.groupNo = source.groupNo;
        target.name = source.name;
        target.description = source.description;
        target.ownerUserId = source.ownerUserId;
        target.visibility = source.visibility;
        target.joinPolicy = source.joinPolicy;
        target.categoryCode = source.categoryCode;
        target.status = source.status;
        target.versionNo = source.versionNo;
        target.createTime = source.createTime;
        target.updateTime = source.updateTime;
        return target;
    }

    public static Group group(CommunityQueryRows.GroupRow source) {
        Group target = new Group();
        target.id = source.id;
        target.groupNo = source.groupNo;
        target.name = source.name;
        target.description = source.description;
        target.ownerUserId = source.ownerUserId;
        target.owner = user(source.ownerUserId, source.ownerNickname, source.ownerAvatar);
        target.visibility = source.visibility;
        target.joinPolicy = source.joinPolicy;
        target.categoryCode = source.categoryCode;
        target.status = source.status;
        target.currentMemberRole = source.currentMemberRole;
        target.currentMemberStatus = source.currentMemberStatus;
        target.memberCount = source.memberCount;
        target.versionNo = source.versionNo;
        target.createTime = source.createTime;
        target.updateTime = source.updateTime;
        return target;
    }

    public static Member member(CommunityGroupMember source) {
        Member target = new Member();
        target.id = source.id;
        target.groupId = source.groupId;
        target.userId = source.userId;
        target.role = source.memberRole;
        target.status = source.status;
        target.joinedAt = source.joinedAt;
        target.createTime = source.createTime;
        return target;
    }

    public static Member member(CommunityQueryRows.MemberRow source) {
        Member target = new Member();
        target.id = source.id;
        target.groupId = source.groupId;
        target.userId = source.userId;
        target.user = user(source.userId, source.nickname, source.avatar);
        target.role = source.memberRole;
        target.status = source.status;
        target.joinedAt = source.joinedAt;
        target.createTime = source.createTime;
        return target;
    }

    public static Post post(CommunityPost source) {
        Post target = new Post();
        target.id = source.id;
        target.postNo = source.postNo;
        target.groupId = source.groupId;
        target.authorUserId = source.authorUserId;
        target.title = source.title;
        target.content = source.contentText;
        target.status = source.status;
        target.versionNo = source.versionNo;
        target.publishedAt = source.publishedAt;
        target.createTime = source.createTime;
        target.updateTime = source.updateTime;
        return target;
    }

    public static Post post(CommunityQueryRows.PostRow source) {
        Post target = new Post();
        target.id = source.id;
        target.postNo = source.postNo;
        target.groupId = source.groupId;
        target.groupName = source.groupName;
        target.groupVisibility = source.groupVisibility;
        target.groupCategory = source.groupCategory;
        target.authorUserId = source.authorUserId;
        target.author = user(source.authorUserId, source.authorNickname, source.authorAvatar);
        target.title = source.title;
        target.content = source.contentText;
        target.status = source.status;
        target.versionNo = source.versionNo;
        target.publishedAt = source.publishedAt;
        target.createTime = source.createTime;
        target.updateTime = source.updateTime;
        return target;
    }

    public static Comment comment(CommunityComment source) {
        Comment target = new Comment();
        target.id = source.id;
        target.commentNo = source.commentNo;
        target.postId = source.postId;
        target.groupId = source.groupId;
        target.authorUserId = source.authorUserId;
        target.content = source.contentText;
        target.status = source.status;
        target.versionNo = source.versionNo;
        target.publishedAt = source.publishedAt;
        target.createTime = source.createTime;
        target.updateTime = source.updateTime;
        return target;
    }

    public static Comment comment(CommunityQueryRows.CommentRow source) {
        Comment target = new Comment();
        target.id = source.id;
        target.commentNo = source.commentNo;
        target.postId = source.postId;
        target.groupId = source.groupId;
        target.authorUserId = source.authorUserId;
        target.author = user(source.authorUserId, source.authorNickname, source.authorAvatar);
        target.content = source.contentText;
        target.status = source.status;
        target.versionNo = source.versionNo;
        target.publishedAt = source.publishedAt;
        target.createTime = source.createTime;
        target.updateTime = source.updateTime;
        return target;
    }

    public static UserSummary user(Long id, String nickname, String avatar) {
        if (id == null) {
            return new UserSummary(null, "已注销用户", null);
        }
        return new UserSummary(id, nickname == null || nickname.isBlank() ? "社区用户" : nickname, avatar);
    }

    public static UserReport userReport(CommunityReport source) {
        UserReport target = new UserReport();
        target.id = source.id;
        target.reportNo = source.reportNo;
        target.targetType = source.targetType;
        target.targetId = source.targetId;
        target.reason = source.reasonText;
        target.status = source.status;
        target.decisionCode = source.decisionCode;
        target.closedAt = source.closedAt;
        target.createTime = source.createTime;
        return target;
    }

    public static BlockedUser blockedUser(CommunityQueryRows.BlockRow source) {
        BlockedUser target = new BlockedUser();
        target.id = source.id;
        target.user = user(source.blockedUserId, source.nickname, source.avatar);
        target.createdAt = source.createdAt;
        return target;
    }

    public static AdminReport adminReport(CommunityQueryRows.ReportRow source) {
        AdminReport target = new AdminReport();
        target.id = source.id;
        target.reportNo = source.reportNo;
        target.reporter = user(source.reporterUserId, source.reporterNickname, source.reporterAvatar);
        target.targetType = source.targetType;
        target.targetId = source.targetId;
        target.reason = source.reasonText;
        target.status = source.status;
        target.assignedTo = source.assignedTo;
        target.decisionCode = source.decisionCode;
        target.internalNote = source.internalNote;
        target.closedAt = source.closedAt;
        target.createTime = source.createTime;
        target.updateTime = source.updateTime;
        return target;
    }

    public static AdminEvent adminEvent(CommunityQueryRows.EventRow source) {
        AdminEvent target = new AdminEvent();
        target.id = source.id;
        target.eventNo = source.eventNo;
        target.eventType = source.eventType;
        target.subjectType = source.subjectType;
        target.subjectId = source.subjectId;
        target.reportId = source.reportId;
        target.actorUserId = source.actorUserId;
        target.actorScope = source.actorScope;
        target.sourceType = source.sourceType;
        target.fromStatus = source.fromStatus;
        target.toStatus = source.toStatus;
        target.reasonCode = source.reasonCode;
        target.reasonSummary = source.reasonSummary;
        target.resultStatus = source.resultStatus;
        target.occurredAt = source.occurredAt;
        return target;
    }

    public static AdminContent adminContent(CommunityGroup source) {
        AdminContent target = new AdminContent();
        target.targetType = "GROUP";
        target.id = source.id;
        target.authorUserId = source.ownerUserId;
        target.title = source.name;
        target.content = source.description;
        target.status = source.status;
        target.hiddenAt = source.hiddenAt;
        target.deletedAt = source.deletedAt;
        target.retainUntil = source.retainUntil;
        target.versionNo = source.versionNo;
        return target;
    }

    public static AdminContent adminContent(CommunityPost source) {
        AdminContent target = new AdminContent();
        target.targetType = "POST";
        target.id = source.id;
        target.groupId = source.groupId;
        target.authorUserId = source.authorUserId;
        target.title = source.title;
        target.content = source.contentText;
        target.status = source.status;
        target.hiddenReasonCode = source.hiddenReasonCode;
        target.hiddenScope = source.hiddenScope;
        target.hiddenBy = source.hiddenBy;
        target.hiddenAt = source.hiddenAt;
        target.deletedAt = source.deletedAt;
        target.retainUntil = source.retainUntil;
        target.versionNo = source.versionNo;
        return target;
    }

    public static AdminContent adminContent(CommunityComment source) {
        AdminContent target = new AdminContent();
        target.targetType = "COMMENT";
        target.id = source.id;
        target.groupId = source.groupId;
        target.parentId = source.postId;
        target.authorUserId = source.authorUserId;
        target.content = source.contentText;
        target.status = source.status;
        target.hiddenReasonCode = source.hiddenReasonCode;
        target.hiddenScope = source.hiddenScope;
        target.hiddenBy = source.hiddenBy;
        target.hiddenAt = source.hiddenAt;
        target.deletedAt = source.deletedAt;
        target.retainUntil = source.retainUntil;
        target.versionNo = source.versionNo;
        return target;
    }

    public static class Group {
        public Long id;
        public String groupNo;
        public String name;
        public String description;
        public Long ownerUserId;
        public UserSummary owner;
        public String visibility;
        public String joinPolicy;
        public String categoryCode;
        public String status;
        public String currentMemberRole;
        public String currentMemberStatus;
        public Long memberCount;
        public Long versionNo;
        public LocalDateTime createTime;
        public LocalDateTime updateTime;
    }

    public static class Member {
        public Long id;
        public Long groupId;
        public Long userId;
        public UserSummary user;
        public String role;
        public String status;
        public LocalDateTime joinedAt;
        public LocalDateTime createTime;
    }

    public static class Post {
        public Long id;
        public String postNo;
        public Long groupId;
        public String groupName;
        /** 所属小组可见范围：PLATFORM_VISIBLE（广场可见）/ MEMBERS_ONLY（仅成员）。 */
        public String groupVisibility;
        /** 所属小组类别（字典 community_group_category；未分类为 null）。 */
        public String groupCategory;
        public Long authorUserId;
        public UserSummary author;
        public String title;
        public String content;
        public String status;
        public Long versionNo;
        public LocalDateTime publishedAt;
        public LocalDateTime createTime;
        public LocalDateTime updateTime;
    }

    public static class Comment {
        public Long id;
        public String commentNo;
        public Long postId;
        public Long groupId;
        public Long authorUserId;
        public UserSummary author;
        public String content;
        public String status;
        public Long versionNo;
        public LocalDateTime publishedAt;
        public LocalDateTime createTime;
        public LocalDateTime updateTime;
    }

    public static class UserReport {
        public Long id;
        public String reportNo;
        public String targetType;
        public Long targetId;
        public String reason;
        public String status;
        public String decisionCode;
        public LocalDateTime closedAt;
        public LocalDateTime createTime;
    }

    public static class BlockedUser {
        public Long id;
        public UserSummary user;
        public LocalDateTime createdAt;
    }

    public static class AdminReport {
        public Long id;
        public String reportNo;
        public UserSummary reporter;
        public String targetType;
        public Long targetId;
        public String reason;
        public String status;
        public Long assignedTo;
        public String decisionCode;
        public String internalNote;
        public LocalDateTime closedAt;
        public LocalDateTime createTime;
        public LocalDateTime updateTime;
    }

    public static class AdminEvent {
        public Long id;
        public String eventNo;
        public String eventType;
        public String subjectType;
        public Long subjectId;
        public Long reportId;
        public Long actorUserId;
        public String actorScope;
        public String sourceType;
        public String fromStatus;
        public String toStatus;
        public String reasonCode;
        public String reasonSummary;
        public String resultStatus;
        public LocalDateTime occurredAt;
    }

    public static class AdminContent {
        public String targetType;
        public Long id;
        public Long groupId;
        public Long parentId;
        public Long authorUserId;
        public String title;
        public String content;
        public String status;
        public String hiddenReasonCode;
        /**
         * 隐藏作用域与执行者（SHIGUANG-BATCH-04-REFACTOR-20260914 S03 / B4-G4）。
         *
         * <p>{@code hidden_scope ∈ (GROUP, PLATFORM)} 与 {@code hidden_by} 是既有持久化列
         * （仅 POST/COMMENT 有；小组实体无这两列，故 GROUP 分支保持 null），但此前不出参，
         * 导致平台治理的检查面无法判别「组内隐藏」与「平台隐藏」的来源——而只有
         * {@code community:content:moderate} 无 {@code community:audit:view} 的运营者
         * 又不能借审计面弥补。此处仅补只读字段，不改状态机、可见性与权限语义。</p>
         */
        public String hiddenScope;
        public Long hiddenBy;
        public LocalDateTime hiddenAt;
        public LocalDateTime deletedAt;
        public LocalDateTime retainUntil;
        public Long versionNo;
    }

    public record UserSummary(Long id, String nickname, String avatar) {
    }
}
