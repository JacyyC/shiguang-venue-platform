package com.example.venue.community.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CommunityRequests {
    private CommunityRequests() {
    }

    public static class CreateGroup {
        @NotBlank
        @Size(max = 40)
        public String name;
        @Size(max = 300)
        public String description;
        @NotBlank
        public String visibility;
        @NotBlank
        public String joinPolicy;
        @NotBlank
        public String category;
    }

    public static class UpdateGroup extends CreateGroup {
        @NotNull
        @Min(0)
        public Long versionNo;
    }

    public static class CreatePost {
        @NotNull
        public Long groupId;
        @NotBlank
        @Size(max = 80)
        public String title;
        @NotBlank
        @Size(max = 5000)
        public String content;
    }

    public static class UpdatePost {
        @NotBlank
        @Size(max = 80)
        public String title;
        @NotBlank
        @Size(max = 5000)
        public String content;
        @NotNull
        @Min(0)
        public Long versionNo;
    }

    public static class CreateComment {
        @NotBlank
        @Size(max = 1000)
        public String content;
    }

    public static class UpdateComment extends CreateComment {
        @NotNull
        @Min(0)
        public Long versionNo;
    }

    public static class CreateReport {
        @NotBlank
        public String targetType;
        @NotNull
        public Long targetId;
        @NotBlank
        @Size(max = 1000)
        public String reason;
    }

    public static class ModerationAction {
        @NotBlank
        @Size(max = 64)
        public String reasonCode;
        @Size(max = 500)
        public String internalNote;
        public Long reportId;
    }

    public static class ReportDecision {
        @NotBlank
        public String decision;
        @NotBlank
        @Size(max = 64)
        public String decisionCode;
        @Size(max = 500)
        public String internalNote;
    }

    public static class MembershipDecision {
        @NotBlank
        public String decision;
        @Size(max = 255)
        public String reason;
    }

    public static class ChangeMemberRole {
        @NotBlank
        public String role;
    }

    public static class GroupQuery {
        @Size(max = 40)
        public String namePrefix;
        /** 字典 community_group_category 的值；OTHER 专指未分类（category_code 为空）的存量小组。 */
        public String category;
        /** true 时按成员维度查询当前用户的小组。 */
        public Boolean mine;
        /**
         * 仅配合 mine=true 使用：ACTIVE（默认，仅有效成员）、PENDING（仅等待审核）、
         * ANY（ACTIVE+PENDING，「我的小组」语义）；其余成员状态不对用户侧开放枚举。
         */
        public String memberStatus;
        public Long cursorId;
        @Min(1)
        @Max(50)
        public int limit = 20;

        public String getNamePrefix() {
            return namePrefix;
        }

        public void setNamePrefix(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public Boolean getMine() {
            return mine;
        }

        public void setMine(Boolean mine) {
            this.mine = mine;
        }

        public String getMemberStatus() {
            return memberStatus;
        }

        public void setMemberStatus(String memberStatus) {
            this.memberStatus = memberStatus;
        }

        public Long getCursorId() {
            return cursorId;
        }

        public void setCursorId(Long cursorId) {
            this.cursorId = cursorId;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }
    }

    public static class PostQuery {
        public Long groupId;
        public Long authorId;
        @Size(max = 80)
        public String titlePrefix;
        /** 字典 community_group_category 的值，按所属小组类别过滤；OTHER 专指未分类小组。 */
        public String category;
        /** 唯一合法值 PLATFORM_VISIBLE：阅读流只取广场可见小组的帖子。 */
        public String visibilityScope;
        /**
         * 内容状态维度：默认 PUBLISHED；HIDDEN/ALL 仅组内治理使用，必须配合 groupId
         * 且调用者为该组 OWNER/ADMIN（服务层校验，普通成员不可用）。
         */
        public String status;
        public Long cursorId;
        @Min(1)
        @Max(50)
        public int limit = 20;

        public Long getGroupId() {
            return groupId;
        }

        public void setGroupId(Long groupId) {
            this.groupId = groupId;
        }

        public Long getAuthorId() {
            return authorId;
        }

        public void setAuthorId(Long authorId) {
            this.authorId = authorId;
        }

        public String getTitlePrefix() {
            return titlePrefix;
        }

        public void setTitlePrefix(String titlePrefix) {
            this.titlePrefix = titlePrefix;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getVisibilityScope() {
            return visibilityScope;
        }

        public void setVisibilityScope(String visibilityScope) {
            this.visibilityScope = visibilityScope;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Long getCursorId() {
            return cursorId;
        }

        public void setCursorId(Long cursorId) {
            this.cursorId = cursorId;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }
    }

    public static class AdminReportQuery {
        public String status;
        public String targetType;
        public Long cursorId;
        @Min(1)
        @Max(100)
        public int limit = 20;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getTargetType() {
            return targetType;
        }

        public void setTargetType(String targetType) {
            this.targetType = targetType;
        }

        public Long getCursorId() {
            return cursorId;
        }

        public void setCursorId(Long cursorId) {
            this.cursorId = cursorId;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }
    }

    public static class AdminEventQuery {
        public String subjectType;
        public Long subjectId;
        public Long reportId;
        public Long cursorId;
        @Min(1)
        @Max(100)
        public int limit = 20;

        public String getSubjectType() {
            return subjectType;
        }

        public void setSubjectType(String subjectType) {
            this.subjectType = subjectType;
        }

        public Long getSubjectId() {
            return subjectId;
        }

        public void setSubjectId(Long subjectId) {
            this.subjectId = subjectId;
        }

        public Long getReportId() {
            return reportId;
        }

        public void setReportId(Long reportId) {
            this.reportId = reportId;
        }

        public Long getCursorId() {
            return cursorId;
        }

        public void setCursorId(Long cursorId) {
            this.cursorId = cursorId;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }
    }
}
