package com.example.venue.placement.service;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.common.CurrentUser;
import com.example.venue.placement.dto.PlacementRequests;
import com.example.venue.placement.dto.PlacementResponses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "app.provider.observability.scheduled-enabled=false",
    "app.community.retention.enabled=false",
    "spring.task.scheduling.enabled=false",
    "spring.data.redis.timeout=200ms",
    "spring.data.redis.connect-timeout=200ms",
    "logging.level.com.example.venue=ERROR"
})
class PlacementDecisionMySqlRedisIntegrationTest {
    private static final long NOTICE_ID = 990001L;
    private static final long CONCURRENCY_CANDIDATE_ID = 990010L;
    private static final long FREQUENCY_CANDIDATE_ID = 990020L;
    private static final long USER_ID = 990001L;
    private static final long SCALE_CANDIDATE_BASE = 991000L;
    private static final long SCALE_EVENT_BASE = 992000L;
    private static final String SCALE_CANDIDATE_PREFIX = "S09-SCALE-CAND-";
    private static final String SCALE_EVENT_PREFIX = "S09-SCALE-EVENT-";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("placement_decision_integration")
        .withUsername("placement")
        .withPassword("placement")
        .withEnv("TZ", "Asia/Shanghai")
        .withCommand("--character-set-server=utf8mb4",
                     "--collation-server=utf8mb4_unicode_ci",
                     "--log-bin-trust-function-creators=1");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("m.daocloud.io/docker.io/library/redis:7-alpine"))
        .withCommand("sh", "-c",
            "redis-server --appendonly no --save \"\" --bind 0.0.0.0 & "
                + "while true; do sleep 3600; done")
        .withExposedPorts(6379)
        .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
            + "?connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlacementDecisionService decisionService;

    @Autowired
    private PlacementEventService eventService;

    @Autowired
    private PlacementFrequencyGuard frequencyGuard;

    @Autowired
    private com.example.venue.placement.mapper.PlacementCandidateVersionMapper candidateMapper;

    @Autowired
    private com.example.venue.placement.mapper.PlacementEventMapper eventMapper;

    @BeforeEach
    void seedFixtures() {
        jdbc.update("DELETE FROM placement_event WHERE candidate_version_id IN (?, ?)",
            CONCURRENCY_CANDIDATE_ID, FREQUENCY_CANDIDATE_ID);
        jdbc.update("DELETE FROM placement_decision WHERE candidate_version_id IN (?, ?)",
            CONCURRENCY_CANDIDATE_ID, FREQUENCY_CANDIDATE_ID);
        jdbc.update("DELETE FROM placement_decision_guard WHERE candidate_version_id IN (?, ?)",
            CONCURRENCY_CANDIDATE_ID, FREQUENCY_CANDIDATE_ID);
        jdbc.update("DELETE FROM placement_candidate_version WHERE id IN (?, ?)",
            CONCURRENCY_CANDIDATE_ID, FREQUENCY_CANDIDATE_ID);
        jdbc.update("DELETE FROM notice WHERE id = ?", NOTICE_ID);
        jdbc.update("DELETE FROM placement_event WHERE event_no LIKE ?", SCALE_EVENT_PREFIX + "%");
        jdbc.update("DELETE FROM placement_candidate_version WHERE candidate_no LIKE ?",
            SCALE_CANDIDATE_PREFIX + "%");
        jdbc.update("""
            INSERT INTO notice (
                id, title, content, type, target_venue_id, status, publish_time,
                create_time, update_time, deleted, target_rule_json
            ) VALUES (?, 'S09 隔离公告', '<p>隔离摘要</p>', 'GLOBAL', NULL, 'PUBLISHED',
                      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0,
                      '{"targetScope":"ALL_USERS"}')
            """, NOTICE_ID);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mysqlGuardSerializesConcurrentFirstDecisions() throws Exception {
        insertCandidate(CONCURRENCY_CANDIDATE_ID, 1);
        authenticate(USER_ID);

        int concurrency = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<PlacementResponses.HomePrimaryDecision>> futures = java.util.stream.IntStream.range(0, concurrency)
                .mapToObj(index -> executor.submit(() -> {
                    authenticate(USER_ID);
                    start.await();
                    try {
                        return decisionService.decideHomePrimary();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }))
                .toList();
            start.countDown();

            List<PlacementResponses.HomePrimaryDecision> decisions = futures.stream()
                .map(this::get)
                .toList();
            assertThat(decisions).allMatch(decision -> decision != null);
            assertThat(decisions).extracting(PlacementResponses.HomePrimaryDecision::decisionId)
                .containsOnly(decisions.get(0).decisionId());
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM placement_decision WHERE candidate_version_id = ?", Long.class,
                CONCURRENCY_CANDIDATE_ID)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reusableIssuedDecisionReturnsRotatedTokenThatRecordsViewableEvent() {
        insertCandidate(CONCURRENCY_CANDIDATE_ID, 1);
        authenticate(USER_ID);

        PlacementResponses.HomePrimaryDecision first = decisionService.decideHomePrimary();
        PlacementResponses.HomePrimaryDecision reused = decisionService.decideHomePrimary();

        assertThat(first).isNotNull();
        assertThat(reused).isNotNull();
        assertThat(reused.reused()).isTrue();
        assertThat(reused.decisionId()).isEqualTo(first.decisionId());
        assertThat(reused.token()).isNotBlank();
        assertThat(reused.token()).isNotEqualTo(first.token());
        assertThat(eventService.recordEvent(
            reused.decisionId(), viewable(reused.token()), "s09-reused-viewable").resultStatus())
            .isEqualTo("ACCEPTED");
    }

    @Test
    void mysqlFrequencyRemainsAuthoritativeAcrossRedisStopClearAndRecovery() throws Exception {
        insertCandidate(FREQUENCY_CANDIDATE_ID, 2);
        authenticate(USER_ID);

        PlacementResponses.HomePrimaryDecision first = decisionService.decideHomePrimary();
        assertThat(first).isNotNull();
        assertThat(eventService.recordEvent(first.decisionId(), viewable(first.token()), "s09-frequency-1")
            .resultStatus()).isEqualTo("ACCEPTED");

        assertThat(frequencyGuard.tryReadHint(USER_ID, 1L, FREQUENCY_CANDIDATE_ID, 24)).isEqualTo(1L);
        REDIS.execInContainer("redis-cli", "FLUSHDB");
        assertThat(frequencyGuard.tryReadHint(USER_ID, 1L, FREQUENCY_CANDIDATE_ID, 24)).isZero();

        REDIS.execInContainer("redis-cli", "shutdown", "nosave");
        assertThat(frequencyGuard.tryReadHint(USER_ID, 1L, FREQUENCY_CANDIDATE_ID, 24)).isNull();

        PlacementResponses.HomePrimaryDecision second = decisionService.decideHomePrimary();
        assertThat(second).isNotNull();
        assertThat(eventService.recordEvent(second.decisionId(), viewable(second.token()), "s09-frequency-2")
            .resultStatus()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM placement_event
            WHERE user_id = ? AND candidate_version_id = ?
              AND event_type = 'CLIENT_VIEWABLE' AND result_status = 'ACCEPTED'
            """, Long.class, USER_ID, FREQUENCY_CANDIDATE_ID)).isEqualTo(2L);

        REDIS.execInContainer("sh", "-c",
            "redis-server --appendonly no --save \"\" --bind 0.0.0.0 --daemonize yes");
        awaitRedisRecovery();
        assertThat(frequencyGuard.tryReadHint(USER_ID, 1L, FREQUENCY_CANDIDATE_ID, 24)).isZero();

        assertThat(decisionService.decideHomePrimary()).isNull();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM placement_event
            WHERE user_id = ? AND candidate_version_id = ?
              AND event_type = 'CLIENT_VIEWABLE' AND result_status = 'ACCEPTED'
            """, Long.class, USER_ID, FREQUENCY_CANDIDATE_ID)).isEqualTo(2L);
    }

    @Test
    void placementQueriesStayIndexedAndBoundedAtControlledScale() {
        insertScaleFixture();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime recentFrom = now.minusHours(2);
        LocalDateTime recentTo = now.plusHours(1);

        assertThat(candidateMapper.findPublishedForDecision(1L, now, 100)).hasSize(100);
        assertThat(eventMapper.findRecentBySlot(1L, recentFrom, recentTo, 100))
            .hasSize(100);
        assertThat(eventMapper.countAcceptedViewable(USER_ID, 1L, SCALE_CANDIDATE_BASE,
            now.minusHours(2))).isEqualTo(5);

        assertIndexed("candidate", """
            EXPLAIN SELECT * FROM placement_candidate_version
            WHERE slot_id = 1 AND status = 'PUBLISHED'
              AND effective_from <= CURRENT_TIMESTAMP
              AND effective_until > CURRENT_TIMESTAMP
            ORDER BY priority DESC, effective_from DESC, id ASC
            LIMIT 100
            """, "idx_placement_candidate_slot_status_priority");
        assertIndexed("event-count", """
            EXPLAIN SELECT COUNT(*) FROM placement_event
            WHERE user_id = 990001 AND slot_id = 1 AND candidate_version_id = 991000
              AND event_type = 'CLIENT_VIEWABLE' AND result_status = 'ACCEPTED'
              AND occurred_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 2 HOUR)
            """, Set.of(
                "idx_placement_event_user_candidate_type_time",
                "idx_placement_event_slot_type_time"));
        assertMapperRecentBySlotPlan(1L, recentFrom, recentTo, 100);
    }

    private void insertCandidate(long candidateId, int cap) {
        LocalDateTime from = LocalDateTime.now().minusMinutes(5);
        LocalDateTime until = LocalDateTime.now().plusHours(1);
        jdbc.update("""
            INSERT INTO placement_candidate_version (
                id, slot_id, candidate_no, source_type, source_id, source_version,
                effective_from, effective_until, audience_type, priority,
                frequency_cap_per_user, frequency_cap_window_hours, rule_version, status
            ) VALUES (?, 1, ?, 'GLOBAL_NOTICE', ?, 'S09', ?, ?, 'ALL_AUTHENTICATED',
                      10, ?, 24, 'PLACEMENT_RULE_S09_TEST', 'PUBLISHED')
            """, candidateId, "S09-CANDIDATE-" + candidateId, NOTICE_ID, from, until, cap);
    }

    private PlacementRequests.RecordEventRequest viewable(String token) {
        return new PlacementRequests.RecordEventRequest("CLIENT_VIEWABLE", token, 1.0, 1000);
    }

    private void insertScaleFixture() {
        LocalDateTime from = LocalDateTime.now().minusMinutes(5);
        LocalDateTime until = LocalDateTime.now().plusHours(1);
        List<Object[]> candidates = IntStream.range(0, 1000)
            .mapToObj(index -> new Object[] {
                SCALE_CANDIDATE_BASE + index, 1L, SCALE_CANDIDATE_PREFIX + index,
                NOTICE_ID, from, until, index % 1000, 5, 24
            })
            .toList();
        jdbc.batchUpdate("""
            INSERT INTO placement_candidate_version (
                id, slot_id, candidate_no, source_type, source_id, source_version,
                effective_from, effective_until, audience_type, priority,
                frequency_cap_per_user, frequency_cap_window_hours, rule_version, status
            ) VALUES (?, ?, ?, 'GLOBAL_NOTICE', ?, 'S09-SCALE', ?, ?,
                      'ALL_AUTHENTICATED', ?, ?, ?, 'PLACEMENT_RULE_S09_SCALE', 'PUBLISHED')
            """, candidates);

        List<Object[]> events = IntStream.range(0, 5000)
            .mapToObj(index -> new Object[] {
                SCALE_EVENT_PREFIX + index, SCALE_EVENT_PREFIX + "KEY-" + index,
                "CLIENT_VIEWABLE", SCALE_EVENT_BASE + index, USER_ID, 1L,
                SCALE_CANDIDATE_BASE + (index % 1000), "a".repeat(64), 1.0, 1000,
                "ACCEPTED", LocalDateTime.now().minusSeconds(index)
            })
            .toList();
        jdbc.batchUpdate("""
            INSERT INTO placement_event (
                event_no, event_key, event_type, decision_id, user_id, slot_id,
                candidate_version_id, request_hash, visible_ratio, visible_duration_ms,
                result_status, occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, events);
        // Use statistics for this fixture, rather than the almost-empty table at startup.
        jdbc.execute("ANALYZE TABLE placement_candidate_version, placement_event");
    }

    private void assertIndexed(String label, String explainSql, String expectedIndex) {
        assertIndexed(label, explainSql, Set.of(expectedIndex));
    }

    private void assertMapperRecentBySlotPlan(long slotId, LocalDateTime from,
                                              LocalDateTime to, int limit) {
        java.util.Map<String, Object> row = jdbc.queryForList("""
            EXPLAIN SELECT * FROM placement_event
            FORCE INDEX (idx_placement_event_slot_time)
            WHERE slot_id = ?
              AND occurred_at >= ?
              AND occurred_at < ?
            ORDER BY occurred_at DESC, id DESC
            LIMIT ?
            """, slotId, from, to, limit).stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("EXPLAIN returned no row for event-recent-mapper"));
        assertThat(String.valueOf(row.get("key")))
            .as("EXPLAIN key for event-recent-mapper: %s", row)
            .isEqualTo("idx_placement_event_slot_time");
        assertThat(String.valueOf(row.get("type")))
            .as("EXPLAIN access type for event-recent-mapper: %s", row)
            .isNotEqualToIgnoringCase("ALL");
        assertThat(String.valueOf(row.get("Extra")))
            .as("EXPLAIN Extra for event-recent-mapper: %s", row)
            .doesNotContainIgnoringCase("filesort");
        System.out.printf("S09_PLACEMENT_QUERY_PLAN label=event-recent-mapper key=%s type=%s rows=%s Extra=%s%n",
            row.get("key"), row.get("type"), row.get("rows"), row.get("Extra"));
    }

    private void assertIndexed(String label, String explainSql, Set<String> expectedIndexes) {
        java.util.Map<String, Object> row = jdbc.queryForList(explainSql).stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("EXPLAIN returned no row for " + label));
        assertThat(String.valueOf(row.get("key"))).as("EXPLAIN key for %s: %s", label, row)
            .isIn(expectedIndexes);
        assertThat(String.valueOf(row.get("type"))).as("EXPLAIN access type for %s: %s", label, row)
            .isNotEqualToIgnoringCase("ALL");
        System.out.printf("S09_PLACEMENT_QUERY_PLAN label=%s key=%s type=%s rows=%s%n",
            label, row.get("key"), row.get("type"), row.get("rows"));
    }

    private void awaitRedisRecovery() {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                if (frequencyGuard.tryReadHint(USER_ID, 1L, FREQUENCY_CANDIDATE_ID, 24) != null) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // Retry until the restarted Redis listener accepts connections.
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for Redis recovery", exception);
            }
        }
        throw new AssertionError("Redis did not recover on the original mapped port");
    }

    private void authenticate(Long userId) {
        CurrentUser user = new CurrentUser(userId, "s09-user", Set.of("USER"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError("concurrent placement decision failed", exception);
        }
    }
}
