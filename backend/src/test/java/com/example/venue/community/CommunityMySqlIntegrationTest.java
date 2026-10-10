package com.example.venue.community;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.ResultCode;
import com.example.venue.community.config.CommunityRetentionProperties;
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
import com.example.venue.community.mapper.CommunityGovernanceEventMapper;
import com.example.venue.community.mapper.CommunityGroupMapper;
import com.example.venue.community.mapper.CommunityGroupMemberMapper;
import com.example.venue.community.mapper.CommunityPostMapper;
import com.example.venue.community.mapper.CommunityReportMapper;
import com.example.venue.community.mapper.CommunityRetentionMapper;
import com.example.venue.community.mapper.CommunityUserBlockMapper;
import com.example.venue.community.service.CommunityAccessService;
import com.example.venue.community.service.CommunityContentService;
import com.example.venue.community.service.CommunityGovernanceEventService;
import com.example.venue.community.service.CommunityGovernanceService;
import com.example.venue.community.service.CommunityGroupService;
import com.example.venue.community.service.CommunityRateLimitService;
import com.example.venue.community.service.CommunityRetentionService;
import com.example.venue.community.service.CommunityTextPolicy;
import com.example.venue.system.mapper.SysDeletedUserSnapshotMapper;
import com.example.venue.system.mapper.SysDictDataMapper;
import com.example.venue.user.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(
    classes = CommunityMySqlIntegrationTest.IntegrationApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.task.scheduling.enabled=false"
)
class CommunityMySqlIntegrationTest {
    private static final long VIEWER_ID = 8_100_001L;
    private static final long NON_MEMBER_ID = 8_100_002L;
    private static final LocalDateTime RETENTION_BASE = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("community_governance")
        .withUsername("community")
        .withPassword("community")
        .withEnv("TZ", "Asia/Shanghai")
        .withCommand("--character-set-server=utf8mb4",
                     "--collation-server=utf8mb4_unicode_ci",
                     "--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
            + "?connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        // V2-UX-REMEDIATION/S01：community_group 增加 category_code 列后，治理契约测试随
        // 最新迁移（V34）运行；此前钉在创建时的 V15。
        registry.add("spring.flyway.target", () -> "34");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private CommunityGroupMapper groupMapper;
    @Autowired
    private CommunityPostMapper postMapper;
    @Autowired
    private CommunityCommentMapper commentMapper;
    @Autowired
    private CommunityRetentionMapper retentionMapper;
    @Autowired
    private CommunityGroupMemberMapper memberMapper;
    @Autowired
    private CommunityUserBlockMapper blockMapper;
    @Autowired
    private CommunityAccessService accessService;
    @Autowired
    private CommunityGovernanceEventService eventService;
    @Autowired
    private CommunityRetentionService retentionService;
    @Autowired
    private CommunityRetentionProperties retentionProperties;
    @Autowired
    private CommunityRateLimitService rateLimitService;
    @Autowired
    private SysDeletedUserSnapshotMapper deletedUserSnapshotMapper;
    @Autowired
    private CommunityContentService contentService;
    @Autowired
    private CommunityGroupService groupService;
    @Autowired
    private CommunityGovernanceService governanceService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void removeCommunityFixtures() {
        reset(rateLimitService);
        jdbcTemplate.update("DELETE FROM community_governance_event");
        jdbcTemplate.update("DELETE FROM community_report");
        jdbcTemplate.update("DELETE FROM community_comment");
        jdbcTemplate.update("DELETE FROM community_post");
        jdbcTemplate.update("DELETE FROM community_group_member");
        jdbcTemplate.update("DELETE FROM community_user_block");
        jdbcTemplate.update("DELETE FROM community_group");
    }

    @Test
    void flywayV1ToV34AppliesInOrderAndKeepsCommunitySchemaContract() {
        assertThat(queryStrings("""
            SELECT version
            FROM flyway_schema_history
            WHERE success = 1
            ORDER BY installed_rank
            """))
            .containsExactly(
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14",
                "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27",
                "28", "29", "30", "31", "32", "33", "34"
            );
        assertThat(queryStrings("""
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name LIKE 'community\\_%'
            ORDER BY table_name
            """))
            .containsExactly(
                "community_activity",
                "community_activity_application",
                "community_activity_command",
                "community_activity_notification_task",
                "community_activity_participation",
                "community_activity_state_event",
                "community_comment",
                "community_companion_request",
                "community_governance_event",
                "community_group",
                "community_group_member",
                "community_match_decision",
                "community_post",
                "community_report",
                "community_user_block"
            );

        assertThat(queryStrings("""
            SELECT CONCAT(table_name, ':', constraint_name)
            FROM information_schema.table_constraints
            WHERE table_schema = DATABASE()
              AND constraint_type = 'CHECK'
              AND table_name LIKE 'community\\_%'
            ORDER BY table_name, constraint_name
            """))
            .contains(
                "community_group:chk_community_group_visibility",
                "community_group_member:chk_community_group_member_timeline",
                "community_post:chk_community_post_retention",
                "community_comment:chk_community_comment_retention",
                "community_user_block:chk_community_user_block_distinct",
                "community_report:chk_community_report_closed",
                "community_governance_event:chk_community_governance_event_retention"
            );
        assertThat(queryStrings("""
            SELECT CONCAT(table_name, ':', index_name)
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND non_unique = 0
              AND index_name <> 'PRIMARY'
              AND table_name LIKE 'community\\_%'
            GROUP BY table_name, index_name
            ORDER BY table_name, index_name
            """))
            .contains(
                "community_group:uk_community_group_owner_idempotency",
                "community_group_member:uk_community_group_member",
                "community_group_member:uk_community_group_active_owner",
                "community_post:uk_community_post_author_idempotency",
                "community_post:uk_community_post_id_group",
                "community_comment:uk_community_comment_author_idempotency",
                "community_user_block:uk_community_user_block_pair",
                "community_report:uk_community_reporter_idempotency",
                "community_governance_event:uk_community_governance_event_idempotency"
            );
        assertThat(queryStrings("""
            SELECT CONCAT(constraint_name, ':',
                          GROUP_CONCAT(column_name ORDER BY ordinal_position SEPARATOR ','))
            FROM information_schema.key_column_usage
            WHERE table_schema = DATABASE()
              AND table_name LIKE 'community\\_%'
              AND referenced_table_name IS NOT NULL
            GROUP BY constraint_name
            ORDER BY constraint_name
            """))
            .containsExactly(
                "fk_community_activity_group:group_id",
                "fk_community_activity_participation_application:application_id",
                "fk_community_activity_post:reference_post_id",
                "fk_community_comment_post_group:post_id,group_id",
                "fk_community_companion_activity:activity_id",
                "fk_community_companion_group:group_id",
                "fk_community_companion_post:reference_post_id",
                "fk_community_governance_event_report:report_id",
                "fk_community_group_member_group:group_id",
                "fk_community_match_candidate:candidate_request_id",
                "fk_community_match_source:source_request_id",
                "fk_community_post_group:group_id"
            );

        assertConstraintViolation(
            () -> insertGroup(8_200_001L, 8_210_001L, "PUBLIC", "invalid-visibility"), 3819);
        assertConstraintViolation(() -> jdbcTemplate.update("""
            INSERT INTO community_user_block(
                blocker_user_id, blocked_user_id, status, last_idempotency_key, created_at
            ) VALUES (?, ?, 'ACTIVE', 'self-block', NOW())
            """, VIEWER_ID, VIEWER_ID), 3819);
    }

    @Test
    void v34SeedsGroupCategoryDictionaryAndCategoryQueriesPartitionGroups() {
        // V2-UX-REMEDIATION/S01：字典类型与 6 条初值词表由 V34 种子，管理员后续经 sys_dict 维护。
        assertThat(queryStrings("""
            SELECT value FROM sys_dict_data
            WHERE dict_type = 'community_group_category' AND status = 'ACTIVE'
            ORDER BY sort_order, id
            """)).containsExactly("STUDY", "SPORTS", "ART", "BOARD_GAME", "FAMILY", "CAREER");
        assertThat(queryStrings("""
            SELECT label FROM sys_dict_data
            WHERE dict_type = 'community_group_category' AND status = 'ACTIVE'
            ORDER BY sort_order, id
            """)).containsExactly("学习共读", "运动户外", "艺术创作", "桌游推理", "亲子同城", "职业交流");

        long viewerId = 8_260_001L;
        insertCategorizedGroup(8_260_101L, 8_260_011L, "PLATFORM_VISIBLE", "cat-study", "STUDY");
        insertCategorizedGroup(8_260_102L, 8_260_012L, "PLATFORM_VISIBLE", "cat-sports", "SPORTS");
        // 存量小组：category_code 保持 NULL，归「其他」分区（OTHER）。
        insertGroup(8_260_103L, 8_260_013L, "PLATFORM_VISIBLE", "cat-legacy");

        assertThat(groupMapper.searchVisible(viewerId, null, "STUDY", null, 50))
            .extracting(row -> row.id).containsExactly(8_260_101L);
        assertThat(groupMapper.searchVisible(viewerId, null, "OTHER", null, 50))
            .extracting(row -> row.id).containsExactly(8_260_103L);
        assertThat(groupMapper.findDetail(8_260_101L, viewerId).categoryCode).isEqualTo("STUDY");
        assertThat(groupMapper.findDetail(8_260_103L, viewerId).categoryCode).isNull();
    }

    @Test
    void searchMineReturnsOnlyActiveMembershipsOfTheViewer() {
        long memberId = 8_260_002L;
        insertCategorizedGroup(8_260_201L, 8_260_021L, "PLATFORM_VISIBLE", "mine-study", "STUDY");
        insertCategorizedGroup(8_260_202L, 8_260_022L, "PLATFORM_VISIBLE", "mine-sports", "SPORTS");
        insertMember(8_260_201L, memberId, "MEMBER", "ACTIVE");
        // PENDING 成员不允许携带 joined_at（chk_community_group_member_timeline）。
        jdbcTemplate.update("""
            INSERT INTO community_group_member(group_id, user_id, member_role, status)
            VALUES (?, ?, 'MEMBER', 'PENDING')
            """, 8_260_202L, memberId);

        // 默认只有 ACTIVE 成员身份进入「我的小组」；类别筛选照常生效。
        assertThat(groupMapper.searchMine(memberId, null, null, "ACTIVE", null, 50))
            .extracting(row -> row.id).containsExactly(8_260_201L);
        assertThat(groupMapper.searchMine(memberId, null, "SPORTS", "ACTIVE", null, 50)).isEmpty();
        assertThat(groupMapper.searchMine(memberId, null, "STUDY", "ACTIVE", null, 50))
            .extracting(row -> row.id).containsExactly(8_260_201L);
    }

    @Test
    void searchMineMemberStatusDimensionCoversPendingAndAnyButNeverRemoved() {
        long memberId = 8_260_003L;
        insertCategorizedGroup(8_260_301L, 8_260_031L, "PLATFORM_VISIBLE", "ms-active", "STUDY");
        insertCategorizedGroup(8_260_302L, 8_260_032L, "PLATFORM_VISIBLE", "ms-pending", "SPORTS");
        insertCategorizedGroup(8_260_303L, 8_260_033L, "PLATFORM_VISIBLE", "ms-removed", "ART");
        insertMember(8_260_301L, memberId, "MEMBER", "ACTIVE");
        jdbcTemplate.update("""
            INSERT INTO community_group_member(group_id, user_id, member_role, status)
            VALUES (?, ?, 'MEMBER', 'PENDING')
            """, 8_260_302L, memberId);
        jdbcTemplate.update("""
            INSERT INTO community_group_member(group_id, user_id, member_role, status, joined_at, left_at)
            VALUES (?, ?, 'MEMBER', 'REMOVED', NOW(), NOW())
            """, 8_260_303L, memberId);

        // PENDING 只取等待审核；ANY 为「我的小组」语义（ACTIVE+PENDING）；REMOVED 永不进入。
        assertThat(groupMapper.searchMine(memberId, null, null, "PENDING", null, 50))
            .extracting(row -> row.id).containsExactly(8_260_302L);
        assertThat(groupMapper.searchMine(memberId, null, null, "ANY", null, 50))
            .extracting(row -> row.id).containsExactlyInAnyOrder(8_260_301L, 8_260_302L);
        assertThat(groupMapper.searchMine(memberId, null, null, "ACTIVE", null, 50))
            .extracting(row -> row.id).containsExactly(8_260_301L);
    }

    @Test
    void createGroupServicePersistsCategoryAgainstRealSchema() {
        long ownerId = 8_261_001L;
        CurrentUser owner = currentUser(ownerId, "category-owner");
        CommunityRequests.CreateGroup request = new CommunityRequests.CreateGroup();
        request.name = "分类建组";
        request.description = "端到端验证类别持久化";
        request.visibility = "PLATFORM_VISIBLE";
        request.joinPolicy = "DIRECT";
        request.category = "ART";

        CommunityGroup created = executeInNewTransaction(owner,
            () -> groupService.create(request, "group-category-key"));

        assertThat(created.categoryCode).isEqualTo("ART");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT category_code FROM community_group WHERE id = ?",
            String.class, created.id)).isEqualTo("ART");

        CommunityGroup replay = executeInNewTransaction(owner,
            () -> groupService.create(request, "group-category-key"));
        assertThat(replay.id).isEqualTo(created.id);
    }

    @Test
    void hiddenGroupPostAndCommentRequireDeletionAndRetentionTimeline() {
        LocalDateTime hiddenAt = LocalDateTime.of(2026, 2, 1, 12, 0);
        assertConstraintViolation(() -> jdbcTemplate.update("""
            INSERT INTO community_group(
                id, group_no, name, description, owner_user_id, visibility, join_policy,
                status, idempotency_key, request_hash, hidden_at
            ) VALUES (?, 'CG-hidden-without-retention', '无保留小组', '说明', ?,
                      'PLATFORM_VISIBLE', 'DIRECT', 'HIDDEN', 'hidden-group-key',
                      'hidden-group-hash', ?)
            """, 8_205_001L, 8_205_101L, hiddenAt), 3819);

        long groupId = 8_205_002L;
        long postId = 8_205_003L;
        insertGroup(groupId, 8_205_102L, "PLATFORM_VISIBLE", "hidden-parent-group");
        assertConstraintViolation(() -> jdbcTemplate.update("""
            INSERT INTO community_post(
                id, post_no, group_id, author_user_id, title, content_text, status,
                idempotency_key, request_hash, published_at, hidden_by, hidden_scope,
                hidden_reason_code, hidden_at
            ) VALUES (?, 'CP-hidden-without-retention', ?, ?, '无保留帖子', '正文', 'HIDDEN',
                      'hidden-post-key', 'hidden-post-hash', ?, ?, 'PLATFORM', 'POLICY', ?)
            """, 8_205_004L, groupId, 8_205_103L, hiddenAt, 8_205_104L, hiddenAt), 3819);

        insertPost(postId, groupId, 8_205_105L, "评论父帖", "hidden-parent-post");
        assertConstraintViolation(() -> jdbcTemplate.update("""
            INSERT INTO community_comment(
                id, comment_no, post_id, group_id, author_user_id, content_text, status,
                idempotency_key, request_hash, published_at, hidden_by, hidden_scope,
                hidden_reason_code, hidden_at
            ) VALUES (?, 'CC-hidden-without-retention', ?, ?, ?, '无保留评论', 'HIDDEN',
                      'hidden-comment-key', 'hidden-comment-hash', ?, ?, 'GROUP', 'POLICY', ?)
            """, 8_205_005L, postId, groupId, 8_205_106L, hiddenAt, 8_205_107L, hiddenAt), 3819);
    }

    @Test
    void governanceEventsKeepDifferentActorsUsingTheSameClientIdempotencyKey() {
        CommunityGovernanceEventService.EventCommand first = creationEvent(
            8_206_001L, 8_206_101L, "CREATE_POST:shared-client-key");
        CommunityGovernanceEventService.EventCommand second = creationEvent(
            8_206_002L, 8_206_102L, "CREATE_POST:shared-client-key");

        eventService.record(first);
        eventService.record(second);
        eventService.record(first);

        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM community_governance_event
            WHERE source_type = 'USER'
              AND event_type = 'POST_CREATED'
              AND actor_user_id IN (?, ?)
            """, Integer.class, first.actorUserId, second.actorUserId)).isEqualTo(2);
        assertThat(queryStrings("""
            SELECT CONCAT(actor_user_id, ':', subject_id)
            FROM community_governance_event
            WHERE source_type = 'USER'
              AND event_type = 'POST_CREATED'
            ORDER BY actor_user_id
            """))
            .containsExactly("8206001:8206101", "8206002:8206102");
    }

    @Test
    void memberRoleChangeReplayIsIdempotentAndAlternatingTransitionsKeepEveryEvent() throws Exception {
        long groupId = 8_206_301L;
        long ownerId = 8_206_302L;
        long memberUserId = 8_206_303L;
        insertGroupWithOwner(groupId, ownerId, "PLATFORM_VISIBLE", "role-change-idempotency");
        insertMember(groupId, memberUserId, "MEMBER", "ACTIVE");
        Long memberId = jdbcTemplate.queryForObject("""
            SELECT id FROM community_group_member
            WHERE group_id = ? AND user_id = ?
            """, Long.class, groupId, memberUserId);
        CommunityRequests.ChangeMemberRole promote = changeMemberRole("ADMIN");
        CommunityRequests.ChangeMemberRole demote = changeMemberRole("MEMBER");
        CurrentUser owner = currentUser(ownerId, "group-owner");

        List<CommunityGroupMember> concurrentReplay = executeConcurrentlyInNewTransactions(
            owner,
            () -> groupService.changeRole(groupId, memberId, promote, "promote-first"),
            () -> groupService.changeRole(groupId, memberId, promote, "promote-first")
        );
        assertThat(concurrentReplay).extracting(member -> member.memberRole).containsOnly("ADMIN");

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        try {
            assertThat(groupService.changeRole(groupId, memberId, demote, "demote-first").memberRole)
                .isEqualTo("MEMBER");
            assertThatThrownBy(
                () -> groupService.changeRole(groupId, memberId, promote, "promote-first"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色变更已处理");
            assertThat(jdbcTemplate.queryForObject(
                "SELECT member_role FROM community_group_member WHERE id = ?",
                String.class, memberId)).isEqualTo("MEMBER");
            assertThat(groupService.changeRole(groupId, memberId, promote, "promote-second").memberRole)
                .isEqualTo("ADMIN");
            assertThat(groupService.changeRole(groupId, memberId, promote, "promote-second").memberRole)
                .isEqualTo("ADMIN");
            assertThatThrownBy(
                () -> groupService.changeRole(groupId, memberId, demote, "promote-second"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同一 Idempotency-Key 对应了不同请求");
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(queryStrings("""
            SELECT CONCAT(from_status, '>', to_status)
            FROM community_governance_event
            WHERE event_type = 'MEMBER_ROLE_CHANGED'
              AND subject_type = 'MEMBER'
              AND subject_id = %d
              AND actor_user_id = %d
            ORDER BY id
            """.formatted(memberId, ownerId)))
            .containsExactly("MEMBER>ADMIN", "ADMIN>MEMBER", "MEMBER>ADMIN");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(DISTINCT idempotency_key)
            FROM community_governance_event
            WHERE event_type = 'MEMBER_ROLE_CHANGED'
              AND subject_id = ?
              AND actor_user_id = ?
            """, Integer.class, memberId, ownerId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT member_role = 'ADMIN'
              AND last_idempotency_key LIKE 'CHANGE_MEMBER_ROLE:%'
              AND CHAR_LENGTH(last_request_hash) = 64
            FROM community_group_member
            WHERE id = ?
            """, Boolean.class, memberId)).isTrue();
    }

    @Test
    void expiredParentsPurgeOrdinaryDescendantsWithoutShorteningReportedContentRetention() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime expiredAt = now.minusMinutes(5);
        long groupId = 8_207_001L;
        long ordinaryPostId = 8_207_101L;
        long ordinaryCommentId = 8_207_201L;
        long reportedPostId = 8_207_102L;
        insertHiddenGroup(groupId, 8_207_301L, expiredAt, "expired-parent");
        insertPost(ordinaryPostId, groupId, 8_207_401L, "普通子帖子", "ordinary-child-post");
        insertComment(ordinaryCommentId, ordinaryPostId, groupId, 8_207_402L,
            "普通子评论", "ordinary-child-comment");
        insertPost(reportedPostId, groupId, 8_207_403L, "受举报子帖子", "reported-child-post");
        LocalDateTime closedAt = now.minusDays(1);
        LocalDateTime evidenceRetainUntil = closedAt.plusDays(180);
        insertClosedReport(8_207_501L, reportedPostId, closedAt, evidenceRetainUntil,
            now.plusDays(365), "reported-child-retention");

        retentionService.runOnce();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM community_group WHERE id = ?", String.class, groupId)).isEqualTo("PURGED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM community_post WHERE id = ?", String.class, ordinaryPostId)).isEqualTo("PURGED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT title IS NULL AND content_text IS NULL FROM community_post WHERE id = ?",
            Boolean.class, ordinaryPostId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM community_comment WHERE id = ?", String.class, ordinaryCommentId))
            .isEqualTo("PURGED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT content_text IS NULL FROM community_comment WHERE id = ?",
            Boolean.class, ordinaryCommentId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM community_post WHERE id = ?", String.class, reportedPostId))
            .isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT content_text FROM community_post WHERE id = ?", String.class, reportedPostId))
            .isEqualTo("帖子正文");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT reason_retain_until FROM community_report WHERE id = ?",
            LocalDateTime.class, 8_207_501L)).isEqualTo(evidenceRetainUntil);
        assertThat(jdbcTemplate.queryForList("""
            SELECT event_type, subject_type, subject_id, source_type, metadata_json
            FROM community_governance_event
            ORDER BY event_type
            """))
            .extracting(event -> event.get("event_type") + ":" + event.get("subject_type") + ":"
                + event.get("subject_id") + ":" + event.get("source_type") + ":"
                + event.get("metadata_json"))
            .containsExactly(
                "COMMENT_PURGED:COMMENT:" + ordinaryCommentId + ":SCHEDULED_JOB:{}",
                "GROUP_PURGED:GROUP:" + groupId + ":SCHEDULED_JOB:{}",
                "POST_PURGED:POST:" + ordinaryPostId + ":SCHEDULED_JOB:{}"
            );
    }

    @Test
    void mapperStateReactivationAndRestorePersistNullTimelineFields() {
        LocalDateTime transitionAt = LocalDateTime.now().withNano(0).minusDays(1);
        long blockerId = 8_208_001L;
        long blockedId = 8_208_002L;
        insertBlock(blockerId, blockedId, "restore-block");
        jdbcTemplate.update("""
            UPDATE community_user_block
            SET status = 'REMOVED', removed_at = ?, last_idempotency_key = 'remove-block'
            WHERE blocker_user_id = ? AND blocked_user_id = ?
            """, transitionAt, blockerId, blockedId);
        CommunityUserBlock block = blockMapper.findPair(blockerId, blockedId);
        block.status = "ACTIVE";
        block.removedAt = null;
        block.lastIdempotencyKey = "reactivate-block";
        block.createdAt = LocalDateTime.now();
        block.updateTime = block.createdAt;
        assertThat(blockMapper.updateById(block)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT removed_at IS NULL FROM community_user_block
            WHERE blocker_user_id = ? AND blocked_user_id = ?
            """, Boolean.class, blockerId, blockedId)).isTrue();

        long groupId = 8_208_101L;
        long memberId = 8_208_102L;
        insertGroup(groupId, 8_208_103L, "PLATFORM_VISIBLE", "member-rejoin");
        insertMember(groupId, memberId, "MEMBER", "ACTIVE");
        jdbcTemplate.update("""
            UPDATE community_group_member
            SET status = 'LEFT', left_at = ?, last_idempotency_key = 'leave-member',
                last_request_hash = 'leave-member-hash'
            WHERE group_id = ? AND user_id = ?
            """, transitionAt, groupId, memberId);
        CommunityGroupMember member = memberMapper.findByGroupAndUser(groupId, memberId);
        member.status = "ACTIVE";
        member.leftAt = null;
        member.joinedAt = LocalDateTime.now();
        member.lastIdempotencyKey = "rejoin-member";
        member.lastRequestHash = "rejoin-member-hash";
        member.updateTime = member.joinedAt;
        assertThat(memberMapper.updateById(member)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT left_at IS NULL FROM community_group_member
            WHERE group_id = ? AND user_id = ?
            """, Boolean.class, groupId, memberId)).isTrue();

        long restoredGroupId = 8_208_201L;
        long restoredPostId = 8_208_202L;
        long restoredCommentId = 8_208_203L;
        insertHiddenGroup(restoredGroupId, 8_208_204L, transitionAt.plusDays(180), "restore-group");
        CommunityGroup group = groupMapper.selectById(restoredGroupId);
        group.status = "ACTIVE";
        group.hiddenAt = null;
        group.deletedAt = null;
        group.retainUntil = null;
        group.updateTime = LocalDateTime.now();
        assertThat(groupMapper.updateById(group)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT hidden_at IS NULL AND deleted_at IS NULL AND retain_until IS NULL
            FROM community_group WHERE id = ?
            """, Boolean.class, restoredGroupId)).isTrue();

        insertHiddenPost(restoredPostId, restoredGroupId, 8_208_205L,
            transitionAt.plusDays(180), "restore-post");
        CommunityPost post = postMapper.selectById(restoredPostId);
        post.status = "PUBLISHED";
        post.hiddenBy = null;
        post.hiddenScope = null;
        post.hiddenReasonCode = null;
        post.hiddenAt = null;
        post.deletedAt = null;
        post.retainUntil = null;
        post.updateTime = LocalDateTime.now();
        assertThat(postMapper.updateById(post)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT hidden_by IS NULL AND hidden_scope IS NULL AND hidden_reason_code IS NULL
              AND hidden_at IS NULL AND deleted_at IS NULL AND retain_until IS NULL
            FROM community_post WHERE id = ?
            """, Boolean.class, restoredPostId)).isTrue();

        insertHiddenComment(restoredCommentId, restoredPostId, restoredGroupId, 8_208_206L,
            transitionAt.plusDays(180), "restore-comment");
        CommunityComment comment = commentMapper.selectById(restoredCommentId);
        comment.status = "PUBLISHED";
        comment.hiddenBy = null;
        comment.hiddenScope = null;
        comment.hiddenReasonCode = null;
        comment.hiddenAt = null;
        comment.deletedAt = null;
        comment.retainUntil = null;
        comment.updateTime = LocalDateTime.now();
        assertThat(commentMapper.updateById(comment)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT hidden_by IS NULL AND hidden_scope IS NULL AND hidden_reason_code IS NULL
              AND hidden_at IS NULL AND deleted_at IS NULL AND retain_until IS NULL
            FROM community_comment WHERE id = ?
            """, Boolean.class, restoredCommentId)).isTrue();
    }

    @Test
    void concurrentIdempotentPostInsertKeepsOneWinnerAndUniqueKeyRejectsDifferentPayload() throws Exception {
        long groupId = 8_220_001L;
        long authorId = 8_220_101L;
        insertGroup(groupId, 8_220_201L, "PLATFORM_VISIBLE", "idempotency-group");

        List<InsertOutcome> samePayload = insertPostsConcurrently(
            new PostInsert("same-request-a", groupId, authorId, "相同标题", "相同正文", "same-key", "same-hash"),
            new PostInsert("same-request-b", groupId, authorId, "相同标题", "相同正文", "same-key", "same-hash")
        );
        assertSingleUniqueWinner(samePayload);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_post
            WHERE author_user_id = ? AND idempotency_key = 'same-key'
            """, Integer.class, authorId)).isEqualTo(1);

        List<InsertOutcome> differentPayload = insertPostsConcurrently(
            new PostInsert("different-request-a", groupId, authorId, "参数甲", "正文甲", "conflict-key", "hash-a"),
            new PostInsert("different-request-b", groupId, authorId, "参数乙", "正文乙", "conflict-key", "hash-b")
        );
        assertSingleUniqueWinner(differentPayload);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_post
            WHERE author_user_id = ? AND idempotency_key = 'conflict-key'
            """, Integer.class, authorId)).isEqualTo(1);
        assertThat(queryStrings("""
            SELECT request_hash FROM community_post
            WHERE author_user_id = %d AND idempotency_key = 'conflict-key'
            """.formatted(authorId))).singleElement().isIn("hash-a", "hash-b");
    }

    @Test
    void concurrentCreatePostServiceReplayReturnsSamePostAndOneCreationEvent() throws Exception {
        long groupId = 8_221_001L;
        long authorId = 8_221_101L;
        insertGroupWithOwner(groupId, authorId, "PLATFORM_VISIBLE", "service-create-race");
        CommunityRequests.CreatePost request = new CommunityRequests.CreatePost();
        request.groupId = groupId;
        request.title = "并发幂等发帖";
        request.content = "相同请求只能创建一条帖子和一条治理事件";

        CurrentUser author = currentUser(authorId, "concurrent-author");
        List<CommunityPost> results = executeConcurrentlyInNewTransactions(
            author,
            () -> contentService.createPost(request, "service-create-key"),
            () -> contentService.createPost(request, "service-create-key")
        );

        assertThat(results).extracting(post -> post.id).doesNotContainNull().containsOnly(results.get(0).id);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_post
            WHERE author_user_id = ? AND idempotency_key = ?
            """, Integer.class, authorId, "service-create-key")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_governance_event
            WHERE event_type = 'POST_CREATED'
              AND subject_type = 'POST'
              AND subject_id = ?
              AND actor_user_id = ?
            """, Integer.class, results.get(0).id, authorId)).isEqualTo(1);
    }

    @Test
    void createPostRechecksDeletedGroupAndDepartedMemberAfterLocks() throws Exception {
        long deletedGroupId = 8_221_201L;
        long deletedGroupOwnerId = 8_221_202L;
        long deletedGroupAuthorId = 8_221_203L;
        insertGroup(deletedGroupId, deletedGroupOwnerId, "PLATFORM_VISIBLE", "deleted-parent-race");
        insertMember(deletedGroupId, deletedGroupAuthorId, "MEMBER", "ACTIVE");
        CommunityRequests.CreatePost deletedGroupRequest = new CommunityRequests.CreatePost();
        deletedGroupRequest.groupId = deletedGroupId;
        deletedGroupRequest.title = "父级删除竞争";
        deletedGroupRequest.content = "锁后不能再写入帖子";
        CountDownLatch deletedGroupRateChecked = new CountDownLatch(1);
        doAnswer(invocation -> {
            deletedGroupRateChecked.countDown();
            return null;
        }).when(rateLimitService).check(deletedGroupAuthorId, CommunityRateLimitService.Action.POST);

        Throwable deletedGroupFailure = executeAfterUncommittedMutation(
            currentUser(deletedGroupAuthorId, "deleted-group-author"),
            deletedGroupRateChecked,
            """
                UPDATE community_group
                SET status = 'DELETED', deleted_at = NOW(), retain_until = DATE_ADD(NOW(), INTERVAL 30 DAY)
                WHERE id = ?
                """,
            () -> contentService.createPost(deletedGroupRequest, "deleted-parent-key"),
            deletedGroupId
        );

        assertThat(deletedGroupFailure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) deletedGroupFailure).getCode()).isEqualTo(ResultCode.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM community_post WHERE group_id = ?", Integer.class, deletedGroupId)).isZero();

        reset(rateLimitService);
        long departedGroupId = 8_221_211L;
        long departedGroupOwnerId = 8_221_212L;
        long departedAuthorId = 8_221_213L;
        insertGroup(departedGroupId, departedGroupOwnerId, "PLATFORM_VISIBLE", "departed-member-race");
        insertMember(departedGroupId, departedAuthorId, "MEMBER", "ACTIVE");
        CommunityRequests.CreatePost departedRequest = new CommunityRequests.CreatePost();
        departedRequest.groupId = departedGroupId;
        departedRequest.title = "成员退出竞争";
        departedRequest.content = "锁后不能再写入帖子";
        CountDownLatch departedRateChecked = new CountDownLatch(1);
        doAnswer(invocation -> {
            departedRateChecked.countDown();
            return null;
        }).when(rateLimitService).check(departedAuthorId, CommunityRateLimitService.Action.POST);

        Throwable departedFailure = executeAfterUncommittedMutation(
            currentUser(departedAuthorId, "departed-author"),
            departedRateChecked,
            """
                UPDATE community_group_member
                SET status = 'LEFT', left_at = NOW()
                WHERE group_id = ? AND user_id = ?
                """,
            () -> contentService.createPost(departedRequest, "departed-member-key"),
            departedGroupId, departedAuthorId
        );

        assertThat(departedFailure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) departedFailure).getCode()).isEqualTo(ResultCode.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM community_post WHERE group_id = ?", Integer.class, departedGroupId)).isZero();
    }

    @Test
    void joinWaitsForConcurrentGroupDeletionAndRejectsTheStaleActiveState() throws Exception {
        long groupId = 8_221_216L;
        long ownerId = 8_221_217L;
        long joiningUserId = 8_221_218L;
        insertGroup(groupId, ownerId, "PLATFORM_VISIBLE", "join-delete-race");

        Throwable failure = executeMembershipWriteAfterUncommittedGroupDeletion(
            currentUser(joiningUserId, "joining-user"),
            groupId,
            () -> groupService.join(groupId, "join-after-delete-key")
        );

        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).getCode()).isEqualTo(ResultCode.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_group_member
            WHERE group_id = ? AND user_id = ?
            """, Integer.class, groupId, joiningUserId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_governance_event
            WHERE event_type = 'MEMBERSHIP_REQUESTED' AND actor_user_id = ?
            """, Integer.class, joiningUserId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM community_group WHERE id = ?", String.class, groupId))
            .isEqualTo("DELETED");
    }

    @Test
    void membershipAdministrationRechecksDeletedGroupBeforeWritingMemberState() throws Exception {
        long decisionGroupId = 8_229_001L;
        long decisionOwnerId = 8_229_002L;
        long pendingUserId = 8_229_003L;
        insertGroupWithOwner(
            decisionGroupId, decisionOwnerId, "PLATFORM_VISIBLE", "decision-delete-race");
        jdbcTemplate.update("""
            INSERT INTO community_group_member(group_id, user_id, member_role, status)
            VALUES (?, ?, 'MEMBER', 'PENDING')
            """, decisionGroupId, pendingUserId);
        Long pendingMemberId = jdbcTemplate.queryForObject("""
            SELECT id FROM community_group_member WHERE group_id = ? AND user_id = ?
            """, Long.class, decisionGroupId, pendingUserId);
        CommunityRequests.MembershipDecision approve = new CommunityRequests.MembershipDecision();
        approve.decision = "APPROVE";

        Throwable decisionFailure = executeMembershipWriteAfterUncommittedGroupDeletion(
            currentUser(decisionOwnerId, "decision-owner"),
            decisionGroupId,
            () -> groupService.decideMembership(
                decisionGroupId, pendingMemberId, approve, "approve-after-delete")
        );

        assertThat(decisionFailure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) decisionFailure).getCode()).isEqualTo(ResultCode.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT status = 'PENDING'
              AND reviewed_by IS NULL
              AND reviewed_at IS NULL
              AND joined_at IS NULL
              AND last_idempotency_key IS NULL
            FROM community_group_member WHERE id = ?
            """, Boolean.class, pendingMemberId)).isTrue();
        assertThat(updateEventCount("MEMBERSHIP_DECIDED", pendingMemberId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM community_group WHERE id = ?", String.class, decisionGroupId))
            .isEqualTo("DELETED");

        long roleGroupId = 8_229_011L;
        long roleOwnerId = 8_229_012L;
        long roleUserId = 8_229_013L;
        insertGroupWithOwner(roleGroupId, roleOwnerId, "PLATFORM_VISIBLE", "role-delete-race");
        insertMember(roleGroupId, roleUserId, "MEMBER", "ACTIVE");
        Long roleMemberId = jdbcTemplate.queryForObject("""
            SELECT id FROM community_group_member WHERE group_id = ? AND user_id = ?
            """, Long.class, roleGroupId, roleUserId);

        Throwable roleFailure = executeMembershipWriteAfterUncommittedGroupDeletion(
            currentUser(roleOwnerId, "role-owner"),
            roleGroupId,
            () -> groupService.changeRole(
                roleGroupId, roleMemberId, changeMemberRole("ADMIN"), "role-after-delete")
        );

        assertThat(roleFailure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) roleFailure).getCode()).isEqualTo(ResultCode.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT member_role = 'MEMBER' AND last_idempotency_key IS NULL
            FROM community_group_member WHERE id = ?
            """, Boolean.class, roleMemberId)).isTrue();
        assertThat(updateEventCount("MEMBER_ROLE_CHANGED", roleMemberId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM community_group WHERE id = ?", String.class, roleGroupId))
            .isEqualTo("DELETED");

        long removalGroupId = 8_229_021L;
        long removalOwnerId = 8_229_022L;
        long removalUserId = 8_229_023L;
        insertGroupWithOwner(
            removalGroupId, removalOwnerId, "PLATFORM_VISIBLE", "removal-delete-race");
        insertMember(removalGroupId, removalUserId, "MEMBER", "ACTIVE");
        Long removalMemberId = jdbcTemplate.queryForObject("""
            SELECT id FROM community_group_member WHERE group_id = ? AND user_id = ?
            """, Long.class, removalGroupId, removalUserId);

        Throwable removalFailure = executeMembershipWriteAfterUncommittedGroupDeletion(
            currentUser(removalOwnerId, "removal-owner"),
            removalGroupId,
            () -> {
                groupService.removeMember(removalGroupId, removalMemberId, "remove-after-delete");
                return null;
            }
        );

        assertThat(removalFailure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) removalFailure).getCode()).isEqualTo(ResultCode.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT status = 'ACTIVE' AND left_at IS NULL AND last_idempotency_key IS NULL
            FROM community_group_member WHERE id = ?
            """, Boolean.class, removalMemberId)).isTrue();
        assertThat(updateEventCount("MEMBER_REMOVED", removalMemberId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM community_group WHERE id = ?", String.class, removalGroupId))
            .isEqualTo("DELETED");
    }

    @Test
    void groupModerationRechecksHiddenGroupBeforeWritingPostOrComment() throws Exception {
        long postGroupId = 8_228_001L;
        long postOwnerId = 8_228_002L;
        long postId = 8_228_003L;
        insertGroupWithOwner(postGroupId, postOwnerId, "PLATFORM_VISIBLE", "moderate-post-hidden-group");
        insertPost(postId, postGroupId, postOwnerId, "待组内处置帖子", "moderate-hidden-group");
        CommunityRequests.ModerationAction request = new CommunityRequests.ModerationAction();
        request.reasonCode = "POLICY";
        CountDownLatch postSnapshotEstablished = new CountDownLatch(1);

        Throwable postFailure = executeAfterUncommittedMutation(
            currentUser(postOwnerId, "post-group-owner"),
            postSnapshotEstablished,
            """
                UPDATE community_group
                SET status = 'HIDDEN', hidden_at = NOW(), deleted_at = NOW(),
                    retain_until = DATE_ADD(NOW(), INTERVAL 180 DAY), version_no = version_no + 1
                WHERE id = ?
                """,
            () -> {
                assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM community_group WHERE id = ?", String.class, postGroupId))
                    .isEqualTo("ACTIVE");
                postSnapshotEstablished.countDown();
                contentService.groupModerate(
                    postGroupId, "POST", postId, false, request, "moderate-post-after-hide");
                return null;
            },
            postGroupId
        );

        assertThat(postFailure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) postFailure).getCode()).isEqualTo(ResultCode.NOT_FOUND);
        assertThat(jdbcTemplate.queryForMap(
            "SELECT status, version_no FROM community_post WHERE id = ?", postId))
            .containsEntry("status", "PUBLISHED")
            .containsEntry("version_no", 0L);
        assertThat(updateEventCount("POST_HIDDEN", postId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM community_group WHERE id = ?", String.class, postGroupId))
            .isEqualTo("HIDDEN");

        long commentGroupId = 8_228_011L;
        long commentOwnerId = 8_228_012L;
        long parentPostId = 8_228_013L;
        long commentId = 8_228_014L;
        insertGroupWithOwner(
            commentGroupId, commentOwnerId, "PLATFORM_VISIBLE", "moderate-comment-hidden-group");
        insertPost(parentPostId, commentGroupId, commentOwnerId,
            "待处置评论父帖", "moderate-comment-hidden-group");
        insertComment(commentId, parentPostId, commentGroupId, commentOwnerId,
            "待组内处置评论", "moderate-hidden-group");
        CountDownLatch commentSnapshotEstablished = new CountDownLatch(1);

        Throwable commentFailure = executeAfterUncommittedMutation(
            currentUser(commentOwnerId, "comment-group-owner"),
            commentSnapshotEstablished,
            """
                UPDATE community_group
                SET status = 'HIDDEN', hidden_at = NOW(), deleted_at = NOW(),
                    retain_until = DATE_ADD(NOW(), INTERVAL 180 DAY), version_no = version_no + 1
                WHERE id = ?
                """,
            () -> {
                assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM community_group WHERE id = ?", String.class, commentGroupId))
                    .isEqualTo("ACTIVE");
                commentSnapshotEstablished.countDown();
                contentService.groupModerate(
                    commentGroupId, "COMMENT", commentId, false, request, "moderate-comment-after-hide");
                return null;
            },
            commentGroupId
        );

        assertThat(commentFailure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) commentFailure).getCode()).isEqualTo(ResultCode.NOT_FOUND);
        assertThat(jdbcTemplate.queryForMap(
            "SELECT status, version_no FROM community_comment WHERE id = ?", commentId))
            .containsEntry("status", "PUBLISHED")
            .containsEntry("version_no", 0L);
        assertThat(updateEventCount("COMMENT_HIDDEN", commentId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM community_group WHERE id = ?", String.class, commentGroupId))
            .isEqualTo("HIDDEN");
    }

    @Test
    void groupModerationRechecksHiddenParentPostBeforeWritingComment() throws Exception {
        long groupId = 8_228_021L;
        long ownerId = 8_228_022L;
        long postId = 8_228_023L;
        long commentId = 8_228_024L;
        insertGroupWithOwner(groupId, ownerId, "PLATFORM_VISIBLE", "moderate-comment-hidden-post");
        insertPost(postId, groupId, ownerId, "即将隐藏的父帖", "moderate-comment-hidden-post");
        insertComment(commentId, postId, groupId, ownerId,
            "父帖隐藏后不能处置的评论", "moderate-comment-hidden-post");
        CommunityRequests.ModerationAction request = new CommunityRequests.ModerationAction();
        request.reasonCode = "POLICY";
        CountDownLatch snapshotEstablished = new CountDownLatch(1);

        Throwable failure = executeAfterUncommittedMutation(
            currentUser(ownerId, "hidden-post-group-owner"),
            snapshotEstablished,
            """
                UPDATE community_post
                SET status = 'HIDDEN', hidden_by = ?, hidden_scope = 'PLATFORM',
                    hidden_reason_code = 'POLICY', hidden_at = NOW(), deleted_at = NOW(),
                    retain_until = DATE_ADD(NOW(), INTERVAL 180 DAY), version_no = version_no + 1
                WHERE id = ?
                """,
            () -> {
                assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM community_post WHERE id = ?", String.class, postId))
                    .isEqualTo("PUBLISHED");
                snapshotEstablished.countDown();
                contentService.groupModerate(
                    groupId, "COMMENT", commentId, false, request, "moderate-comment-after-parent-hide");
                return null;
            },
            ownerId, postId
        );

        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).getCode()).isEqualTo(ResultCode.CONFLICT);
        assertThat(jdbcTemplate.queryForMap(
            "SELECT status, version_no FROM community_comment WHERE id = ?", commentId))
            .containsEntry("status", "PUBLISHED")
            .containsEntry("version_no", 0L);
        assertThat(updateEventCount("COMMENT_HIDDEN", commentId)).isZero();
    }

    @Test
    void createCommentRechecksHiddenParentPostAfterLocks() throws Exception {
        long groupId = 8_221_221L;
        long ownerId = 8_221_222L;
        long authorId = 8_221_223L;
        long postId = 8_221_224L;
        insertGroup(groupId, ownerId, "PLATFORM_VISIBLE", "hidden-post-race");
        insertMember(groupId, authorId, "MEMBER", "ACTIVE");
        insertPost(postId, groupId, ownerId, "即将隐藏的父帖", "hidden-post-race");
        CommunityRequests.CreateComment request = new CommunityRequests.CreateComment();
        request.content = "父帖隐藏后不能落下这条评论";
        CountDownLatch rateChecked = new CountDownLatch(1);
        doAnswer(invocation -> {
            rateChecked.countDown();
            return null;
        }).when(rateLimitService).check(authorId, CommunityRateLimitService.Action.COMMENT);

        Throwable failure = executeAfterUncommittedMutation(
            currentUser(authorId, "comment-author"),
            rateChecked,
            """
                UPDATE community_post
                SET status = 'HIDDEN', hidden_by = ?, hidden_scope = 'PLATFORM',
                    hidden_reason_code = 'POLICY', hidden_at = NOW(), deleted_at = NOW(),
                    retain_until = DATE_ADD(NOW(), INTERVAL 180 DAY), version_no = version_no + 1
                WHERE id = ?
                """,
            () -> contentService.createComment(postId, request, "hidden-post-comment-key"),
            ownerId, postId
        );

        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).getCode()).isEqualTo(ResultCode.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM community_comment WHERE post_id = ?", Integer.class, postId)).isZero();
    }

    @Test
    void updatesReplayCurrentResultsRejectLateKeysAndKeepBodiesOutOfEvidence() {
        long groupId = 8_221_231L;
        long ownerId = 8_221_232L;
        long postId = 8_221_233L;
        long commentId = 8_221_234L;
        insertGroupWithOwner(groupId, ownerId, "PLATFORM_VISIBLE", "update-idempotency");
        insertPost(postId, groupId, ownerId, "初始标题", "update-idempotency");
        insertComment(commentId, postId, groupId, ownerId, "初始评论", "update-idempotency");
        CurrentUser owner = currentUser(ownerId, "update-owner");

        CommunityRequests.UpdateGroup groupFirst = updateGroup(0L, "第一版小组", "第一版简介");
        CommunityRequests.UpdateGroup groupSecond = updateGroup(1L, "第二版小组", "第二版简介");
        assertThat(executeInNewTransaction(owner,
            () -> groupService.update(groupId, groupFirst, "group-update-1")).versionNo).isEqualTo(1L);
        assertThat(executeInNewTransaction(owner,
            () -> groupService.update(groupId, groupFirst, "group-update-1")).versionNo).isEqualTo(1L);
        assertThat(executeInNewTransaction(owner,
            () -> groupService.update(groupId, groupSecond, "group-update-2")).versionNo).isEqualTo(2L);
        assertThatThrownBy(() -> executeInNewTransaction(owner,
            () -> groupService.update(groupId, groupFirst, "group-update-1")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已有后续修改");
        assertThatThrownBy(() -> executeInNewTransaction(owner,
            () -> groupService.update(groupId, updateGroup(0L, "旧版本小组", "旧版本简介"), "group-update-3")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请刷新后重试");

        CommunityRequests.UpdatePost postFirst = updatePost(0L, "第一版帖子", "第一版帖子正文");
        CommunityRequests.UpdatePost postSecond = updatePost(1L, "第二版帖子", "第二版帖子正文");
        assertThat(executeInNewTransaction(owner,
            () -> contentService.updatePost(postId, postFirst, "post-update-1")).versionNo).isEqualTo(1L);
        assertThat(executeInNewTransaction(owner,
            () -> contentService.updatePost(postId, postFirst, "post-update-1")).versionNo).isEqualTo(1L);
        assertThat(executeInNewTransaction(owner,
            () -> contentService.updatePost(postId, postSecond, "post-update-2")).versionNo).isEqualTo(2L);
        assertThatThrownBy(() -> executeInNewTransaction(owner,
            () -> contentService.updatePost(postId, postFirst, "post-update-1")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已有后续修改");

        CommunityRequests.UpdateComment commentFirst = updateComment(0L, "第一版评论正文");
        CommunityRequests.UpdateComment commentSecond = updateComment(1L, "第二版评论正文");
        assertThat(executeInNewTransaction(owner,
            () -> contentService.updateComment(commentId, commentFirst, "comment-update-1")).versionNo)
            .isEqualTo(1L);
        assertThat(executeInNewTransaction(owner,
            () -> contentService.updateComment(commentId, commentFirst, "comment-update-1")).versionNo)
            .isEqualTo(1L);
        assertThat(executeInNewTransaction(owner,
            () -> contentService.updateComment(commentId, commentSecond, "comment-update-2")).versionNo)
            .isEqualTo(2L);
        assertThatThrownBy(() -> executeInNewTransaction(owner,
            () -> contentService.updateComment(
                commentId, updateComment(1L, "同键不同评论正文"), "comment-update-2")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("同一 Idempotency-Key");

        assertThat(queryStrings("""
            SELECT CONCAT(event_type, ':', COUNT(*))
            FROM community_governance_event
            WHERE subject_id IN (%d, %d, %d)
              AND event_type IN ('GROUP_UPDATED','POST_UPDATED','COMMENT_UPDATED')
            GROUP BY event_type
            ORDER BY event_type
            """.formatted(groupId, postId, commentId)))
            .containsExactly("COMMENT_UPDATED:2", "GROUP_UPDATED:2", "POST_UPDATED:2");
        assertThat(queryStrings("""
            SELECT metadata_json
            FROM community_governance_event
            WHERE subject_id IN (%d, %d, %d)
              AND event_type IN ('GROUP_UPDATED','POST_UPDATED','COMMENT_UPDATED')
            """.formatted(groupId, postId, commentId)))
            .allSatisfy(metadata -> assertThat(metadata)
                .contains("requestHash", "baseVersion", "resultVersion")
                .doesNotContain("第一版简介", "第二版简介", "第一版帖子正文", "第二版帖子正文",
                    "第一版评论正文", "第二版评论正文"));
    }

    @Test
    void concurrentPostUpdatesSerializeSameKeyReplayAndDifferentKeyConflict() throws Exception {
        long groupId = 8_221_241L;
        long ownerId = 8_221_242L;
        long sameKeyPostId = 8_221_243L;
        long differentKeyPostId = 8_221_244L;
        insertGroupWithOwner(groupId, ownerId, "PLATFORM_VISIBLE", "update-race");
        insertPost(sameKeyPostId, groupId, ownerId, "并发初始标题", "same-update-race");
        insertPost(differentKeyPostId, groupId, ownerId, "竞争初始标题", "different-update-race");
        CurrentUser owner = currentUser(ownerId, "concurrent-update-owner");
        CommunityRequests.UpdatePost sameRequest = updatePost(0L, "同键更新标题", "同键更新正文");

        List<CommunityPost> sameKey = executeConcurrentlyInNewTransactions(
            owner,
            () -> contentService.updatePost(sameKeyPostId, sameRequest, "same-update-key"),
            () -> contentService.updatePost(sameKeyPostId, sameRequest, "same-update-key")
        );
        assertThat(sameKey).extracting(post -> post.versionNo).containsOnly(1L);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT version_no FROM community_post WHERE id = ?", Long.class, sameKeyPostId)).isEqualTo(1L);
        assertThat(updateEventCount("POST_UPDATED", sameKeyPostId)).isEqualTo(1);

        CommunityRequests.UpdatePost first = updatePost(0L, "竞争标题甲", "竞争正文甲");
        CommunityRequests.UpdatePost second = updatePost(0L, "竞争标题乙", "竞争正文乙");
        List<CommandOutcome> differentKeys = executeConcurrently(
            () -> updatePostOutcome(owner, differentKeyPostId, first, "different-update-a"),
            () -> updatePostOutcome(owner, differentKeyPostId, second, "different-update-b")
        );
        assertThat(differentKeys).filteredOn(CommandOutcome::succeeded).hasSize(1);
        assertThat(differentKeys).filteredOn(outcome -> !outcome.succeeded()).singleElement()
            .extracting(CommandOutcome::code).isEqualTo(ResultCode.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT version_no FROM community_post WHERE id = ?", Long.class, differentKeyPostId)).isEqualTo(1L);
        assertThat(updateEventCount("POST_UPDATED", differentKeyPostId)).isEqualTo(1);
    }

    @Test
    void concurrentPlatformModerationServiceReplayHidesPostOnceAndRecordsOneEvent() throws Exception {
        long groupId = 8_222_001L;
        long postId = 8_222_101L;
        long actorId = 8_222_201L;
        insertGroup(groupId, 8_222_301L, "PLATFORM_VISIBLE", "service-moderation-race");
        insertPost(postId, groupId, 8_222_401L, "并发处置帖子", "service-moderation-race");
        CommunityRequests.ModerationAction request = new CommunityRequests.ModerationAction();
        request.reasonCode = "POLICY";

        CurrentUser actor = currentUser(actorId, "concurrent-operator");
        List<Boolean> results = executeConcurrentlyInNewTransactions(
            actor,
            () -> moderatePost(request, postId),
            () -> moderatePost(request, postId)
        );

        assertThat(results).containsExactly(true, true);
        Map<String, Object> persisted = jdbcTemplate.queryForMap("""
            SELECT status, version_no, hidden_by, hidden_scope, hidden_reason_code
            FROM community_post
            WHERE id = ?
            """, postId);
        assertThat(persisted)
            .containsEntry("status", "HIDDEN")
            .containsEntry("version_no", 1L)
            .containsEntry("hidden_by", actorId)
            .containsEntry("hidden_scope", "PLATFORM")
            .containsEntry("hidden_reason_code", "POLICY");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_governance_event
            WHERE event_type = 'POST_HIDDEN'
              AND subject_type = 'POST'
              AND subject_id = ?
              AND actor_user_id = ?
            """, Integer.class, postId, actorId)).isEqualTo(1);
    }

    @Test
    void mapperQueriesApplyDiscoveryMembershipAndBidirectionalBlockSemantics() {
        long publicGroup = 8_230_001L;
        long membersGroup = 8_230_002L;
        long forwardBlockedGroup = 8_230_003L;
        long reverseBlockedGroup = 8_230_004L;
        long publicOwner = 8_231_001L;
        long membersOwner = 8_231_002L;
        long forwardBlockedOwner = 8_231_003L;
        long reverseBlockedOwner = 8_231_004L;

        insertGroupWithOwner(publicGroup, publicOwner, "PLATFORM_VISIBLE", "public");
        insertGroupWithOwner(membersGroup, membersOwner, "MEMBERS_ONLY", "members");
        insertGroupWithOwner(forwardBlockedGroup, forwardBlockedOwner, "PLATFORM_VISIBLE", "blocked-forward");
        insertGroupWithOwner(reverseBlockedGroup, reverseBlockedOwner, "PLATFORM_VISIBLE", "blocked-reverse");
        insertMember(membersGroup, VIEWER_ID, "MEMBER", "ACTIVE");
        insertBlock(VIEWER_ID, forwardBlockedOwner, "viewer-blocks-owner");
        insertBlock(reverseBlockedOwner, VIEWER_ID, "owner-blocks-viewer");

        long visibleAuthor = 8_232_001L;
        long forwardBlockedAuthor = 8_232_002L;
        long reverseBlockedAuthor = 8_232_003L;
        long publicPost = 8_233_001L;
        long membersPost = 8_233_002L;
        long forwardBlockedPost = 8_233_003L;
        long reverseBlockedPost = 8_233_004L;
        long blockedOwnerPost = 8_233_005L;
        insertPost(publicPost, publicGroup, visibleAuthor, "公开帖子", "public-post");
        insertPost(membersPost, membersGroup, visibleAuthor, "成员帖子", "members-post");
        insertPost(forwardBlockedPost, publicGroup, forwardBlockedAuthor, "正向屏蔽帖子", "blocked-author-a");
        insertPost(reverseBlockedPost, publicGroup, reverseBlockedAuthor, "反向屏蔽帖子", "blocked-author-b");
        insertPost(blockedOwnerPost, forwardBlockedGroup, visibleAuthor, "屏蔽组主帖子", "blocked-owner-post");
        insertBlock(VIEWER_ID, forwardBlockedAuthor, "viewer-blocks-author");
        insertBlock(reverseBlockedAuthor, VIEWER_ID, "author-blocks-viewer");

        assertThat(groupMapper.searchVisible(VIEWER_ID, null, null, null, 20))
            .extracting(row -> row.id)
            .containsExactlyInAnyOrder(publicGroup, membersGroup);
        assertThat(groupMapper.searchVisible(NON_MEMBER_ID, null, null, null, 20))
            .extracting(row -> row.id)
            .contains(membersGroup);
        assertThat(postMapper.searchVisible(VIEWER_ID, null, null, null, null, false, null, 20))
            .extracting(row -> row.id)
            .containsExactlyInAnyOrder(publicPost, membersPost);
        assertThat(postMapper.searchVisible(NON_MEMBER_ID, null, null, null, null, false, null, 20))
            .extracting(row -> row.id)
            .doesNotContain(membersPost);
        assertThat(accessService.requireVisiblePost(membersPost, VIEWER_ID).id).isEqualTo(membersPost);
        assertThat(accessService.requireVisibleGroup(membersGroup, NON_MEMBER_ID).id).isEqualTo(membersGroup);
        assertThatThrownBy(() -> accessService.requireVisiblePost(membersPost, NON_MEMBER_ID))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accessService.requireVisiblePost(forwardBlockedPost, VIEWER_ID))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accessService.requireVisiblePost(reverseBlockedPost, VIEWER_ID))
            .isInstanceOf(BusinessException.class);

        long visibleComment = 8_234_001L;
        long forwardBlockedComment = 8_234_002L;
        long reverseBlockedComment = 8_234_003L;
        long membersComment = 8_234_004L;
        insertComment(visibleComment, publicPost, publicGroup, visibleAuthor, "公开评论", "public-comment");
        insertComment(forwardBlockedComment, publicPost, publicGroup, forwardBlockedAuthor,
            "正向屏蔽评论", "blocked-comment-a");
        insertComment(reverseBlockedComment, publicPost, publicGroup, reverseBlockedAuthor,
            "反向屏蔽评论", "blocked-comment-b");
        insertComment(membersComment, membersPost, membersGroup, visibleAuthor, "成员评论", "members-comment");

        assertThat(commentMapper.listVisible(VIEWER_ID, publicPost, null, 20))
            .extracting(row -> row.id)
            .containsExactly(visibleComment);
        assertThat(commentMapper.listVisible(VIEWER_ID, membersPost, null, 20))
            .extracting(row -> row.id)
            .containsExactly(membersComment);
        assertThat(commentMapper.listVisible(NON_MEMBER_ID, membersPost, null, 20)).isEmpty();
        assertThat(accessService.requireVisibleComment(visibleComment, VIEWER_ID).id).isEqualTo(visibleComment);
        assertThatThrownBy(() -> accessService.requireVisibleComment(forwardBlockedComment, VIEWER_ID))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accessService.requireVisibleComment(reverseBlockedComment, VIEWER_ID))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void postSearchAppliesCategoryAndPlatformScopeAndDetailKeepsVisibilitySemantics() {
        long studyGroup = 8_250_001L;
        long sportsGroup = 8_250_002L;
        long membersStudy = 8_250_003L;
        insertCategorizedGroup(studyGroup, 8_251_001L, "PLATFORM_VISIBLE", "feed-study", "STUDY");
        insertCategorizedGroup(sportsGroup, 8_251_002L, "PLATFORM_VISIBLE", "feed-sports", "SPORTS");
        insertCategorizedGroup(membersStudy, 8_251_003L, "MEMBERS_ONLY", "feed-members", "STUDY");
        insertMember(membersStudy, VIEWER_ID, "MEMBER", "ACTIVE");

        long studyPost = 8_252_001L;
        long sportsPost = 8_252_002L;
        long membersPost = 8_252_003L;
        insertPost(studyPost, studyGroup, 8_253_001L, "共读帖", "feed-study-post");
        insertPost(sportsPost, sportsGroup, 8_253_001L, "约球帖", "feed-sports-post");
        insertPost(membersPost, membersStudy, 8_253_001L, "内部帖", "feed-members-post");

        // 类别过滤按所属小组 category_code；本用例无未分类小组，OTHER 应为空。
        assertThat(postMapper.searchVisible(VIEWER_ID, null, null, null, "STUDY", false, null, 20))
            .extracting(row -> row.id).containsExactlyInAnyOrder(studyPost, membersPost);
        assertThat(postMapper.searchVisible(VIEWER_ID, null, null, null, "OTHER", false, null, 20)).isEmpty();

        // 广场阅读流：platformOnly 排除成员可见帖，即使阅读者是 ACTIVE 成员。
        assertThat(postMapper.searchVisible(VIEWER_ID, null, null, null, null, true, null, 20))
            .extracting(row -> row.id).containsExactlyInAnyOrder(studyPost, sportsPost);

        // 详情聚合：成员可读成员帖并携带小组来源字段；非成员与被屏蔽方向均为 null（服务层 404）。
        CommunityQueryRows.PostRow detail = postMapper.findVisibleDetail(VIEWER_ID, membersPost);
        assertThat(detail).isNotNull();
        assertThat(detail.groupName).isEqualTo("小组-feed-members");
        assertThat(detail.groupVisibility).isEqualTo("MEMBERS_ONLY");
        assertThat(postMapper.findVisibleDetail(NON_MEMBER_ID, membersPost)).isNull();

        insertBlock(NON_MEMBER_ID, 8_253_001L, "feed-detail-block");
        assertThat(postMapper.findVisibleDetail(NON_MEMBER_ID, studyPost)).isNull();
    }

    @Test
    void moderationListingReturnsPublishedAndHiddenWithGroupCategoryForManagers() {
        insertGroupWithOwner(8_270_001L, 8_270_011L, "PLATFORM_VISIBLE", "mod-list");
        long publishedPost = 8_270_101L;
        long hiddenPost = 8_270_102L;
        insertPost(publishedPost, 8_270_001L, 8_270_021L, "正常帖", "mod-normal");
        insertHiddenPost(hiddenPost, 8_270_001L, 8_270_021L,
            RETENTION_BASE.plusDays(90), "mod-hidden");

        // ALL 覆盖 PUBLISHED+HIDDEN，HIDDEN 单列；状态列原样透出供处置面标注。
        assertThat(postMapper.listForModeration(8_270_001L, "ALL", null, 20))
            .extracting(row -> row.id).containsExactly(hiddenPost, publishedPost);
        assertThat(postMapper.listForModeration(8_270_001L, "HIDDEN", null, 20))
            .extracting(row -> row.id).containsExactly(hiddenPost);
        assertThat(postMapper.listForModeration(8_270_001L, "ALL", null, 20))
            .extracting(row -> row.status).containsExactlyInAnyOrder("PUBLISHED", "HIDDEN");

        // 常规可见性读取不受治理维度影响：HIDDEN 帖对成员也不可见。
        insertMember(8_270_001L, VIEWER_ID, "MEMBER", "ACTIVE");
        assertThat(postMapper.searchVisible(VIEWER_ID, 8_270_001L, null, null, null, false, null, 20))
            .extracting(row -> row.id).containsExactly(publishedPost);
    }

    @Test
    void compositeForeignKeyRejectsCommentPointingAtPostInAnotherGroup() {
        long firstGroup = 8_240_001L;
        long secondGroup = 8_240_002L;
        long postId = 8_241_001L;
        insertGroup(firstGroup, 8_242_001L, "PLATFORM_VISIBLE", "first-group");
        insertGroup(secondGroup, 8_242_002L, "PLATFORM_VISIBLE", "second-group");
        insertPost(postId, firstGroup, 8_243_001L, "跨组外键", "cross-group-post");

        assertConstraintViolation(() -> insertComment(
            8_244_001L, postId, secondGroup, 8_243_002L, "错误归组", "cross-group-comment"
        ), 1452);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM community_comment WHERE id = 8244001", Integer.class)).isZero();
    }

    @Test
    void retentionBoundariesIncludeThirtyDayDueTimeAndEnforceOneHundredEightyAndThreeHundredSixtyFiveDays() {
        long groupId = 8_250_001L;
        insertGroup(groupId, 8_251_001L, "PLATFORM_VISIBLE", "retention-group");
        long duePost = 8_252_001L;
        long futurePost = 8_252_002L;
        insertDeletedPost(duePost, groupId, RETENTION_BASE, RETENTION_BASE.plusDays(30), "due-post");
        insertDeletedPost(futurePost, groupId, RETENTION_BASE,
            RETENTION_BASE.plusDays(30).plusSeconds(1), "future-post");

        assertThat(retentionMapper.selectDuePostIds(RETENTION_BASE.plusDays(30), 10))
            .extracting(candidate -> candidate.id)
            .containsExactly(duePost);

        LocalDateTime closedAt = RETENTION_BASE.plusDays(2);
        insertClosedReport(8_253_001L, duePost, closedAt, closedAt.plusDays(180),
            RETENTION_BASE.plusDays(365), "report-180-exact");
        assertConstraintViolation(() -> insertClosedReport(
            8_253_002L, duePost, closedAt, closedAt.plusDays(180).minusSeconds(1),
            RETENTION_BASE.plusDays(365), "report-180-short"
        ), 3819);
        assertConstraintViolation(() -> insertOpenReport(
            8_253_003L, duePost, RETENTION_BASE.plusDays(365).minusSeconds(1), "report-365-short"
        ), 3819);

        insertGovernanceEvent(8_254_001L, duePost, RETENTION_BASE.plusDays(365), "event-365-exact");
        assertConstraintViolation(() -> insertGovernanceEvent(
            8_254_002L, duePost, RETENTION_BASE.plusDays(365).minusSeconds(1), "event-365-short"
        ), 3819);
    }

    @Test
    void mainGroupPostCommentAndReportQueriesUseV15IndexesWithoutFullTableScans() {
        ScaleFixture scale = insertScaleFixture(1_200);

        List<Map<String, Object>> groupPlan = jdbcTemplate.queryForList("""
            EXPLAIN
            SELECT g.id, g.group_no, g.name, g.description, g.owner_user_id,
                   owner.nickname AS owner_nickname, owner.avatar AS owner_avatar,
                   g.visibility, g.join_policy, g.status,
                   mine.member_role AS current_member_role, mine.status AS current_member_status,
                   (SELECT COUNT(*) FROM community_group_member members
                     WHERE members.group_id = g.id AND members.status = 'ACTIVE') AS member_count,
                   g.version_no, g.create_time, g.update_time
            FROM community_group g
            LEFT JOIN sys_user owner ON owner.id = g.owner_user_id AND owner.deleted = 0
            LEFT JOIN community_group_member mine
              ON mine.group_id = g.id AND mine.user_id = ?
            WHERE g.status = 'ACTIVE'
              AND NOT EXISTS (
                SELECT 1 FROM community_user_block b
                WHERE b.status = 'ACTIVE'
                  AND ((b.blocker_user_id = ? AND b.blocked_user_id = g.owner_user_id)
                    OR (b.blocker_user_id = g.owner_user_id AND b.blocked_user_id = ?))
              )
              AND g.id < ?
            ORDER BY g.id DESC
            LIMIT 21
            """, VIEWER_ID, VIEWER_ID, VIEWER_ID, scale.cursorId());
        assertIndexedAccess(groupPlan, "g", "idx_community_group_discovery", "idx_community_group_name");

        List<Map<String, Object>> postPlan = jdbcTemplate.queryForList("""
            EXPLAIN
            SELECT p.id, p.post_no, p.group_id, g.name AS group_name, p.author_user_id,
                   author.nickname AS author_nickname, author.avatar AS author_avatar,
                   p.title, p.content_text, p.status, p.version_no,
                   p.published_at, p.create_time, p.update_time
            FROM community_post p
            JOIN community_group g ON g.id = p.group_id AND g.status = 'ACTIVE'
            LEFT JOIN community_group_member mine
              ON mine.group_id = g.id AND mine.user_id = ?
            LEFT JOIN sys_user author ON author.id = p.author_user_id AND author.deleted = 0
            WHERE p.status = 'PUBLISHED'
              AND (g.visibility = 'PLATFORM_VISIBLE' OR mine.status = 'ACTIVE')
              AND NOT EXISTS (
                SELECT 1 FROM community_user_block b
                WHERE b.status = 'ACTIVE'
                  AND ((b.blocker_user_id = ? AND b.blocked_user_id IN (p.author_user_id, g.owner_user_id))
                    OR (b.blocked_user_id = ? AND b.blocker_user_id IN (p.author_user_id, g.owner_user_id)))
              )
              AND p.group_id = ?
              AND p.id < ?
            ORDER BY p.id DESC
            LIMIT 21
            """, VIEWER_ID, VIEWER_ID, VIEWER_ID, scale.targetGroupId(), scale.cursorId());
        assertIndexedAccess(postPlan, "p", "idx_community_post_group_cursor");

        List<Map<String, Object>> commentPlan = jdbcTemplate.queryForList("""
            EXPLAIN
            SELECT c.id, c.comment_no, c.post_id, c.group_id, c.author_user_id,
                   author.nickname AS author_nickname, author.avatar AS author_avatar,
                   c.content_text, c.status, c.version_no,
                   c.published_at, c.create_time, c.update_time
            FROM community_comment c
            JOIN community_post p ON p.id = c.post_id AND p.status = 'PUBLISHED'
            JOIN community_group g ON g.id = c.group_id AND g.status = 'ACTIVE'
            LEFT JOIN community_group_member mine
              ON mine.group_id = g.id AND mine.user_id = ?
            LEFT JOIN sys_user author ON author.id = c.author_user_id AND author.deleted = 0
            WHERE c.post_id = ?
              AND c.status = 'PUBLISHED'
              AND (g.visibility = 'PLATFORM_VISIBLE' OR mine.status = 'ACTIVE')
              AND NOT EXISTS (
                SELECT 1 FROM community_user_block b
                WHERE b.status = 'ACTIVE'
                  AND ((b.blocker_user_id = ? AND b.blocked_user_id = c.author_user_id)
                    OR (b.blocker_user_id = c.author_user_id AND b.blocked_user_id = ?))
              )
              AND c.id < ?
            ORDER BY c.id DESC
            LIMIT 21
            """, VIEWER_ID, scale.targetPostId(), VIEWER_ID, VIEWER_ID, scale.cursorId());
        assertIndexedAccess(commentPlan, "c", "idx_community_comment_post_cursor");

        List<Map<String, Object>> reportPlan = jdbcTemplate.queryForList("""
            EXPLAIN
            SELECT r.id, r.report_no, r.reporter_user_id,
                   reporter.nickname AS reporter_nickname, reporter.avatar AS reporter_avatar,
                   r.target_type, r.target_id, r.reason_text, r.status, r.assigned_to,
                   r.decision_code, r.internal_note, r.closed_at, r.create_time, r.update_time
            FROM community_report r
            LEFT JOIN sys_user reporter ON reporter.id = r.reporter_user_id AND reporter.deleted = 0
            WHERE r.status = 'OPEN'
              AND r.id < ?
            ORDER BY r.id DESC
            LIMIT 21
            """, scale.cursorId());
        assertIndexedAccess(reportPlan, "r", "idx_community_report_queue");
    }

    @Test
    void retentionPurgesAppendMinimalScheduledEvidenceAndReplayDoesNotDuplicateIt() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime deletedAt = now.minusDays(31);
        long groupId = 8_255_001L;
        long postId = 8_255_101L;
        long reportId = 8_255_201L;
        insertGroup(groupId, 8_255_301L, "PLATFORM_VISIBLE", "retention-evidence");
        insertDeletedPost(postId, groupId, deletedAt, now.minusDays(1), "retention-evidence");
        insertClosedReport(
            reportId,
            postId,
            now.minusDays(181),
            now.minusDays(1),
            now.plusDays(365),
            "retention-evidence"
        );

        retentionService.runOnce();
        retentionService.runOnce();

        assertThat(jdbcTemplate.queryForMap("""
            SELECT status, title, content_text
            FROM community_post
            WHERE id = ?
            """, postId))
            .containsEntry("status", "PURGED")
            .containsEntry("title", null)
            .containsEntry("content_text", null);
        assertThat(jdbcTemplate.queryForMap("""
            SELECT reason_text, internal_note, reason_purged_at IS NOT NULL AS reason_purged
            FROM community_report
            WHERE id = ?
            """, reportId))
            .containsEntry("reason_text", null)
            .containsEntry("internal_note", null)
            .containsEntry("reason_purged", 1L);
        assertThat(jdbcTemplate.queryForList("""
            SELECT event_type, subject_type, subject_id, actor_user_id, actor_scope, source_type,
                   from_status, to_status, reason_code, metadata_json
            FROM community_governance_event
            WHERE event_type IN ('POST_PURGED', 'REPORT_REASON_PURGED')
            ORDER BY event_type
            """))
            .hasSize(2)
            .allSatisfy(event -> {
                assertThat(event)
                    .containsEntry("actor_user_id", null)
                    .containsEntry("actor_scope", "SYSTEM")
                    .containsEntry("source_type", "SCHEDULED_JOB")
                    .containsEntry("reason_code", "RETENTION_EXPIRED")
                    .containsEntry("metadata_json", "{}");
                assertThat(event.get("subject_id")).isIn(postId, reportId);
            });
    }

    @Test
    void concurrentRetentionRunsPurgeOnceAndAppendOneEvent() throws Exception {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        long groupId = 8_256_001L;
        long postId = 8_256_101L;
        insertGroup(groupId, 8_256_201L, "PLATFORM_VISIBLE", "retention-race");
        insertDeletedPost(postId, groupId, now.minusDays(31), now.minusMinutes(1), "retention-race");
        CommunityRetentionService first = new CommunityRetentionService(
            retentionProperties, retentionMapper, deletedUserSnapshotMapper, eventService, transactionManager);
        CommunityRetentionService second = new CommunityRetentionService(
            retentionProperties, retentionMapper, deletedUserSnapshotMapper, eventService, transactionManager);

        executeConcurrentlyInNewTransactions(
            currentUser(8_256_301L, "retention-runner"),
            () -> runRetention(first),
            () -> runRetention(second)
        );

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM community_post WHERE id = ?", String.class, postId)).isEqualTo("PURGED");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM community_governance_event
            WHERE event_type = 'POST_PURGED'
              AND subject_type = 'POST'
              AND subject_id = ?
              AND source_type = 'SCHEDULED_JOB'
            """, Integer.class, postId)).isEqualTo(1);
    }

    @Test
    void scheduledEventFailureRollsBackThePhysicalPurgeInMySql() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        long groupId = 8_257_001L;
        long postId = 8_257_101L;
        insertGroup(groupId, 8_257_201L, "PLATFORM_VISIBLE", "retention-rollback");
        insertDeletedPost(postId, groupId, now.minusDays(31), now.minusMinutes(1), "retention-rollback");
        CommunityGovernanceEventService failingEventService = mock(CommunityGovernanceEventService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("event persistence failed"))
            .when(failingEventService).record(org.mockito.ArgumentMatchers.any());
        CommunityRetentionService failingService = new CommunityRetentionService(
            retentionProperties,
            retentionMapper,
            deletedUserSnapshotMapper,
            failingEventService,
            transactionManager
        );

        failingService.runOnce();

        assertThat(jdbcTemplate.queryForMap("""
            SELECT status, title, content_text, purged_at
            FROM community_post
            WHERE id = ?
            """, postId))
            .containsEntry("status", "DELETED")
            .containsEntry("title", "待清理帖子")
            .containsEntry("content_text", "待清理正文")
            .containsEntry("purged_at", null);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM community_governance_event", Integer.class)).isZero();
    }

    private void assertSingleUniqueWinner(List<InsertOutcome> outcomes) {
        assertThat(outcomes).filteredOn(InsertOutcome::success).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.success()).singleElement()
            .satisfies(outcome -> {
                assertThat(outcome.sqlState()).startsWith("23");
                assertThat(outcome.errorCode()).isEqualTo(1062);
            });
    }

    private CommunityGovernanceEventService.EventCommand creationEvent(long actorId, long subjectId,
                                                                         String idempotencyKey) {
        CommunityGovernanceEventService.EventCommand command =
            new CommunityGovernanceEventService.EventCommand();
        command.eventType = "POST_CREATED";
        command.subjectType = "POST";
        command.subjectId = subjectId;
        command.actorUserId = actorId;
        command.actorScope = CommunityActorScope.SELF;
        command.sourceType = CommunitySourceType.USER;
        command.toStatus = "PUBLISHED";
        command.resultStatus = "SUCCEEDED";
        command.idempotencyKey = idempotencyKey;
        command.metadata = Map.of("groupId", 8_206_201L);
        command.occurredAt = RETENTION_BASE;
        return command;
    }

    private CommunityRequests.ChangeMemberRole changeMemberRole(String role) {
        CommunityRequests.ChangeMemberRole request = new CommunityRequests.ChangeMemberRole();
        request.role = role;
        return request;
    }

    private List<InsertOutcome> insertPostsConcurrently(PostInsert first, PostInsert second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<InsertOutcome> firstResult = executor.submit(() -> insertPostAfterStart(start, first));
            Future<InsertOutcome> secondResult = executor.submit(() -> insertPostAfterStart(start, second));
            start.countDown();
            return List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private CurrentUser currentUser(long id, String username) {
        return new CurrentUser(id, username, Set.of(), Set.of());
    }

    private boolean moderatePost(CommunityRequests.ModerationAction request, long postId) {
        governanceService.moderate("POST", postId, false, request, "service-moderation-key");
        return true;
    }

    private boolean runRetention(CommunityRetentionService service) {
        service.runOnce();
        return true;
    }

    private CommunityRequests.UpdateGroup updateGroup(long versionNo, String name, String description) {
        CommunityRequests.UpdateGroup request = new CommunityRequests.UpdateGroup();
        request.versionNo = versionNo;
        request.name = name;
        request.description = description;
        request.visibility = "PLATFORM_VISIBLE";
        request.joinPolicy = "DIRECT";
        request.category = "STUDY";
        return request;
    }

    private CommunityRequests.UpdatePost updatePost(long versionNo, String title, String content) {
        CommunityRequests.UpdatePost request = new CommunityRequests.UpdatePost();
        request.versionNo = versionNo;
        request.title = title;
        request.content = content;
        return request;
    }

    private CommunityRequests.UpdateComment updateComment(long versionNo, String content) {
        CommunityRequests.UpdateComment request = new CommunityRequests.UpdateComment();
        request.versionNo = versionNo;
        request.content = content;
        return request;
    }

    private CommandOutcome updatePostOutcome(CurrentUser user, long postId,
                                             CommunityRequests.UpdatePost request, String key) {
        try {
            executeInNewTransaction(user, () -> contentService.updatePost(postId, request, key));
            return new CommandOutcome(true, null);
        } catch (BusinessException ex) {
            return new CommandOutcome(false, ex.getCode());
        }
    }

    private int updateEventCount(String eventType, long subjectId) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_governance_event
            WHERE event_type = ? AND subject_id = ?
            """, Integer.class, eventType, subjectId);
    }

    private Throwable executeAfterUncommittedMutation(CurrentUser user,
                                                       CountDownLatch operationReachedRateLimit,
                                                       String mutationSql,
                                                       Supplier<?> operation,
                                                       Object... parameters) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(mutationSql)) {
                    for (int index = 0; index < parameters.length; index++) {
                        statement.setObject(index + 1, parameters[index]);
                    }
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                Future<Throwable> result = executor.submit(() -> {
                    try {
                        executeInNewTransaction(user, operation::get);
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                });
                assertThat(operationReachedRateLimit.await(10, TimeUnit.SECONDS)).isTrue();
                connection.commit();
                return result.get(30, TimeUnit.SECONDS);
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Throwable executeMembershipWriteAfterUncommittedGroupDeletion(CurrentUser user,
                                                                           long groupId,
                                                                           Supplier<?> operation) throws Exception {
        CountDownLatch operationStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE community_group
                    SET status = 'DELETED', deleted_at = NOW(),
                        retain_until = DATE_ADD(NOW(), INTERVAL 30 DAY), version_no = version_no + 1
                    WHERE id = ?
                    """)) {
                    statement.setLong(1, groupId);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                Future<Throwable> result = executor.submit(() -> {
                    try {
                        executeInNewTransaction(user, () -> {
                            assertThat(jdbcTemplate.queryForObject(
                                "SELECT status FROM community_group WHERE id = ?", String.class, groupId))
                                .isEqualTo("ACTIVE");
                            operationStarted.countDown();
                            return operation.get();
                        });
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                });
                assertThat(operationStarted.await(10, TimeUnit.SECONDS)).isTrue();
                awaitCommunityGroupLockWait(result);
                connection.commit();
                return result.get(30, TimeUnit.SECONDS);
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitCommunityGroupLockWait(Future<?> result) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (result.isDone()) {
                throw new AssertionError("membership write completed before the group deletion committed");
            }
            boolean waiting = jdbcTemplate.queryForList("SHOW PROCESSLIST").stream()
                .map(process -> String.valueOf(process.get("Info")).toLowerCase(Locale.ROOT))
                .anyMatch(query -> query.contains("community_group") && query.contains("for update"));
            if (waiting) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("membership write did not reach the community group row lock");
    }

    private <T> List<T> executeConcurrentlyInNewTransactions(CurrentUser user,
                                                              Supplier<T> first,
                                                              Supplier<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<T> firstResult = executor.submit(() -> executeInNewTransaction(user, ready, start, first));
            Future<T> secondResult = executor.submit(() -> executeInNewTransaction(user, ready, start, second));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private <T> List<T> executeConcurrently(Supplier<T> first, Supplier<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<T> firstResult = executor.submit(() -> executeAfterStart(ready, start, first));
            Future<T> secondResult = executor.submit(() -> executeAfterStart(ready, start, second));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private <T> T executeAfterStart(CountDownLatch ready, CountDownLatch start, Supplier<T> operation) {
        ready.countDown();
        awaitStart(start);
        return operation.get();
    }

    private <T> T executeInNewTransaction(CurrentUser user, Supplier<T> operation) {
        return executeInNewTransaction(user, new CountDownLatch(0), new CountDownLatch(0), operation);
    }

    private <T> T executeInNewTransaction(CurrentUser user, CountDownLatch ready,
                                           CountDownLatch start, Supplier<T> operation) {
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(user, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return transaction.execute(status -> {
                ready.countDown();
                awaitStart(start);
                return operation.get();
            });
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void awaitStart(CountDownLatch start) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("community service concurrency start timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("community service concurrency interrupted", exception);
        }
    }

    private InsertOutcome insertPostAfterStart(CountDownLatch start, PostInsert post) {
        try {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO community_post(
                         post_no, group_id, author_user_id, title, content_text, status,
                         idempotency_key, request_hash, published_at
                     ) VALUES (?, ?, ?, ?, ?, 'PUBLISHED', ?, ?, NOW())
                     """)) {
                statement.setString(1, post.postNo());
                statement.setLong(2, post.groupId());
                statement.setLong(3, post.authorId());
                statement.setString(4, post.title());
                statement.setString(5, post.content());
                statement.setString(6, post.idempotencyKey());
                statement.setString(7, post.requestHash());
                statement.executeUpdate();
                return InsertOutcome.succeeded();
            }
        } catch (SQLException exception) {
            return InsertOutcome.rejected(exception.getSQLState(), exception.getErrorCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("community idempotency race interrupted", exception);
        }
    }

    private void insertCategorizedGroup(long groupId, long ownerId, String visibility,
                                        String suffix, String categoryCode) {
        jdbcTemplate.update("""
            INSERT INTO community_group(
                id, group_no, name, description, owner_user_id, visibility, join_policy,
                category_code, status, idempotency_key, request_hash
            ) VALUES (?, ?, ?, ?, ?, ?, 'DIRECT', ?, 'ACTIVE', ?, ?)
            """, groupId, "CG-" + suffix, "小组-" + suffix, "小组说明", ownerId, visibility,
            categoryCode, "group-key-" + suffix, "group-hash-" + suffix);
    }

    private void insertGroupWithOwner(long groupId, long ownerId, String visibility, String suffix) {
        insertGroup(groupId, ownerId, visibility, suffix);
        insertMember(groupId, ownerId, "OWNER", "ACTIVE");
    }

    private void insertGroup(long groupId, long ownerId, String visibility, String suffix) {
        jdbcTemplate.update("""
            INSERT INTO community_group(
                id, group_no, name, description, owner_user_id, visibility, join_policy,
                status, idempotency_key, request_hash
            ) VALUES (?, ?, ?, ?, ?, ?, 'DIRECT', 'ACTIVE', ?, ?)
            """, groupId, "CG-" + suffix, "小组-" + suffix, "小组说明", ownerId, visibility,
            "group-key-" + suffix, "group-hash-" + suffix);
    }

    private void insertHiddenGroup(long groupId, long ownerId, LocalDateTime retainUntil, String suffix) {
        LocalDateTime hiddenAt = retainUntil.minusDays(180);
        jdbcTemplate.update("""
            INSERT INTO community_group(
                id, group_no, name, description, owner_user_id, visibility, join_policy,
                status, idempotency_key, request_hash, hidden_at, deleted_at, retain_until
            ) VALUES (?, ?, ?, '小组说明', ?, 'PLATFORM_VISIBLE', 'DIRECT', 'HIDDEN', ?, ?, ?, ?, ?)
            """, groupId, "CG-" + suffix, "小组-" + suffix, ownerId,
            "group-key-" + suffix, "group-hash-" + suffix, hiddenAt, hiddenAt, retainUntil);
    }

    private void insertMember(long groupId, long userId, String role, String status) {
        jdbcTemplate.update("""
            INSERT INTO community_group_member(
                group_id, user_id, member_role, status, joined_at
            ) VALUES (?, ?, ?, ?, NOW())
            """, groupId, userId, role, status);
    }

    private void insertPost(long postId, long groupId, long authorId, String title, String suffix) {
        jdbcTemplate.update("""
            INSERT INTO community_post(
                id, post_no, group_id, author_user_id, title, content_text, status,
                idempotency_key, request_hash, published_at
            ) VALUES (?, ?, ?, ?, ?, '帖子正文', 'PUBLISHED', ?, ?, NOW())
            """, postId, "CP-" + suffix, groupId, authorId, title,
            "post-key-" + suffix, "post-hash-" + suffix);
    }

    private void insertHiddenPost(long postId, long groupId, long authorId,
                                  LocalDateTime retainUntil, String suffix) {
        LocalDateTime hiddenAt = retainUntil.minusDays(180);
        jdbcTemplate.update("""
            INSERT INTO community_post(
                id, post_no, group_id, author_user_id, title, content_text, status,
                idempotency_key, request_hash, published_at, hidden_by, hidden_scope,
                hidden_reason_code, hidden_at, deleted_at, retain_until
            ) VALUES (?, ?, ?, ?, '待恢复帖子', '待恢复正文', 'HIDDEN', ?, ?, ?, ?,
                      'PLATFORM', 'POLICY', ?, ?, ?)
            """, postId, "CP-" + suffix, groupId, authorId,
            "post-key-" + suffix, "post-hash-" + suffix, hiddenAt, 8_299_001L,
            hiddenAt, hiddenAt, retainUntil);
    }

    private void insertComment(long commentId, long postId, long groupId, long authorId,
                               String content, String suffix) {
        jdbcTemplate.update("""
            INSERT INTO community_comment(
                id, comment_no, post_id, group_id, author_user_id, content_text, status,
                idempotency_key, request_hash, published_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'PUBLISHED', ?, ?, NOW())
            """, commentId, "CC-" + suffix, postId, groupId, authorId, content,
            "comment-key-" + suffix, "comment-hash-" + suffix);
    }

    private void insertHiddenComment(long commentId, long postId, long groupId, long authorId,
                                     LocalDateTime retainUntil, String suffix) {
        LocalDateTime hiddenAt = retainUntil.minusDays(180);
        jdbcTemplate.update("""
            INSERT INTO community_comment(
                id, comment_no, post_id, group_id, author_user_id, content_text, status,
                idempotency_key, request_hash, published_at, hidden_by, hidden_scope,
                hidden_reason_code, hidden_at, deleted_at, retain_until
            ) VALUES (?, ?, ?, ?, ?, '待恢复评论', 'HIDDEN', ?, ?, ?, ?, 'GROUP', 'POLICY', ?, ?, ?)
            """, commentId, "CC-" + suffix, postId, groupId, authorId,
            "comment-key-" + suffix, "comment-hash-" + suffix, hiddenAt, 8_299_002L,
            hiddenAt, hiddenAt, retainUntil);
    }

    private void insertBlock(long blockerId, long blockedId, String suffix) {
        jdbcTemplate.update("""
            INSERT INTO community_user_block(
                blocker_user_id, blocked_user_id, status, last_idempotency_key, created_at
            ) VALUES (?, ?, 'ACTIVE', ?, NOW())
            """, blockerId, blockedId, "block-key-" + suffix);
    }

    private void insertDeletedPost(long postId, long groupId, LocalDateTime deletedAt,
                                   LocalDateTime retainUntil, String suffix) {
        jdbcTemplate.update("""
            INSERT INTO community_post(
                id, post_no, group_id, author_user_id, title, content_text, status,
                idempotency_key, request_hash, published_at, deleted_by, deletion_reason,
                deleted_at, retain_until
            ) VALUES (?, ?, ?, ?, '待清理帖子', '待清理正文', 'DELETED', ?, ?, ?, ?,
                      'USER_REQUEST', ?, ?)
            """, postId, "CP-" + suffix, groupId, 8_259_001L,
            "post-key-" + suffix, "post-hash-" + suffix, deletedAt, 8_259_001L, deletedAt, retainUntil);
    }

    private void insertClosedReport(long reportId, long targetId, LocalDateTime closedAt,
                                    LocalDateTime reasonRetainUntil, LocalDateTime minimalRetainUntil,
                                    String suffix) {
        jdbcTemplate.update("""
            INSERT INTO community_report(
                id, report_no, reporter_user_id, target_type, target_id, reason_text, status,
                idempotency_key, request_hash, decision_code, closed_at, reason_retain_until,
                minimal_retain_until, create_time
            ) VALUES (?, ?, ?, 'POST', ?, '举报原因', 'RESOLVED', ?, ?, 'NO_ACTION', ?, ?, ?, ?)
            """, reportId, "CR-" + suffix, 8_259_101L, targetId,
            "report-key-" + suffix, "report-hash-" + suffix,
            closedAt, reasonRetainUntil, minimalRetainUntil, RETENTION_BASE);
    }

    private void insertOpenReport(long reportId, long targetId, LocalDateTime minimalRetainUntil,
                                  String suffix) {
        jdbcTemplate.update("""
            INSERT INTO community_report(
                id, report_no, reporter_user_id, target_type, target_id, reason_text, status,
                idempotency_key, request_hash, minimal_retain_until, create_time
            ) VALUES (?, ?, ?, 'POST', ?, '举报原因', 'OPEN', ?, ?, ?, ?)
            """, reportId, "CR-" + suffix, 8_259_102L, targetId,
            "report-key-" + suffix, "report-hash-" + suffix, minimalRetainUntil, RETENTION_BASE);
    }

    private void insertGovernanceEvent(long eventId, long subjectId, LocalDateTime retainUntil, String suffix) {
        jdbcTemplate.update("""
            INSERT INTO community_governance_event(
                id, event_no, event_type, subject_type, subject_id, actor_user_id, actor_scope,
                source_type, result_status, idempotency_key, metadata_json, occurred_at, retain_until
            ) VALUES (?, ?, 'CONTENT_REVIEWED', 'POST', ?, ?, 'CONTENT', 'CONTENT_OPERATOR',
                      'SUCCEEDED', ?, JSON_OBJECT(), ?, ?)
            """, eventId, "CE-" + suffix, subjectId, 8_259_201L,
            "event-key-" + suffix, RETENTION_BASE, retainUntil);
    }

    private ScaleFixture insertScaleFixture(int rowCount) {
        long groupBase = 8_300_000L;
        long postBase = 8_400_000L;
        long commentBase = 8_500_000L;
        long reportBase = 8_600_000L;
        LocalDateTime now = LocalDateTime.of(2026, 7, 1, 12, 0);

        List<Object[]> groups = new ArrayList<>(rowCount);
        List<Long> activeGroupIds = new ArrayList<>();
        for (int index = 1; index <= rowCount; index++) {
            long groupId = groupBase + index;
            boolean active = index % 10 == 1;
            if (active) {
                activeGroupIds.add(groupId);
            }
            groups.add(new Object[]{
                groupId, "CG-scale-" + index, "规模小组-" + index, "规模说明", 9_000_000L + index,
                "PLATFORM_VISIBLE", "DIRECT", active ? "ACTIVE" : "HIDDEN",
                "group-scale-key-" + index, "group-scale-hash-" + index, active ? null : now,
                active ? null : now, active ? null : now.plusDays(180)
            });
        }
        jdbcTemplate.batchUpdate("""
            INSERT INTO community_group(
                id, group_no, name, description, owner_user_id, visibility, join_policy, status,
                idempotency_key, request_hash, hidden_at, deleted_at, retain_until
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, groups);

        List<Object[]> posts = new ArrayList<>(rowCount);
        List<Long> postGroups = new ArrayList<>(rowCount);
        for (int index = 1; index <= rowCount; index++) {
            long groupId = activeGroupIds.get((index - 1) % activeGroupIds.size());
            postGroups.add(groupId);
            posts.add(new Object[]{
                postBase + index, "CP-scale-" + index, groupId, 9_100_000L + index,
                "规模帖子-" + index, "规模帖子正文", "post-scale-key-" + index,
                "post-scale-hash-" + index, now
            });
        }
        jdbcTemplate.batchUpdate("""
            INSERT INTO community_post(
                id, post_no, group_id, author_user_id, title, content_text, status,
                idempotency_key, request_hash, published_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'PUBLISHED', ?, ?, ?)
            """, posts);

        List<Object[]> comments = new ArrayList<>(rowCount * 2);
        for (int index = 1; index <= rowCount * 2; index++) {
            int postOffset = (index - 1) % rowCount;
            comments.add(new Object[]{
                commentBase + index, "CC-scale-" + index, postBase + postOffset + 1,
                postGroups.get(postOffset), 9_200_000L + index, "规模评论", "comment-scale-key-" + index,
                "comment-scale-hash-" + index, now
            });
        }
        jdbcTemplate.batchUpdate("""
            INSERT INTO community_comment(
                id, comment_no, post_id, group_id, author_user_id, content_text, status,
                idempotency_key, request_hash, published_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'PUBLISHED', ?, ?, ?)
            """, comments);

        List<Object[]> reports = new ArrayList<>(rowCount);
        for (int index = 1; index <= rowCount; index++) {
            reports.add(new Object[]{
                reportBase + index, "CR-scale-" + index, 9_300_000L + index, postBase + index,
                "规模举报原因", index % 10 == 1 ? "OPEN" : "IN_REVIEW",
                "report-scale-key-" + index, "report-scale-hash-" + index,
                now.plusDays(365), now
            });
        }
        jdbcTemplate.batchUpdate("""
            INSERT INTO community_report(
                id, report_no, reporter_user_id, target_type, target_id, reason_text, status,
                idempotency_key, request_hash, minimal_retain_until, create_time
            ) VALUES (?, ?, ?, 'POST', ?, ?, ?, ?, ?, ?, ?)
            """, reports);

        return new ScaleFixture(activeGroupIds.get(0), postBase + 1, reportBase + rowCount + 1);
    }

    private void assertIndexedAccess(List<Map<String, Object>> plan, String tableAlias, String... expectedIndexes) {
        Map<String, Object> target = plan.stream()
            .filter(row -> tableAlias.equals(String.valueOf(row.get("table"))))
            .findFirst()
            .orElseThrow(() -> new AssertionError("EXPLAIN did not contain table alias " + tableAlias + ": " + plan));
        assertThat(String.valueOf(target.get("type"))).isNotEqualToIgnoringCase("ALL");
        assertThat(String.valueOf(target.get("key"))).isNotBlank().isNotEqualTo("null");
        String possibleIndexes = String.valueOf(target.get("possible_keys"));
        assertThat(List.of(expectedIndexes).stream().anyMatch(possibleIndexes::contains))
            .as("EXPLAIN should expose the matching V15 index for alias %s: %s", tableAlias, plan)
            .isTrue();
    }

    private void assertConstraintViolation(ThrowingCallable operation, int expectedErrorCode) {
        assertThatThrownBy(operation).satisfies(error -> {
            Throwable rootCause = error;
            while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
            }
            assertThat(rootCause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) rootCause).getErrorCode()).isEqualTo(expectedErrorCode);
        });
    }

    private List<String> queryStrings(String sql) {
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> resultSet.getString(1));
    }

    private record PostInsert(String postNo, long groupId, long authorId, String title, String content,
                              String idempotencyKey, String requestHash) {
    }

    private record InsertOutcome(boolean success, String sqlState, int errorCode) {
        static InsertOutcome succeeded() {
            return new InsertOutcome(true, null, 0);
        }

        static InsertOutcome rejected(String sqlState, int errorCode) {
            return new InsertOutcome(false, sqlState, errorCode);
        }
    }

    private record ScaleFixture(long targetGroupId, long targetPostId, long cursorId) {
    }

    private record CommandOutcome(boolean succeeded, ResultCode code) {
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan({"com.example.venue.community.mapper", "com.example.venue.system.mapper"})
    static class IntegrationApplication {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        CommunityAccessService communityAccessService(CommunityGroupMapper groupMapper,
                                                      CommunityGroupMemberMapper memberMapper,
                                                      CommunityPostMapper postMapper,
                                                      CommunityCommentMapper commentMapper,
                                                      CommunityUserBlockMapper blockMapper) {
            return new CommunityAccessService(groupMapper, memberMapper, postMapper, commentMapper, blockMapper);
        }

        @Bean
        CommunityGovernanceEventService communityGovernanceEventService(
            CommunityGovernanceEventMapper eventMapper, ObjectMapper objectMapper) {
            return new CommunityGovernanceEventService(eventMapper, objectMapper);
        }

        @Bean
        CommunityTextPolicy communityTextPolicy() {
            return new CommunityTextPolicy();
        }

        @Bean
        CommunityRateLimitService communityRateLimitService() {
            return mock(CommunityRateLimitService.class);
        }

        @Bean
        SysUserMapper sysUserMapper() {
            return mock(SysUserMapper.class);
        }

        @Bean
        CommunityContentService communityContentService(
            CommunityPostMapper postMapper,
            CommunityCommentMapper commentMapper,
            CommunityReportMapper reportMapper,
            CommunityUserBlockMapper blockMapper,
            SysUserMapper userMapper,
            CommunityAccessService accessService,
            CommunityTextPolicy textPolicy,
            CommunityRateLimitService rateLimitService,
            CommunityGovernanceEventService eventService) {
            return new CommunityContentService(
                postMapper, commentMapper, reportMapper, blockMapper, userMapper,
                accessService, textPolicy, rateLimitService, eventService);
        }

        @Bean
        CommunityGroupService communityGroupService(
            CommunityGroupMapper groupMapper,
            CommunityGroupMemberMapper memberMapper,
            SysDictDataMapper dictDataMapper,
            CommunityAccessService accessService,
            CommunityTextPolicy textPolicy,
            CommunityRateLimitService rateLimitService,
            CommunityGovernanceEventService eventService) {
            return new CommunityGroupService(
                groupMapper, memberMapper, dictDataMapper, accessService, textPolicy, rateLimitService, eventService);
        }

        @Bean
        CommunityGovernanceService communityGovernanceService(
            CommunityReportMapper reportMapper,
            CommunityGovernanceEventMapper eventMapper,
            CommunityGroupMapper groupMapper,
            CommunityPostMapper postMapper,
            CommunityCommentMapper commentMapper,
            CommunityContentService contentService,
            CommunityAccessService accessService,
            CommunityTextPolicy textPolicy,
            CommunityGovernanceEventService eventService) {
            return new CommunityGovernanceService(
                reportMapper, eventMapper, groupMapper, postMapper, commentMapper,
                contentService, accessService, textPolicy, eventService);
        }

        @Bean
        CommunityRetentionProperties communityRetentionProperties() {
            CommunityRetentionProperties properties = new CommunityRetentionProperties();
            properties.setBatchSize(100);
            properties.setMaxBatchesPerRun(10);
            return properties;
        }

        @Bean
        SysDeletedUserSnapshotMapper deletedUserSnapshotMapper() {
            return mock(SysDeletedUserSnapshotMapper.class);
        }

        @Bean
        CommunityRetentionService communityRetentionService(
            CommunityRetentionProperties properties,
            CommunityRetentionMapper retentionMapper,
            SysDeletedUserSnapshotMapper deletedUserSnapshotMapper,
            CommunityGovernanceEventService eventService,
            PlatformTransactionManager transactionManager) {
            return new CommunityRetentionService(
                properties, retentionMapper, deletedUserSnapshotMapper, eventService, transactionManager);
        }
    }
}
