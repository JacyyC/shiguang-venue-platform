package com.example.venue.community.mapper;

import com.example.venue.community.dto.CommunityQueryRows;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommunityRetentionMapper {
    @Select("""
        SELECT g.id, g.status
        FROM community_group g
        WHERE g.status IN ('DELETED', 'HIDDEN')
          AND g.purged_at IS NULL
          AND g.retain_until <= #{now}
          AND NOT EXISTS (
            SELECT 1 FROM community_report r
            WHERE r.target_type = 'GROUP' AND r.target_id = g.id
              AND (r.status IN ('OPEN', 'IN_REVIEW') OR r.reason_retain_until > #{now})
          )
        ORDER BY g.retain_until ASC, g.id ASC
        LIMIT #{limit}
        """)
    List<CommunityQueryRows.RetentionCandidateRow> selectDueGroupIds(
        @Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("""
        SELECT p.id, p.status
        FROM community_post p
        JOIN community_group g ON g.id = p.group_id
        WHERE p.status <> 'PURGED'
          AND p.purged_at IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM community_report r
            WHERE r.target_type = 'POST' AND r.target_id = p.id
              AND (r.status IN ('OPEN', 'IN_REVIEW') OR r.reason_retain_until > #{now})
          )
          AND (
            (p.status IN ('DELETED', 'HIDDEN') AND p.retain_until <= #{now})
            OR (
              p.status = 'PUBLISHED'
              AND (p.retain_until IS NULL OR p.retain_until <= #{now})
              AND g.status IN ('DELETED', 'HIDDEN', 'PURGED')
              AND g.retain_until <= #{now}
              AND NOT EXISTS (
                SELECT 1 FROM community_report gr
                WHERE gr.target_type = 'GROUP' AND gr.target_id = g.id
                  AND (gr.status IN ('OPEN', 'IN_REVIEW') OR gr.reason_retain_until > #{now})
              )
            )
          )
        ORDER BY p.id ASC
        LIMIT #{limit}
        """)
    List<CommunityQueryRows.RetentionCandidateRow> selectDuePostIds(
        @Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("""
        SELECT c.id, c.status
        FROM community_comment c
        JOIN community_post p ON p.id = c.post_id AND p.group_id = c.group_id
        JOIN community_group g ON g.id = c.group_id
        WHERE c.status <> 'PURGED'
          AND c.purged_at IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM community_report r
            WHERE r.target_type = 'COMMENT' AND r.target_id = c.id
              AND (r.status IN ('OPEN', 'IN_REVIEW') OR r.reason_retain_until > #{now})
          )
          AND (
            (c.status IN ('DELETED', 'HIDDEN') AND c.retain_until <= #{now})
            OR (
              c.status = 'PUBLISHED'
              AND (c.retain_until IS NULL OR c.retain_until <= #{now})
              AND (p.status IN ('DELETED', 'HIDDEN', 'PURGED')
                OR g.status IN ('DELETED', 'HIDDEN', 'PURGED'))
              AND NOT (
                p.status IN ('DELETED', 'HIDDEN', 'PURGED')
                AND (p.retain_until > #{now} OR EXISTS (
                  SELECT 1 FROM community_report pr
                  WHERE pr.target_type = 'POST' AND pr.target_id = p.id
                    AND (pr.status IN ('OPEN', 'IN_REVIEW') OR pr.reason_retain_until > #{now})
                ))
              )
              AND NOT (
                g.status IN ('DELETED', 'HIDDEN', 'PURGED')
                AND (g.retain_until > #{now} OR EXISTS (
                  SELECT 1 FROM community_report gr
                  WHERE gr.target_type = 'GROUP' AND gr.target_id = g.id
                    AND (gr.status IN ('OPEN', 'IN_REVIEW') OR gr.reason_retain_until > #{now})
                ))
              )
            )
          )
        ORDER BY c.id ASC
        LIMIT #{limit}
        """)
    List<CommunityQueryRows.RetentionCandidateRow> selectDueCommentIds(
        @Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("""
        SELECT id, status
        FROM community_report
        WHERE status IN ('RESOLVED', 'DISMISSED')
          AND reason_purged_at IS NULL
          AND reason_retain_until <= #{now}
        ORDER BY reason_retain_until ASC, id ASC
        LIMIT #{limit}
        """)
    List<CommunityQueryRows.RetentionCandidateRow> selectDueReportReasonIds(
        @Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update({
        "<script>",
        "UPDATE community_group",
        "SET name = NULL, description = NULL, owner_user_id = NULL, status = 'PURGED',",
        "purged_at = #{now}, version_no = version_no + 1, update_time = #{now}",
        "WHERE id = #{id} AND status = #{expectedStatus}",
        "AND status IN ('DELETED', 'HIDDEN') AND purged_at IS NULL AND retain_until &lt;= #{now}",
        "AND NOT EXISTS (SELECT 1 FROM community_report r",
        "WHERE r.target_type = 'GROUP' AND r.target_id = community_group.id",
        "AND (r.status IN ('OPEN', 'IN_REVIEW') OR r.reason_retain_until &gt; #{now}))",
        "</script>"
    })
    int purgeGroups(@Param("id") Long id,
                    @Param("expectedStatus") String expectedStatus,
                    @Param("now") LocalDateTime now);

    @Update({
        "<script>",
        "UPDATE community_post",
        "SET title = NULL, content_text = NULL, author_user_id = NULL, status = 'PURGED',",
        "deletion_reason = COALESCE(deletion_reason, 'MODERATION_RETENTION_EXPIRED'),",
        "deleted_at = COALESCE(deleted_at, #{now}), retain_until = COALESCE(retain_until, #{now}),",
        "purged_at = #{now}, version_no = version_no + 1, update_time = #{now}",
        "WHERE id = #{id} AND status = #{expectedStatus}",
        "AND status &lt;&gt; 'PURGED' AND purged_at IS NULL",
        "AND NOT EXISTS (SELECT 1 FROM community_report r",
        "WHERE r.target_type = 'POST' AND r.target_id = community_post.id",
        "AND (r.status IN ('OPEN', 'IN_REVIEW') OR r.reason_retain_until &gt; #{now}))",
        "AND ((status IN ('DELETED', 'HIDDEN') AND retain_until &lt;= #{now})",
        "OR (status = 'PUBLISHED' AND (retain_until IS NULL OR retain_until &lt;= #{now})",
        "AND EXISTS (SELECT 1 FROM community_group g WHERE g.id = community_post.group_id",
        "AND g.status IN ('DELETED', 'HIDDEN', 'PURGED') AND g.retain_until &lt;= #{now}",
        "AND NOT EXISTS (SELECT 1 FROM community_report gr",
        "WHERE gr.target_type = 'GROUP' AND gr.target_id = g.id",
        "AND (gr.status IN ('OPEN', 'IN_REVIEW') OR gr.reason_retain_until &gt; #{now})))))",
        "</script>"
    })
    int purgePosts(@Param("id") Long id,
                   @Param("expectedStatus") String expectedStatus,
                   @Param("now") LocalDateTime now);

    @Update({
        "<script>",
        "UPDATE community_comment",
        "SET content_text = NULL, author_user_id = NULL, status = 'PURGED',",
        "deletion_reason = COALESCE(deletion_reason, 'MODERATION_RETENTION_EXPIRED'),",
        "deleted_at = COALESCE(deleted_at, #{now}), retain_until = COALESCE(retain_until, #{now}),",
        "purged_at = #{now}, version_no = version_no + 1, update_time = #{now}",
        "WHERE id = #{id} AND status = #{expectedStatus}",
        "AND status &lt;&gt; 'PURGED' AND purged_at IS NULL",
        "AND NOT EXISTS (SELECT 1 FROM community_report r",
        "WHERE r.target_type = 'COMMENT' AND r.target_id = community_comment.id",
        "AND (r.status IN ('OPEN', 'IN_REVIEW') OR r.reason_retain_until &gt; #{now}))",
        "AND ((status IN ('DELETED', 'HIDDEN') AND retain_until &lt;= #{now})",
        "OR (status = 'PUBLISHED' AND (retain_until IS NULL OR retain_until &lt;= #{now})",
        "AND EXISTS (SELECT 1 FROM community_post p JOIN community_group g ON g.id = p.group_id",
        "WHERE p.id = community_comment.post_id AND p.group_id = community_comment.group_id",
        "AND (p.status IN ('DELETED', 'HIDDEN', 'PURGED')",
        "OR g.status IN ('DELETED', 'HIDDEN', 'PURGED'))",
        "AND NOT (p.status IN ('DELETED', 'HIDDEN', 'PURGED')",
        "AND (p.retain_until &gt; #{now} OR EXISTS (SELECT 1 FROM community_report pr",
        "WHERE pr.target_type = 'POST' AND pr.target_id = p.id",
        "AND (pr.status IN ('OPEN', 'IN_REVIEW') OR pr.reason_retain_until &gt; #{now}))))",
        "AND NOT (g.status IN ('DELETED', 'HIDDEN', 'PURGED')",
        "AND (g.retain_until &gt; #{now} OR EXISTS (SELECT 1 FROM community_report gr",
        "WHERE gr.target_type = 'GROUP' AND gr.target_id = g.id",
        "AND (gr.status IN ('OPEN', 'IN_REVIEW') OR gr.reason_retain_until &gt; #{now})))))))",
        "</script>"
    })
    int purgeComments(@Param("id") Long id,
                      @Param("expectedStatus") String expectedStatus,
                      @Param("now") LocalDateTime now);

    @Update({
        "<script>",
        "UPDATE community_report",
        "SET reason_text = NULL, internal_note = NULL, reason_purged_at = #{now}, update_time = #{now}",
        "WHERE id = #{id} AND status = #{expectedStatus}",
        "AND status IN ('RESOLVED', 'DISMISSED') AND reason_purged_at IS NULL",
        "AND reason_retain_until &lt;= #{now}",
        "</script>"
    })
    int purgeReportReasons(@Param("id") Long id,
                           @Param("expectedStatus") String expectedStatus,
                           @Param("now") LocalDateTime now);

    @Select("""
        SELECT COUNT(1)
        FROM (
            SELECT owned_group.id
            FROM community_group owned_group
            WHERE owned_group.owner_user_id = #{userId}
              AND (
                  owned_group.retain_until > #{now}
                  OR EXISTS (
                      SELECT 1
                      FROM community_report open_group_report
                      WHERE open_group_report.target_type = 'GROUP'
                        AND open_group_report.target_id = owned_group.id
                        AND open_group_report.status IN ('OPEN', 'IN_REVIEW')
                  )
              )
            UNION ALL
            SELECT authored_post.id
            FROM community_post authored_post
            WHERE authored_post.author_user_id = #{userId}
              AND (
                  authored_post.retain_until > #{now}
                  OR EXISTS (
                      SELECT 1
                      FROM community_report open_post_report
                      WHERE open_post_report.target_type = 'POST'
                        AND open_post_report.target_id = authored_post.id
                        AND open_post_report.status IN ('OPEN', 'IN_REVIEW')
                  )
              )
            UNION ALL
            SELECT authored_comment.id
            FROM community_comment authored_comment
            WHERE authored_comment.author_user_id = #{userId}
              AND (
                  authored_comment.retain_until > #{now}
                  OR EXISTS (
                      SELECT 1
                      FROM community_report open_comment_report
                      WHERE open_comment_report.target_type = 'COMMENT'
                        AND open_comment_report.target_id = authored_comment.id
                        AND open_comment_report.status IN ('OPEN', 'IN_REVIEW')
                  )
              )
            UNION ALL
            SELECT protected_report.id
            FROM community_report protected_report
            WHERE protected_report.reporter_user_id = #{userId}
              AND (
                  protected_report.status IN ('OPEN', 'IN_REVIEW')
                  OR protected_report.minimal_retain_until > #{now}
              )
            UNION ALL
            SELECT protected_event.id
            FROM community_governance_event protected_event
            WHERE protected_event.actor_user_id = #{userId}
              AND protected_event.retain_until > #{now}
        ) retention_blocker
        """)
    int countDeidentificationBlockers(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE community_group
        SET name = NULL,
            description = NULL,
            owner_user_id = NULL,
            status = 'PURGED',
            deleted_at = COALESCE(deleted_at, #{deletedTime}),
            retain_until = COALESCE(retain_until, #{ordinaryRetainUntil}),
            purged_at = #{now},
            version_no = version_no + 1,
            update_time = #{now}
        WHERE owner_user_id = #{userId}
          AND status <> 'PURGED'
          AND (retain_until IS NULL OR retain_until <= #{now})
        """)
    int purgeGroupsOwnedByUser(@Param("userId") Long userId,
                               @Param("deletedTime") LocalDateTime deletedTime,
                               @Param("ordinaryRetainUntil") LocalDateTime ordinaryRetainUntil,
                               @Param("now") LocalDateTime now);

    @Update("""
        UPDATE community_post
        SET title = NULL,
            content_text = NULL,
            author_user_id = NULL,
            status = 'PURGED',
            deletion_reason = COALESCE(deletion_reason, 'ACCOUNT_DEIDENTIFIED'),
            deleted_at = COALESCE(deleted_at, #{deletedTime}),
            retain_until = COALESCE(retain_until, #{ordinaryRetainUntil}),
            purged_at = #{now},
            version_no = version_no + 1,
            update_time = #{now}
        WHERE author_user_id = #{userId}
          AND status <> 'PURGED'
          AND (retain_until IS NULL OR retain_until <= #{now})
        """)
    int purgePostsAuthoredByUser(@Param("userId") Long userId,
                                 @Param("deletedTime") LocalDateTime deletedTime,
                                 @Param("ordinaryRetainUntil") LocalDateTime ordinaryRetainUntil,
                                 @Param("now") LocalDateTime now);

    @Update("""
        UPDATE community_comment
        SET content_text = NULL,
            author_user_id = NULL,
            status = 'PURGED',
            deletion_reason = COALESCE(deletion_reason, 'ACCOUNT_DEIDENTIFIED'),
            deleted_at = COALESCE(deleted_at, #{deletedTime}),
            retain_until = COALESCE(retain_until, #{ordinaryRetainUntil}),
            purged_at = #{now},
            version_no = version_no + 1,
            update_time = #{now}
        WHERE author_user_id = #{userId}
          AND status <> 'PURGED'
          AND (retain_until IS NULL OR retain_until <= #{now})
        """)
    int purgeCommentsAuthoredByUser(@Param("userId") Long userId,
                                    @Param("deletedTime") LocalDateTime deletedTime,
                                    @Param("ordinaryRetainUntil") LocalDateTime ordinaryRetainUntil,
                                    @Param("now") LocalDateTime now);

    @Update("""
        UPDATE community_group_member
        SET reviewed_at = CASE
                WHEN status = 'PENDING' THEN COALESCE(reviewed_at, #{now})
                ELSE reviewed_at
            END,
            left_at = CASE
                WHEN status = 'ACTIVE' THEN COALESCE(left_at, #{now})
                ELSE left_at
            END,
            status = CASE
                WHEN status = 'PENDING' THEN 'REJECTED'
                WHEN status = 'ACTIVE' THEN 'REMOVED'
                ELSE status
            END,
            user_id = NULL,
            update_time = #{now}
        WHERE user_id = #{userId}
        """)
    int deidentifyMemberships(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE community_group_member
        SET reviewed_by = NULL, update_time = #{now}
        WHERE reviewed_by = #{userId}
        """)
    int clearMembershipReviewer(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE community_post
        SET hidden_by = CASE WHEN hidden_by = #{userId} THEN NULL ELSE hidden_by END,
            deleted_by = CASE WHEN deleted_by = #{userId} THEN NULL ELSE deleted_by END,
            update_time = #{now}
        WHERE hidden_by = #{userId} OR deleted_by = #{userId}
        """)
    int clearPostActorReferences(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE community_comment
        SET hidden_by = CASE WHEN hidden_by = #{userId} THEN NULL ELSE hidden_by END,
            deleted_by = CASE WHEN deleted_by = #{userId} THEN NULL ELSE deleted_by END,
            update_time = #{now}
        WHERE hidden_by = #{userId} OR deleted_by = #{userId}
        """)
    int clearCommentActorReferences(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE community_user_block
        SET removed_at = COALESCE(removed_at, #{now}),
            status = 'REMOVED',
            blocker_user_id = NULL,
            blocked_user_id = NULL,
            update_time = #{now}
        WHERE blocker_user_id = #{userId} OR blocked_user_id = #{userId}
        """)
    int deidentifyBlocks(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE community_report
        SET reporter_user_id = CASE
                WHEN reporter_user_id = #{userId} AND status IN ('RESOLVED', 'DISMISSED') THEN NULL
                ELSE reporter_user_id
            END,
            assigned_to = CASE WHEN assigned_to = #{userId} THEN NULL ELSE assigned_to END,
            update_time = #{now}
        WHERE reporter_user_id = #{userId} OR assigned_to = #{userId}
        """)
    int clearReportUserReferences(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE community_governance_event
        SET actor_user_id = NULL
        WHERE actor_user_id = #{userId}
          AND retain_until <= #{now}
        """)
    int clearMatureGovernanceActors(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Select("""
        SELECT COUNT(1)
        FROM (
            SELECT owner_user_id AS user_reference FROM community_group WHERE owner_user_id = #{userId}
            UNION ALL
            SELECT user_id FROM community_group_member WHERE user_id = #{userId}
            UNION ALL
            SELECT reviewed_by FROM community_group_member WHERE reviewed_by = #{userId}
            UNION ALL
            SELECT author_user_id FROM community_post WHERE author_user_id = #{userId}
            UNION ALL
            SELECT hidden_by FROM community_post WHERE hidden_by = #{userId}
            UNION ALL
            SELECT deleted_by FROM community_post WHERE deleted_by = #{userId}
            UNION ALL
            SELECT author_user_id FROM community_comment WHERE author_user_id = #{userId}
            UNION ALL
            SELECT hidden_by FROM community_comment WHERE hidden_by = #{userId}
            UNION ALL
            SELECT deleted_by FROM community_comment WHERE deleted_by = #{userId}
            UNION ALL
            SELECT blocker_user_id FROM community_user_block WHERE blocker_user_id = #{userId}
            UNION ALL
            SELECT blocked_user_id FROM community_user_block WHERE blocked_user_id = #{userId}
            UNION ALL
            SELECT reporter_user_id FROM community_report WHERE reporter_user_id = #{userId}
            UNION ALL
            SELECT assigned_to FROM community_report WHERE assigned_to = #{userId}
            UNION ALL
            SELECT actor_user_id FROM community_governance_event WHERE actor_user_id = #{userId}
        ) remaining_reference
        """)
    int countRemainingUserReferences(@Param("userId") Long userId);
}
