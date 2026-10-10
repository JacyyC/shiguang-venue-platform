package com.example.venue.community.dto;

import java.time.LocalDateTime;

public final class CommunityQueryRows {
    private CommunityQueryRows() {
    }

    public static class GroupRow {
        public Long id;
        public String groupNo;
        public String name;
        public String description;
        public Long ownerUserId;
        public String ownerNickname;
        public String ownerAvatar;
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

    public static class MemberRow {
        public Long id;
        public Long groupId;
        public Long userId;
        public String nickname;
        public String avatar;
        public String memberRole;
        public String status;
        public LocalDateTime joinedAt;
        public LocalDateTime createTime;
    }

    public static class PostRow {
        public Long id;
        public String postNo;
        public Long groupId;
        public String groupName;
        /** 所属小组可见范围：PLATFORM_VISIBLE（广场可见）/ MEMBERS_ONLY（仅成员），走查 2026-08-30 F-04 透出。 */
        public String groupVisibility;
        /** 所属小组类别（字典 community_group_category；未分类为 null），社区 V1 帖子行静物场景派生使用。 */
        public String groupCategory;
        public Long authorUserId;
        public String authorNickname;
        public String authorAvatar;
        public String title;
        public String contentText;
        public String status;
        public Long versionNo;
        public LocalDateTime publishedAt;
        public LocalDateTime createTime;
        public LocalDateTime updateTime;
    }

    public static class CommentRow {
        public Long id;
        public String commentNo;
        public Long postId;
        public Long groupId;
        public Long authorUserId;
        public String authorNickname;
        public String authorAvatar;
        public String contentText;
        public String status;
        public Long versionNo;
        public LocalDateTime publishedAt;
        public LocalDateTime createTime;
        public LocalDateTime updateTime;
    }

    public static class BlockRow {
        public Long id;
        public Long blockedUserId;
        public String nickname;
        public String avatar;
        public LocalDateTime createdAt;
    }

    public static class ReportRow {
        public Long id;
        public String reportNo;
        public Long reporterUserId;
        public String reporterNickname;
        public String reporterAvatar;
        public String targetType;
        public Long targetId;
        public String reasonText;
        public String status;
        public Long assignedTo;
        public String decisionCode;
        public String internalNote;
        public LocalDateTime closedAt;
        public LocalDateTime createTime;
        public LocalDateTime updateTime;
    }

    public static class EventRow {
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

    public static class RetentionCandidateRow {
        public Long id;
        public String status;
    }
}
