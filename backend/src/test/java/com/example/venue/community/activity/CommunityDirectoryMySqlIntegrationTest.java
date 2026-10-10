package com.example.venue.community.activity;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.community.activity.dto.ActivityRequests;
import com.example.venue.community.activity.dto.ActivityResponses;
import com.example.venue.community.activity.mapper.CommunityDirectoryMapper;
import com.example.venue.community.activity.service.CommunityDirectoryService;
import com.example.venue.community.mapper.CommunityCommentMapper;
import com.example.venue.community.mapper.CommunityGroupMapper;
import com.example.venue.community.mapper.CommunityGroupMemberMapper;
import com.example.venue.community.mapper.CommunityPostMapper;
import com.example.venue.community.mapper.CommunityUserBlockMapper;
import com.example.venue.community.service.CommunityAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V02 日期目录合同测试：全局时间序、类型化 ID、计数口径、游标绑定筛选、可见性与本人发起读取。
 */
// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(
    classes = CommunityDirectoryMySqlIntegrationTest.IntegrationApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.task.scheduling.enabled=false"
)
class CommunityDirectoryMySqlIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(13_000_000L);
    private static final ZoneId FIXTURE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime FUTURE_START =
        LocalDate.now(FIXTURE_ZONE).plusDays(7).atTime(10, 0);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("community_directory")
        .withUsername("directory")
        .withPassword("directory")
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
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CommunityDirectoryService directoryService;

    private long viewerId;
    private long groupId;
    private long membersOnlyGroupId;

    @BeforeEach
    void cleanFixtures() {
        SecurityContextHolder.clearContext();
        grantSystemVariablesAdminOnce();
        jdbcTemplate.update("DELETE FROM community_match_decision");
        jdbcTemplate.update("DELETE FROM community_activity_participation");
        jdbcTemplate.update("DELETE FROM community_activity_application");
        jdbcTemplate.update("DELETE FROM community_activity_command");
        jdbcTemplate.update("DELETE FROM community_companion_request");
        jdbcTemplate.update("DELETE FROM community_activity");
        jdbcTemplate.update("DELETE FROM community_post");
        jdbcTemplate.update("DELETE FROM community_group_member");
        jdbcTemplate.update("DELETE FROM community_user_block");
        jdbcTemplate.update("DELETE FROM community_group");
        viewerId = nextId();
        groupId = insertGroup(nextId(), "PLATFORM_VISIBLE", "公共小组");
        membersOnlyGroupId = insertGroup(nextId(), "MEMBERS_ONLY", "内部小组");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void directoryOrdersMixedTypesByStartTimeStablyAcrossPages() {
        List<long[]> inserted = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            boolean activity = i % 2 == 0;
            LocalDateTime start = FUTURE_START.plusHours(i);
            long id = nextId();
            inserted.add(new long[]{id, i});
            if (activity) {
                insertActivityRow(id, groupId, start, "PUBLISHED", 8, 3, "活动-" + i);
            } else {
                insertCompanionRow(id, groupId, start, "PUBLISHED", 4, 1, "搭子-" + i);
            }
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 3; page++) {
            ActivityRequests.DirectoryQuery query = new ActivityRequests.DirectoryQuery();
            query.limit = 5;
            query.cursor = cursor;
            ActivityResponses.DirectoryPage result = runAsViewer(() -> directoryService.directory(query));
            assertThat(result.records()).hasSize(page == 2 ? 2 : 5);
            for (ActivityResponses.DirectoryItem item : result.records()) {
                seen.add(item.sourceType + ":" + item.sourceId);
            }
            cursor = result.nextCursor();
        }
        assertThat(cursor).isNull();
        assertThat(seen).hasSize(12).doesNotHaveDuplicates();

        List<String> expected = inserted.stream()
            .map(pair -> (pair[1] % 2 == 0 ? "ACTIVITY:" : "COMPANION:") + pair[0])
            .toList();
        assertThat(seen).isEqualTo(expected);
    }

    @Test
    void sameStartTimeWithSameNumericIdKeepsBothTypedEntries() {
        long sharedId = nextId();
        insertActivityRow(sharedId, groupId, FUTURE_START, "PUBLISHED", 8, 3, "同号活动");
        insertCompanionRow(sharedId, groupId, FUTURE_START, "PUBLISHED", 4, 1, "同号搭子");

        ActivityResponses.DirectoryPage page = runAsViewer(() -> directoryService.directory(query(null, 10)));
        assertThat(page.records()).extracting(item -> item.sourceType + ":" + item.sourceId)
            .containsExactly("ACTIVITY:" + sharedId, "COMPANION:" + sharedId);
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void typeCountsAndTotalAndDateCountsUseTheSameNonTypeFilters() {
        insertActivityRow(nextId(), groupId, FUTURE_START, "PUBLISHED", 8, 3, "活动一");
        insertActivityRow(nextId(), groupId, FUTURE_START.plusHours(1), "PUBLISHED", 8, 3, "活动二");
        insertActivityRow(nextId(), groupId, FUTURE_START.plusDays(1), "FULL", 8, 8, "活动三");
        insertCompanionRow(nextId(), groupId, FUTURE_START, "PUBLISHED", 4, 1, "搭子一");

        ActivityRequests.DirectoryQuery companionOnly = query(null, 10);
        companionOnly.types = "COMPANION";
        ActivityResponses.DirectoryPage page = runAsViewer(() -> directoryService.directory(companionOnly));

        // 类型筛选只收窄记录，不收窄计数口径（tab 数不随当前 tab 缩窄）。
        assertThat(page.records()).allSatisfy(item -> assertThat(item.sourceType).isEqualTo("COMPANION"));
        assertThat(page.typeCounts()).containsEntry("ACTIVITY", 3L).containsEntry("COMPANION", 1L);
        assertThat(page.total()).isEqualTo(4);
        assertThat(page.dateCounts()).hasSize(2);
        long dateTotal = page.dateCounts().stream().mapToLong(ActivityResponses.DateCount::count).sum();
        assertThat(dateTotal).isEqualTo(4);
    }

    @Test
    void keywordMatchesGroupNamesAndOnlyAvailableExcludesFullRows() {
        insertActivityRow(nextId(), groupId, FUTURE_START, "PUBLISHED", 8, 3, "普通活动");
        insertActivityRow(nextId(), groupId, FUTURE_START.plusHours(1), "FULL", 8, 8, "满员活动");
        insertCompanionRow(nextId(), groupId, FUTURE_START.plusHours(2), "FULL", 4, 4, "满员搭子");

        ActivityRequests.DirectoryQuery byGroup = query("公共小组", 10);
        assertThat(runAsViewer(() -> directoryService.directory(byGroup)).records())
            .hasSize(2)
            .allSatisfy(item -> assertThat(item.groupName).isEqualTo("公共小组"));

        ActivityRequests.DirectoryQuery available = query(null, 10);
        available.onlyAvailable = true;
        ActivityResponses.DirectoryPage availablePage =
            runAsViewer(() -> directoryService.directory(available));
        assertThat(availablePage.records()).hasSize(1);
        assertThat(availablePage.records().get(0).title).isEqualTo("普通活动");
        assertThat(availablePage.total()).isEqualTo(1);
    }

    @Test
    void cursorIsBoundToFiltersAndMidStreamInsertsDoNotDuplicate() {
        long firstId = nextId();
        long secondId = nextId();
        insertActivityRow(firstId, groupId, FUTURE_START, "PUBLISHED", 8, 3, "第一场");
        insertActivityRow(secondId, groupId, FUTURE_START.plusHours(1), "PUBLISHED", 8, 3, "第二场");

        ActivityRequests.DirectoryQuery firstPage = query(null, 1);
        ActivityResponses.DirectoryPage page = runAsViewer(() -> directoryService.directory(firstPage));
        assertThat(page.records()).extracting(item -> item.sourceId).containsExactly(firstId);

        // 翻页前插入更早的新条目：游标页不回填，也不重复。
        insertActivityRow(nextId(), groupId, FUTURE_START.minusHours(1), "PUBLISHED", 8, 1, "中途插入");

        ActivityRequests.DirectoryQuery secondPage = query(null, 1);
        secondPage.cursor = page.nextCursor();
        ActivityResponses.DirectoryPage next = runAsViewer(() -> directoryService.directory(secondPage));
        assertThat(next.records()).extracting(item -> item.sourceId).containsExactly(secondId);

        // 同一游标换筛选被拒绝。
        ActivityRequests.DirectoryQuery changed = query("第二场", 1);
        changed.cursor = page.nextCursor();
        assertThatThrownBy(() -> runAsViewer(() -> directoryService.directory(changed)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("筛选条件已变化");
    }

    @Test
    void visibilityAndBlocksRestrictDirectoryRows() {
        long hiddenGroupId = insertGroup(nextId(), "MEMBERS_ONLY", "内部小组");
        long blockedOrganizer = nextId();
        insertActivityRow(nextId(), membersOnlyGroupId, FUTURE_START, "PUBLISHED", 8, 2, "内部活动");
        insertActivityRow(nextId(), groupId, FUTURE_START.plusHours(1), "PUBLISHED", 8, 2, "公开活动");
        insertActivityRow(nextId(), groupId, FUTURE_START.plusHours(2), "PUBLISHED", 8, 2, "被屏蔽活动",
            blockedOrganizer);
        jdbcTemplate.update("""
            INSERT INTO community_user_block(
                blocker_user_id, blocked_user_id, status, last_idempotency_key, created_at
            ) VALUES (?, ?, 'ACTIVE', ?, NOW())
            """, viewerId, blockedOrganizer, "block-key-" + nextId());

        ActivityResponses.DirectoryPage page = runAsViewer(() -> directoryService.directory(query(null, 10)));
        assertThat(page.records()).extracting(item -> item.title)
            .containsExactly("公开活动")
            .doesNotContain("内部活动", "被屏蔽活动");

        // 成员可以看到内部小组的活动。
        long memberId = nextId();
        insertMember(membersOnlyGroupId, memberId);
        runAs(memberId, () -> {
            ActivityResponses.DirectoryPage memberPage =
                directoryService.directory(query(null, 10));
            assertThat(memberPage.records()).extracting(item -> item.title).contains("内部活动");
            return memberPage;
        });
    }

    @Test
    void fullCompanionStaysOutOfPublicDirectory() {
        insertCompanionRow(nextId(), groupId, FUTURE_START, "FULL", 4, 4, "满员搭子");
        insertActivityRow(nextId(), groupId, FUTURE_START.plusHours(1), "PUBLISHED", 8, 3, "普通活动");

        ActivityResponses.DirectoryPage page = runAsViewer(() -> directoryService.directory(query(null, 10)));
        assertThat(page.records()).extracting(item -> item.sourceType).containsExactly("ACTIVITY");
        assertThat(page.typeCounts()).containsEntry("COMPANION", 0L);
    }

    @Test
    void directoryPageRequestStaysWithinBoundedStatements() {
        insertActivityRow(nextId(), groupId, FUTURE_START, "PUBLISHED", 8, 3, "活动一");
        insertCompanionRow(nextId(), groupId, FUTURE_START.plusHours(1), "PUBLISHED", 4, 1, "搭子一");

        jdbcTemplate.execute("SET GLOBAL log_output = 'TABLE'");
        String marker = jdbcTemplate.queryForObject(
            "SELECT CAST(NOW(6) AS DATETIME(6))", String.class);
        jdbcTemplate.execute("SET GLOBAL general_log = 'ON'");
        try {
            runAsViewer(() -> directoryService.directory(query(null, 50)));
        } finally {
            jdbcTemplate.execute("SET GLOBAL general_log = 'OFF'");
        }
        // 业务语句 = 目录页 1 + 类型计数 1 + 日期计数 1 + 小组/用户批量补名 2 + 本人申请/参与 2×类型数；
        // general_log 的 argument 是 BLOB；驱动版本会改变控制语句大小写，显式归一化后排除，业务上界保持 9。
        Integer statements = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mysql.general_log "
                + "WHERE command_type = 'Query' AND event_time >= '" + marker + "' "
                + "AND LOWER(CONVERT(argument USING utf8mb4)) NOT LIKE 'set session%' "
                + "AND LOWER(CONVERT(argument USING utf8mb4)) NOT LIKE 'set autocommit%' "
                + "AND LOWER(CONVERT(argument USING utf8mb4)) NOT LIKE 'commit%' "
                + "AND LOWER(CONVERT(argument USING utf8mb4)) NOT LIKE 'set global%'",
            Integer.class);
        assertThat(statements).as("一次目录请求的业务语句数").isBetween(5, 9);

        String pageSql = jdbcTemplate.queryForObject(
            "SELECT argument FROM mysql.general_log "
                + "WHERE command_type in ('Query', 'Execute') AND argument LIKE '%sort_start asc%' "
                + "LIMIT 1", String.class);
        List<Map<String, Object>> plan = jdbcTemplate.queryForList("EXPLAIN FORMAT=TREE " + pageSql);
        String planText = plan.toString();
        System.out.println("DIRECTORY_PAGE_EXPLAIN " + planText);
        assertThat(planText).contains("->");
    }

    @Test
    void myHostedListsDraftsAndOwnedCompanionsWithBoundedPaging() {
        long draftActivity = nextId();
        insertActivityRow(draftActivity, groupId, FUTURE_START, "DRAFT", 8, 1, "草稿活动", viewerId);
        long ownedCompanion = nextId();
        insertCompanionRow(ownedCompanion, groupId, FUTURE_START.plusHours(1), "PUBLISHED", 4, 1, "我的搭子",
            viewerId);
        insertActivityRow(nextId(), groupId, FUTURE_START.plusHours(2), "PUBLISHED", 8, 2, "别人的活动",
            nextId());

        ActivityResponses.DirectoryPage first = runAsViewer(() -> directoryService.myHosted(null, 1));
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.records()).hasSize(1);
        String firstId = first.records().get(0).sourceType + ":" + first.records().get(0).sourceId;

        ActivityResponses.DirectoryPage second = runAsViewer(
            () -> directoryService.myHosted(first.nextCursor(), 1));
        String secondId = second.records().get(0).sourceType + ":" + second.records().get(0).sourceId;

        assertThat(Set.of(firstId, secondId)).containsExactlyInAnyOrder(
            "ACTIVITY:" + draftActivity, "COMPANION:" + ownedCompanion);
    }

    private static boolean systemVariablesAdminGranted = false;

    /** general_log 语句数证据需要 SET GLOBAL；容器 root 密码与业务用户相同（Testcontainers 约定）。 */
    private void grantSystemVariablesAdminOnce() {
        if (systemVariablesAdminGranted) {
            return;
        }
        try (var connection = java.sql.DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("GRANT SYSTEM_VARIABLES_ADMIN, RELOAD ON *.* TO 'directory'@'%'");
            statement.execute("GRANT SELECT ON mysql.general_log TO 'directory'@'%'");
            systemVariablesAdminGranted = true;
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("无法授予 general_log 证据所需权限", exception);
        }
    }

    private ActivityRequests.DirectoryQuery query(String keyword, int limit) {
        ActivityRequests.DirectoryQuery query = new ActivityRequests.DirectoryQuery();
        query.keyword = keyword;
        query.limit = limit;
        return query;
    }

    private void insertActivityRow(long id, long groupId, LocalDateTime start, String status,
                                   int capacity, int count, String title) {
        insertActivityRow(id, groupId, start, status, capacity, count, title, nextId());
    }

    private void insertActivityRow(long id, long groupId, LocalDateTime start, String status,
                                   int capacity, int count, String title, long organizerId) {
        jdbcTemplate.update("""
            INSERT INTO community_activity(
                id, activity_no, group_id, organizer_user_id, activity_type, title, description,
                start_time, end_time, location_type, area_code, location_commitment_status,
                commitment_version, capacity_total, participant_count, status, version_no, published_at
            ) VALUES (?, ?, ?, ?, 'SPORTS', ?, '目录合同数据', ?, ?, 'CAMPUS_AREA', 'NORTH_CAMPUS',
                      'UNCONFIRMED', 0, ?, ?, ?, 1, NOW())
            """, id, "ACT-DIR-" + id, groupId, organizerId, title, start, start.plusHours(2),
            capacity, count, status);
    }

    private void insertCompanionRow(long id, long groupId, LocalDateTime start, String status,
                                    int capacity, int count, String title) {
        insertCompanionRow(id, groupId, start, status, capacity, count, title, nextId());
    }

    private void insertCompanionRow(long id, long groupId, LocalDateTime start, String status,
                                    int capacity, int count, String title, long ownerId) {
        jdbcTemplate.update("""
            INSERT INTO community_companion_request(
                id, request_no, group_id, owner_user_id, activity_type, title, description,
                window_start, window_end, location_type, area_code, location_commitment_status,
                commitment_version, capacity_total, participant_count, status, version_no, published_at
            ) VALUES (?, ?, ?, ?, 'SPORTS', ?, '目录合同数据', ?, ?, 'CAMPUS_AREA', 'NORTH_CAMPUS',
                      'UNCONFIRMED', 0, ?, ?, ?, 1, NOW())
            """, id, "CPR-DIR-" + id, groupId, ownerId, title, start, start.plusHours(2),
            capacity, count, status);
    }

    private long insertGroup(long ownerId, String visibility, String name) {
        long groupId = nextId();
        jdbcTemplate.update("""
            INSERT INTO community_group(
                id, group_no, name, description, owner_user_id, visibility, join_policy,
                status, idempotency_key, request_hash
            ) VALUES (?, ?, ?, '目录合同小组', ?, ?, 'DIRECT', 'ACTIVE', ?, ?)
            """, groupId, "CG-DIR-" + groupId, name, ownerId, visibility,
            "group-key-" + groupId, "group-hash-" + groupId);
        return groupId;
    }

    private void insertMember(long groupId, long userId) {
        jdbcTemplate.update("""
            INSERT INTO community_group_member(group_id, user_id, member_role, status, joined_at)
            VALUES (?, ?, 'MEMBER', 'ACTIVE', NOW())
            """, groupId, userId);
    }

    private <T> T runAsViewer(Supplier<T> operation) {
        return runAs(viewerId, operation);
    }

    private <T> T runAs(long userId, Supplier<T> operation) {
        CurrentUser user = new CurrentUser(userId, "user-" + userId, Set.of(), Set.of("community:access"));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
            return operation.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private long nextId() {
        return IDS.incrementAndGet();
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan({
        "com.example.venue.community.mapper",
        "com.example.venue.community.activity.mapper",
        "com.example.venue.user.mapper",
        "com.example.venue.venue.mapper"
    })
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
        CommunityDirectoryService communityDirectoryService(
            CommunityDirectoryMapper directoryMapper,
            com.example.venue.community.activity.mapper.CommunityActivityApplicationMapper applicationMapper,
            com.example.venue.community.activity.mapper.CommunityActivityParticipationMapper participationMapper,
            CommunityGroupMapper groupMapper,
            com.example.venue.user.mapper.SysUserMapper userMapper,
            com.example.venue.venue.mapper.VenueMapper venueMapper,
            com.example.venue.venue.mapper.VenueSpaceMapper spaceMapper,
            CommunityAccessService accessService,
            ObjectMapper objectMapper) {
            return new CommunityDirectoryService(directoryMapper, applicationMapper, participationMapper,
                groupMapper, userMapper, venueMapper, spaceMapper, accessService, objectMapper);
        }
    }
}
