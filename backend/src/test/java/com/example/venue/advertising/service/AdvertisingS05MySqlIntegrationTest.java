package com.example.venue.advertising.service;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.advertising.dto.AdvertisingDecisionRequests;
import com.example.venue.advertising.dto.AdvertisingDecisionResponses;
import com.example.venue.advertising.dto.AdvertisingRequests;
import com.example.venue.common.CurrentUser;
import com.example.venue.placement.contract.OwnedPlacementDecisionReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.venue.reservation.service.ReservationLifecycleChangedEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "app.provider.observability.scheduled-enabled=false",
    "app.community.retention.enabled=false",
    "app.advertising.retention.enabled=false",
    "spring.task.scheduling.enabled=false",
    "spring.data.redis.timeout=200ms",
    "spring.data.redis.connect-timeout=200ms",
    "spring.datasource.hikari.maximum-pool-size=48",
    "logging.level.com.example.venue=ERROR"
})
class AdvertisingS05MySqlIntegrationTest {
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long SCALE_DECISION_BASE = 5_000_000L;
    private static final long SCALE_EVENT_BASE = 6_000_000L;
    private static final long SCALE_ATTRIBUTION_BASE = 7_000_000L;
    private static final long SCALE_CALLBACK_BASE = 8_000_000L;
    private static final long SCALE_USER_BASE = 9_200_000L;
    private static final long ADVERTISER_ID = 9_210_010L;
    private static final long CAMPAIGN_ID = 9_210_011L;
    private static final long CREATIVE_ID = 9_210_012L;
    private static final long CONCURRENT_USER_ID = 9_210_100L;
    private static final long CONCURRENT_EVENT_ID = 9_210_101L;
    private static final long CONCURRENT_RESERVATION_ID = 9_210_200L;
    private static final long STOP_USER_ID = 9_210_300L;
    private static final long STOP_RESERVATION_ID = 9_210_301L;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("advertising_s05_integration")
        .withUsername("advertising")
        .withPassword("advertising")
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
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean(name = "advertisingConversionTaskExecutor") private TaskExecutor conversionExecutor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdvertisingAnalysisService analysisService;
    @Autowired private AdvertisingConversionService conversionService;
    @Autowired private AdvertisingDecisionService decisionService;
    @Autowired private AdvertisingEventService eventService;
    @Autowired private AdvertisingAdminService adminService;
    @Autowired private AdvertisingRetentionService retentionService;
    @Autowired private OwnedPlacementDecisionReader ownedPlacementReader;

    @BeforeEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM advertising_attribution "
            + "WHERE attribution_no LIKE 'S05-%' OR reservation_id IN (?, ?)",
            CONCURRENT_RESERVATION_ID, STOP_RESERVATION_ID);
        jdbc.update("DELETE FROM advertising_conversion_callback "
            + "WHERE callback_no LIKE 'S05-%' OR user_id IN (?, ?)",
            CONCURRENT_USER_ID, STOP_USER_ID);
        jdbc.update("DELETE FROM advertising_event "
            + "WHERE event_no LIKE 'S05-%' OR user_id IN (?, ?) "
            + "OR event_key LIKE '%:S05-STOP-CLICK'", CONCURRENT_USER_ID, STOP_USER_ID);
        jdbc.update("DELETE FROM advertising_decision "
            + "WHERE decision_no LIKE 'S05-%' OR user_id IN (?, ?)",
            CONCURRENT_USER_ID, STOP_USER_ID);
        jdbc.update("DELETE FROM advertising_audit_event WHERE audit_no LIKE 'S05-%'");
        jdbc.update("DELETE FROM advertising_decision_guard WHERE user_id = ?", CONCURRENT_USER_ID);
        jdbc.update("DELETE FROM advertising_conversion_guard WHERE user_id = ?", CONCURRENT_USER_ID);
        jdbc.update("DELETE FROM advertising_privacy_preference WHERE user_id IN (?, ?)",
            CONCURRENT_USER_ID, STOP_USER_ID);
        jdbc.update("DELETE FROM advertising_creative_version WHERE id = ?", CREATIVE_ID);
        jdbc.update("DELETE FROM advertising_campaign WHERE id = ?", CAMPAIGN_ID);
        jdbc.update("DELETE FROM advertising_advertiser WHERE id = ?", ADVERTISER_ID);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void analysisQueriesUseS05IndexesAndStayWithinTheEngineeringP95Gate() {
        LocalDateTime until = LocalDateTime.now().withNano(0);
        LocalDateTime from = until.minusDays(30);
        seedScaleFixture(until.minusDays(1));

        assertPlanUses(
            "EXPLAIN SELECT COUNT(*) FROM advertising_decision "
                + "WHERE create_time >= '" + sql(from) + "' AND create_time < '" + sql(until) + "'",
            "idx_advertising_decision_create_time");
        for (String eventType : List.of("CLIENT_VIEWABLE", "CLICK")) {
            assertPlanUses(
                "EXPLAIN SELECT COUNT(*) FROM advertising_event "
                    + "WHERE event_type = '" + eventType + "' AND result_status = 'ACCEPTED' "
                    + "AND occurred_at >= '" + sql(from) + "' AND occurred_at < '" + sql(until) + "'",
                "idx_advertising_event_type_result_time");
        }
        for (String status : List.of("ATTRIBUTED", "GAP", "REVOKED")) {
            assertPlanUses(
                "EXPLAIN SELECT COUNT(*) FROM advertising_attribution "
                    + "WHERE status = '" + status + "' AND conversion_occurred_at >= '" + sql(from)
                    + "' AND conversion_occurred_at < '" + sql(until) + "'",
                "idx_advertising_attribution_status_time");
        }
        assertJoinPlanUses(
            "EXPLAIN SELECT COUNT(*) "
                + "FROM advertising_event event "
                + "LEFT JOIN advertising_attribution attribution "
                + "ON attribution.click_event_id = event.id "
                + "WHERE event.event_type = 'CLICK' "
                + "AND event.result_status = 'ACCEPTED' "
                + "AND event.occurred_at >= '" + sql(from) + "' "
                + "AND event.occurred_at < '" + sql(until) + "' "
                + "AND attribution.id IS NULL",
            "event", "idx_advertising_event_type_result_time",
            "attribution", "idx_advertising_attribution_click_event");
        for (String resultCode : List.of("NO_VALID_CLICK", "PRIVACY_DISABLED")) {
            assertPlanUses(
                "EXPLAIN SELECT COUNT(*) FROM advertising_conversion_callback "
                    + "WHERE result_code = '" + resultCode + "' AND occurred_at >= '" + sql(from)
                    + "' AND occurred_at < '" + sql(until) + "'",
                "idx_advertising_callback_result_time");
        }
        assertPlanUsesOneOf(
            "EXPLAIN SELECT COUNT(*) FROM advertising_conversion_callback "
                + "WHERE status = 'PENDING_RETRY' AND occurred_at >= '" + sql(from)
                + "' AND occurred_at < '" + sql(until) + "'",
            Set.of("idx_advertising_callback_status_time", "idx_advertising_callback_retry"),
            "idx_advertising_callback_status_time");
        assertPlanUses(
            "EXPLAIN SELECT id FROM advertising_audit_event "
                + "WHERE create_time < '" + sql(until.minusDays(730))
                + "' ORDER BY create_time, id LIMIT 500",
            "idx_advertising_audit_create_time");

        analysisService.overview(from, until);
        List<Long> elapsedMillis = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            long started = System.nanoTime();
            var overview = analysisService.overview(from, until);
            elapsedMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            assertThat(overview.decisions()).isEqualTo(10_000L);
            assertThat(overview.viewableEvents()).isEqualTo(10_000L);
            assertThat(overview.clickEvents()).isEqualTo(10_000L);
            assertThat(overview.attributedReservations()).isEqualTo(2_000L);
            assertThat(overview.gapReservations()).isEqualTo(2_000L);
            assertThat(overview.revokedReservations()).isEqualTo(1_000L);
        }
        long p95 = percentile95(elapsedMillis);
        System.out.printf("R7_S05_ANALYSIS_P95 p95_ms=%d samples=%d%n", p95, elapsedMillis.size());
        assertThat(p95).as("R7 S05 analysis overview p95").isLessThanOrEqualTo(1_000L);
    }

    @Test
    void committedOutboxSurvivesRejectedExecutorWhenEveryConnectionIsOccupied() throws Exception {
        org.mockito.Mockito.doThrow(new java.util.concurrent.RejectedExecutionException("test rejection"))
            .when(conversionExecutor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        int count = 48; // Exactly the configured pool size, each transaction owns one connection.
        ExecutorService workers = Executors.newFixedThreadPool(count);
        CountDownLatch acquired = new CountDownLatch(count);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                final int index = i;
                tasks.add(workers.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    jdbc.queryForObject("SELECT 1", Integer.class);
                    acquired.countDown();
                    try {
                        if (!acquired.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("pool barrier");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    publisher.publishEvent(outboxEvent("R09-POOL-" + index, CONCURRENT_RESERVATION_ID + index));
                    return null;
                })));
            }
            for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advertising_conversion_callback "
                + "WHERE event_key LIKE 'R09-POOL-%' AND status='PENDING' AND next_retry_at IS NOT NULL",
                Integer.class)).isEqualTo(count);
            org.mockito.Mockito.verify(conversionExecutor, org.mockito.Mockito.times(count))
                .execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(40, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void businessRollbackRemovesOutboxAndNeverDispatches() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishEvent(outboxEvent("R09-ROLLBACK", CONCURRENT_RESERVATION_ID));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advertising_conversion_callback "
                + "WHERE event_key='R09-ROLLBACK'", Integer.class)).isOne();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advertising_conversion_callback "
            + "WHERE event_key='R09-ROLLBACK'", Integer.class)).isZero();
        org.mockito.Mockito.verifyNoInteractions(conversionExecutor);
    }

    private ReservationLifecycleChangedEvent outboxEvent(String key, long reservationId) {
        return new ReservationLifecycleChangedEvent(reservationId, CONCURRENT_USER_ID, 1L, key,
            "SUBMITTED", null, "PENDING_REVIEW", "RESERVATION_SUBMITTED", "USER",
            LocalDateTime.now().withNano(0));
    }

    @Test
    void distinctConcurrentOutboxFactsRemainDurableAndReplayOnce() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        List<AdvertisingConversionService.ConversionCommand> commands = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            var command = new AdvertisingConversionService.ConversionCommand(
                "R06-OUTBOX-" + i, "RESERVATION_SUBMITTED", CONCURRENT_RESERVATION_ID + i,
                CONCURRENT_USER_ID, 1L, "PENDING_REVIEW", LocalDateTime.now().withNano(0));
            commands.add(command);
            tasks.add(workers.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                conversionService.persistPending(command);
                return null;
            }));
        }
        try {
            start.countDown();
            for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
            for (var command : commands) conversionService.persistPending(command);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advertising_conversion_callback WHERE event_key LIKE 'R06-OUTBOX-%'", Integer.class)).isEqualTo(32);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advertising_conversion_callback WHERE event_key LIKE 'R06-OUTBOX-%' AND status='PENDING' AND next_retry_at IS NOT NULL", Integer.class)).isEqualTo(32);
        } finally {
            start.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void thirtyTwoConcurrentReplaysProduceOneAttributionAndCallback() throws Exception {
        seedConcurrentClick();
        String eventKey = "S05-CONCURRENT-REPLAY";
        AdvertisingConversionService.ConversionCommand command = new AdvertisingConversionService.ConversionCommand(
            eventKey, "RESERVATION_SUBMITTED", 9_210_200L, CONCURRENT_USER_ID, 1L,
            "PENDING_REVIEW", LocalDateTime.now().withNano(0));
        int concurrency = 32;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int index = 0; index < concurrency; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    long started = System.nanoTime();
                    conversionService.accept(command);
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                }));
            }
            start.countDown();
            List<Long> elapsedMillis = new ArrayList<>();
            for (Future<Long> future : futures) {
                elapsedMillis.add(future.get(30, TimeUnit.SECONDS));
            }
            long p95 = percentile95(elapsedMillis);
            System.out.printf("R7_S05_CONVERSION_P95 p95_ms=%d samples=%d%n", p95, elapsedMillis.size());
            assertThat(p95).as("R7 S05 conversion p95").isLessThanOrEqualTo(1_000L);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM advertising_conversion_callback WHERE event_key = ?", Long.class, eventKey))
            .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM advertising_attribution WHERE reservation_id = ?", Long.class,
            command.reservationId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM advertising_attribution WHERE reservation_id = ?", String.class,
            command.reservationId())).isEqualTo(AdvertisingConversionService.ATTRIBUTED);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM advertising_conversion_callback WHERE event_key = ?", String.class, eventKey))
            .isEqualTo("PROCESSED");
    }

    @Test
    void stoppedCampaignBlocksNewDecisionsButKeepsHistoricalFactsReadable() {
        seedPublishedCommercial();
        enablePrivacy(9_210_300L);
        authenticateUser(9_210_300L);
        assertThat(ownedPlacementReader.readHomePrimary().status())
            .isEqualTo(OwnedPlacementDecisionReader.Status.EMPTY);
        AdvertisingDecisionResponses.HomeCommercialDecision decision = decisionService.decideHomeCommercial();
        assertThat(decision).isNotNull();
        assertThat(eventService.recordEvent(
            decision.decisionId(), click(decision.token()), "S05-STOP-CLICK").resultStatus())
            .isEqualTo("ACCEPTED");
        conversionService.accept(new AdvertisingConversionService.ConversionCommand(
            "S05-STOP-CONVERSION", "RESERVATION_SUBMITTED", 9_210_301L, 9_210_300L, 1L,
            "PENDING_REVIEW", LocalDateTime.now()));

        authenticateOperator();
        adminService.pauseCampaign(CAMPAIGN_ID,
            new AdvertisingRequests.TransitionRequest("S05_STOP", "S05 停用验证"));

        authenticateUser(9_210_300L);
        assertThat(decisionService.decideHomeCommercial()).isNull();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM advertising_decision WHERE decision_no LIKE 'ADD-%' AND campaign_id = ?",
            Long.class, CAMPAIGN_ID)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM advertising_event WHERE event_key LIKE '%:S05-STOP-CLICK'", Long.class))
            .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM advertising_attribution WHERE reservation_id = 9210301", Long.class))
            .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM advertising_audit_event "
                + "WHERE entity_type = 'CAMPAIGN' AND entity_id = ? AND action = 'PAUSE'",
            Long.class, CAMPAIGN_ID)).isEqualTo(1L);
    }

    @Test
    void retentionRemovesOnlyExpiredTerminalFactsAndPreservesPendingRetry() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        insertDecision(9_210_400L, "S05-OLD-DECISION", now.minusDays(90).minusSeconds(1));
        insertDecision(9_210_401L, "S05-BOUNDARY-DECISION", now.minusDays(90).plusHours(1));
        insertEvent(9_210_402L, "S05-OLD-EVENT", "S05-OLD-EVENT", 9_210_400L,
            "CLICK", now.minusDays(90).minusSeconds(1));
        insertEvent(9_210_403L, "S05-BOUNDARY-EVENT", "S05-BOUNDARY-EVENT", 9_210_401L,
            "CLICK", now.minusDays(90).plusHours(1));
        insertCallback(9_210_404L, "S05-OLD-CALLBACK", "PROCESSED", "ATTRIBUTED",
            now.minusDays(180).minusSeconds(1), null);
        insertCallback(9_210_405L, "S05-PENDING-RETRY", "PENDING_RETRY", "ADVERTISING_CALLBACK_FAILED",
            now.minusDays(365), now.plusMinutes(1));
        insertAttribution(9_210_406L, "S05-OLD-ATTRIBUTION", 9_210_406L,
            "GAP", now.minusDays(180).minusSeconds(1));
        insertAttribution(9_210_407L, "S05-BOUNDARY-ATTRIBUTION", 9_210_407L,
            "GAP", now.minusDays(180).plusHours(1));
        insertAudit("S05-OLD-AUDIT", now.minusDays(730).minusSeconds(1));

        retentionService.runOnce();

        assertThat(countBy("advertising_decision", "decision_no", "S05-OLD-DECISION")).isZero();
        assertThat(countBy("advertising_event", "event_no", "S05-OLD-EVENT")).isZero();
        assertThat(countBy("advertising_conversion_callback", "callback_no", "S05-OLD-CALLBACK")).isZero();
        assertThat(countBy("advertising_attribution", "attribution_no", "S05-OLD-ATTRIBUTION")).isZero();
        assertThat(countBy("advertising_audit_event", "audit_no", "S05-OLD-AUDIT")).isZero();
        assertThat(countBy("advertising_decision", "decision_no", "S05-BOUNDARY-DECISION")).isEqualTo(1L);
        assertThat(countBy("advertising_event", "event_no", "S05-BOUNDARY-EVENT")).isEqualTo(1L);
        assertThat(countBy("advertising_conversion_callback", "callback_no", "S05-PENDING-RETRY"))
            .isEqualTo(1L);
        assertThat(countBy("advertising_attribution", "attribution_no", "S05-BOUNDARY-ATTRIBUTION"))
            .isEqualTo(1L);
    }

    private void seedScaleFixture(LocalDateTime occurredAt) {
        List<Object[]> decisions = new ArrayList<>(10_000);
        for (int index = 0; index < 10_000; index++) {
            long id = SCALE_DECISION_BASE + index;
            decisions.add(new Object[]{id, "S05-DECISION-" + index, SCALE_USER_BASE + index,
                ADVERTISER_ID, CAMPAIGN_ID, CREATIVE_ID, String.format("%064x", index + 1),
                occurredAt.plusDays(1), occurredAt.plusSeconds(index % 60)});
        }
        jdbc.batchUpdate("""
            INSERT INTO advertising_decision
              (id, decision_no, user_id, slot_code, advertiser_id, campaign_id, creative_version_id,
               token_hash, policy_version, rule_version_snapshot, privacy_snapshot_json,
               frequency_snapshot_json, creative_snapshot_json, arbitration_snapshot_json,
               expires_at, status, create_time)
            VALUES (?, ?, ?, 'HOME_COMMERCIAL', ?, ?, ?, ?, 'G-R7-20260803-v1',
                    'ADVERTISING_RULE_V1', '{}', '{}', '{}', '{}', ?, 'ISSUED', ?)
            """, decisions);

        List<Object[]> events = new ArrayList<>(20_000);
        for (int index = 0; index < 10_000; index++) {
            long decisionId = SCALE_DECISION_BASE + index;
            long userId = SCALE_USER_BASE + index;
            LocalDateTime eventTime = occurredAt.plusSeconds(index % 60);
            events.add(eventArgs(SCALE_EVENT_BASE + index * 2L, "S05-EVENT-V-" + index,
                "S05-EVENT-V-" + index, decisionId, userId, "CLIENT_VIEWABLE", eventTime));
            events.add(eventArgs(SCALE_EVENT_BASE + index * 2L + 1L, "S05-EVENT-C-" + index,
                "S05-EVENT-C-" + index, decisionId, userId, "CLICK", eventTime));
        }
        jdbc.batchUpdate("""
            INSERT INTO advertising_event
              (id, event_no, event_key, event_type, decision_id, user_id, slot_code,
               advertiser_id, campaign_id, creative_version_id, request_hash, visible_ratio,
               visible_duration_ms, result_status, rejection_code, policy_version, occurred_at, create_time)
            VALUES (?, ?, ?, ?, ?, ?, 'HOME_COMMERCIAL', ?, ?, ?, ?, ?, ?, 'ACCEPTED', NULL,
                    'G-R7-20260803-v1', ?, ?)
            """, events);

        List<Object[]> attributions = new ArrayList<>(5_000);
        for (int index = 0; index < 5_000; index++) {
            String status = index < 2_000 ? "ATTRIBUTED" : index < 4_000 ? "GAP" : "REVOKED";
            Long clickEventId = "ATTRIBUTED".equals(status)
                ? SCALE_EVENT_BASE + index * 2L + 1L : null;
            attributions.add(new Object[]{SCALE_ATTRIBUTION_BASE + index,
                "S05-ATTRIBUTION-" + index, 10_000_000L + index, SCALE_USER_BASE + index,
                clickEventId, status, occurredAt.plusSeconds(index % 60)});
        }
        jdbc.batchUpdate("""
            INSERT INTO advertising_attribution
              (id, attribution_no, reservation_id, user_id, click_event_id, decision_id,
               advertiser_id, campaign_id, creative_version_id, click_occurred_at,
               conversion_occurred_at, policy_version, rule_version_snapshot, status,
               reason_code, source_status, source_event_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'G-R7-20260803-v1',
                    'ADVERTISING_ATTRIBUTION_V1', ?, ?, 'PENDING_REVIEW', ?)
            """, attributions.stream().map(row -> new Object[]{
                row[0], row[1], row[2], row[3], row[4],
                row[4] == null ? null : SCALE_DECISION_BASE + ((Long) row[2] - 10_000_000L),
                row[4] == null ? null : ADVERTISER_ID,
                row[4] == null ? null : CAMPAIGN_ID,
                row[4] == null ? null : CREATIVE_ID,
                row[4] == null ? null : row[6], row[6], row[5],
                "ATTRIBUTED".equals(row[5]) ? "LAST_VALID_CLICK_7D" : "NO_VALID_CLICK",
                "S05-ATTRIBUTION-EVENT-" + row[0]
            }).toList());

        List<Object[]> callbacks = new ArrayList<>(5_000);
        for (int index = 0; index < 5_000; index++) {
            String status = index == 4_999 ? "PENDING_RETRY" : "PROCESSED";
            String resultCode = index < 2_000 ? "ATTRIBUTED" : "NO_VALID_CLICK";
            callbacks.add(new Object[]{SCALE_CALLBACK_BASE + index, "S05-CALLBACK-" + index,
                "S05-CALLBACK-EVENT-" + index, 10_000_000L + index,
                SCALE_USER_BASE + index, status, resultCode, occurredAt.plusSeconds(index % 60),
                "PENDING_RETRY".equals(status) ? occurredAt.plusDays(1) : null});
        }
        jdbc.batchUpdate("""
            INSERT INTO advertising_conversion_callback
              (id, callback_no, event_key, event_type, reservation_id, user_id, venue_id,
               source_status, policy_version, occurred_at, status, result_code, attempt_count,
               next_retry_at, processed_at)
            VALUES (?, ?, ?, 'RESERVATION_SUBMITTED', ?, ?, 1, 'PENDING_REVIEW',
                    'G-R7-20260803-v1', ?, ?, ?, 0, ?, ?)
            """, callbacks.stream().map(row -> new Object[]{
                row[0], row[1], row[2], row[3], row[4], row[7], row[5], row[6], row[8],
                "PROCESSED".equals(row[5]) ? row[7] : null
            }).toList());
    }

    private Object[] eventArgs(long id, String eventNo, String eventKey, long decisionId,
                               long userId, String eventType, LocalDateTime occurredAt) {
        return new Object[]{id, eventNo, eventKey, eventType, decisionId, userId,
            ADVERTISER_ID, CAMPAIGN_ID, CREATIVE_ID, String.format("%064x", id),
            "CLIENT_VIEWABLE".equals(eventType) ? new BigDecimal("1.0000") : null,
            "CLIENT_VIEWABLE".equals(eventType) ? 1000 : null, occurredAt, occurredAt};
    }

    private void seedConcurrentClick() {
        LocalDateTime clickAt = LocalDateTime.now().minusSeconds(1).withNano(0);
        jdbc.update("""
            INSERT INTO advertising_privacy_preference
              (user_id, commercial_enabled, measurement_enabled, policy_version, changed_at)
            VALUES (?, 1, 1, 'G-R7-20260803-v1', CURRENT_TIMESTAMP)
            """, CONCURRENT_USER_ID);
        jdbc.update("""
            INSERT INTO advertising_event
              (id, event_no, event_key, event_type, decision_id, user_id, slot_code,
               advertiser_id, campaign_id, creative_version_id, request_hash, result_status,
               policy_version, occurred_at, create_time)
            VALUES (?, 'S05-CONCURRENT-CLICK', 'S05-CONCURRENT-CLICK', 'CLICK', ?, ?,
                    'HOME_COMMERCIAL', 1, 1, 1, ?, 'ACCEPTED', 'G-R7-20260803-v1', ?, ?)
            """, CONCURRENT_EVENT_ID, 1L, CONCURRENT_USER_ID, String.format("%064x", CONCURRENT_EVENT_ID),
            clickAt, clickAt);
    }

    private void seedPublishedCommercial() {
        LocalDateTime from = LocalDateTime.now().minusMinutes(5);
        LocalDateTime until = LocalDateTime.now().plusHours(2);
        jdbc.update("""
            INSERT INTO advertising_advertiser
              (id, advertiser_no, advertiser_name, status, created_by)
            VALUES (?, 'S05-ADVERTISER', 'S05 广告主', 'ACTIVE', 1)
            """, ADVERTISER_ID);
        jdbc.update("""
            INSERT INTO advertising_campaign
              (id, campaign_no, advertiser_id, campaign_name, effective_from, effective_until,
               status, created_by, reviewed_by, published_by, reviewed_at, published_at)
            VALUES (?, 'S05-CAMPAIGN', ?, 'S05 计划', ?, ?, 'PUBLISHED', 1, 2, 2, ?, ?)
            """, CAMPAIGN_ID, ADVERTISER_ID, from, until, from, from);
        jdbc.update("""
            INSERT INTO advertising_creative_version
              (id, creative_no, campaign_id, version_no, headline, body_text,
               call_to_action, target_type, target_id, status, created_by,
               reviewed_by, published_by, reviewed_at, published_at)
            VALUES (?, 'S05-CREATIVE', ?, 1, 'S05 标题', 'S05 正文', 'BOOK',
                    'VENUE_DETAIL', 1, 'PUBLISHED', 1, 2, 2, ?, ?)
            """, CREATIVE_ID, CAMPAIGN_ID, from, from);
    }

    private void enablePrivacy(long userId) {
        jdbc.update("""
            INSERT INTO advertising_privacy_preference
              (user_id, commercial_enabled, measurement_enabled, policy_version, changed_at)
            VALUES (?, 1, 1, 'G-R7-20260803-v1', CURRENT_TIMESTAMP)
            """, userId);
    }

    private void insertDecision(long id, String decisionNo, LocalDateTime createTime) {
        jdbc.update("""
            INSERT INTO advertising_decision
              (id, decision_no, user_id, slot_code, advertiser_id, campaign_id, creative_version_id,
               token_hash, policy_version, rule_version_snapshot, privacy_snapshot_json,
               frequency_snapshot_json, creative_snapshot_json, arbitration_snapshot_json,
               expires_at, status, create_time)
            VALUES (?, ?, 1, 'HOME_COMMERCIAL', 1, 1, 1, ?, 'G-R7-20260803-v1',
                    'ADVERTISING_RULE_V1', '{}', '{}', '{}', '{}', ?, 'ISSUED', ?)
            """, id, decisionNo, String.format("%064x", id), createTime.plusDays(1), createTime);
    }

    private void insertEvent(long id, String eventNo, String eventKey, long decisionId,
                             String eventType, LocalDateTime occurredAt) {
        jdbc.update("""
            INSERT INTO advertising_event
              (id, event_no, event_key, event_type, decision_id, user_id, slot_code,
               advertiser_id, campaign_id, creative_version_id, request_hash,
               visible_ratio, visible_duration_ms, result_status, policy_version, occurred_at, create_time)
            VALUES (?, ?, ?, ?, ?, 1, 'HOME_COMMERCIAL', 1, 1, 1, ?, ?, ?, 'ACCEPTED',
                    'G-R7-20260803-v1', ?, ?)
            """, id, eventNo, eventKey, eventType, decisionId, String.format("%064x", id),
            "CLICK".equals(eventType) ? null : new BigDecimal("1.0000"),
            "CLICK".equals(eventType) ? null : 1000, occurredAt, occurredAt);
    }

    private void insertCallback(long id, String callbackNo, String status, String resultCode,
                                LocalDateTime occurredAt, LocalDateTime nextRetryAt) {
        jdbc.update("""
            INSERT INTO advertising_conversion_callback
              (id, callback_no, event_key, event_type, reservation_id, user_id, venue_id,
               source_status, policy_version, occurred_at, status, result_code, attempt_count,
               next_retry_at, processed_at)
            VALUES (?, ?, ?, 'RESERVATION_SUBMITTED', ?, 1, 1, 'PENDING_REVIEW',
                    'G-R7-20260803-v1', ?, ?, ?, 0, ?, ?)
            """, id, callbackNo, callbackNo, id, occurredAt, status, resultCode, nextRetryAt,
            "PROCESSED".equals(status) ? occurredAt : null);
    }

    private void insertAttribution(long id, String attributionNo, long reservationId,
                                   String status, LocalDateTime conversionAt) {
        jdbc.update("""
            INSERT INTO advertising_attribution
              (id, attribution_no, reservation_id, user_id, conversion_occurred_at,
               policy_version, rule_version_snapshot, status, reason_code, source_status, source_event_key)
            VALUES (?, ?, ?, 1, ?, 'G-R7-20260803-v1', 'ADVERTISING_ATTRIBUTION_V1', ?,
                    'NO_VALID_CLICK', 'PENDING_REVIEW', ?)
            """, id, attributionNo, reservationId, conversionAt, status, attributionNo);
    }

    private void insertAudit(String auditNo, LocalDateTime createTime) {
        jdbc.update("""
            INSERT INTO advertising_audit_event
              (audit_no, entity_type, entity_id, entity_no, action, actor_id,
               source_type, result_status, create_time)
            VALUES (?, 'CAMPAIGN', 1, 'S05-CAMPAIGN', 'PAUSE', 1,
                    'SCHEDULED_JOB', 'ACCEPTED', ?)
            """, auditNo, createTime);
    }

    private AdvertisingDecisionRequests.RecordEventRequest click(String token) {
        return new AdvertisingDecisionRequests.RecordEventRequest(
            AdvertisingEventService.CLICK, token, null, null);
    }

    private void authenticateUser(long userId) {
        CurrentUser principal = new CurrentUser(userId, "s05-user-" + userId,
            Set.of("USER"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private void authenticateOperator() {
        Set<String> permissions = Set.of("advertising:manage", "advertising:publish");
        CurrentUser principal = new CurrentUser(99_999L, "s05-operator",
            Set.of("SYSTEM_ADMIN"), permissions);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null,
                permissions.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private void assertPlanUses(String explainSql, String expectedIndex) {
        List<Map<String, Object>> rows = jdbc.queryForList(explainSql);
        assertThat(rows).isNotEmpty();
        assertThat(rows).anySatisfy(row -> {
            assertThat(String.valueOf(row.get("key"))).contains(expectedIndex);
            assertThat(String.valueOf(row.get("type"))).isNotEqualTo("ALL");
            assertPlanEstimatesRows(row);
        });
    }

    private void assertPlanUsesOneOf(String explainSql,
                                     Set<String> acceptableIndexes,
                                     String requiredPossibleIndex) {
        List<Map<String, Object>> rows = jdbc.queryForList(explainSql);
        assertThat(rows).isNotEmpty();
        assertThat(rows).anySatisfy(row -> {
            assertThat(acceptableIndexes).contains(String.valueOf(row.get("key")));
            assertThat(String.valueOf(row.get("possible_keys"))).contains(requiredPossibleIndex);
            assertThat(String.valueOf(row.get("type"))).isNotEqualTo("ALL");
            assertPlanEstimatesRows(row);
        });
    }

    private void assertJoinPlanUses(String explainSql,
                                    String firstTable,
                                    String firstIndex,
                                    String secondTable,
                                    String secondIndex) {
        List<Map<String, Object>> rows = jdbc.queryForList(explainSql);
        assertThat(rows).isNotEmpty();
        assertPlanUsesForTable(rows, firstTable, firstIndex);
        assertPlanUsesForTable(rows, secondTable, secondIndex);
    }

    private void assertPlanUsesForTable(List<Map<String, Object>> rows,
                                        String tableName,
                                        String expectedIndex) {
        Map<String, Object> row = rows.stream()
            .filter(candidate -> tableName.equals(String.valueOf(candidate.get("table"))))
            .findFirst()
            .orElseThrow(() -> new AssertionError("EXPLAIN did not include table " + tableName));
        assertThat(String.valueOf(row.get("key")))
            .as("EXPLAIN key for table %s", tableName)
            .contains(expectedIndex);
        assertThat(String.valueOf(row.get("type")))
            .as("EXPLAIN access type for table %s", tableName)
            .isNotEqualTo("ALL");
        assertPlanEstimatesRows(row);
    }

    private void assertPlanEstimatesRows(Map<String, Object> row) {
        assertThat(row.get("rows")).isInstanceOf(Number.class);
        assertThat(((Number) row.get("rows")).longValue()).isPositive();
    }

    private long percentile95(List<Long> values) {
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1);
        return sorted.get(index);
    }

    private String sql(LocalDateTime value) {
        return value.format(SQL_TIME);
    }

    private long countBy(String table, String column, String value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
            Long.class, value);
    }
}
