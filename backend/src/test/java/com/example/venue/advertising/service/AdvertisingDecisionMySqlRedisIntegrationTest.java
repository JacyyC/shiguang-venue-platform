package com.example.venue.advertising.service;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.advertising.dto.AdvertisingDecisionRequests;
import com.example.venue.advertising.dto.AdvertisingDecisionResponses;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.placement.contract.OwnedPlacementDecisionReader;
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
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    "spring.datasource.hikari.maximum-pool-size=24",
    "logging.level.com.example.venue=ERROR"
})
class AdvertisingDecisionMySqlRedisIntegrationTest {
    private static final long USER_ID = 990501L;
    private static final long OTHER_USER_ID = 990502L;
    private static final long ADVERTISER_ID = 990510L;
    private static final long CAMPAIGN_ID = 990511L;
    private static final long CREATIVE_BASE_ID = 990520L;
    private static final long NOTICE_ID = 990590L;
    private static final long R6_CANDIDATE_ID = 990591L;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("advertising_decision_integration")
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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdvertisingDecisionService decisionService;
    @Autowired private AdvertisingEventService eventService;
    @Autowired private AdvertisingConversionService conversionService;
    @Autowired private AdvertisingFrequencyHint frequencyHint;
    @Autowired private OwnedPlacementDecisionReader ownedPlacementReader;

    @BeforeEach
    void cleanFixtures() throws Exception {
        jdbc.update("DELETE FROM advertising_event WHERE user_id IN (?, ?)", USER_ID, OTHER_USER_ID);
        jdbc.update("DELETE FROM advertising_attribution WHERE user_id IN (?, ?)", USER_ID, OTHER_USER_ID);
        jdbc.update("DELETE FROM advertising_conversion_callback WHERE user_id IN (?, ?)", USER_ID, OTHER_USER_ID);
        jdbc.update("DELETE FROM advertising_decision WHERE user_id IN (?, ?)", USER_ID, OTHER_USER_ID);
        jdbc.update("DELETE FROM advertising_decision_guard WHERE user_id IN (?, ?)", USER_ID, OTHER_USER_ID);
        jdbc.update("DELETE FROM advertising_privacy_preference WHERE user_id IN (?, ?)", USER_ID, OTHER_USER_ID);
        jdbc.update("DELETE FROM advertising_creative_version WHERE campaign_id = ?", CAMPAIGN_ID);
        jdbc.update("DELETE FROM advertising_campaign WHERE id = ?", CAMPAIGN_ID);
        jdbc.update("DELETE FROM advertising_advertiser WHERE id = ?", ADVERTISER_ID);
        jdbc.update("DELETE FROM placement_event WHERE candidate_version_id = ?", R6_CANDIDATE_ID);
        jdbc.update("DELETE FROM placement_decision WHERE candidate_version_id = ?", R6_CANDIDATE_ID);
        jdbc.update("DELETE FROM placement_decision_guard WHERE candidate_version_id = ?", R6_CANDIDATE_ID);
        jdbc.update("DELETE FROM placement_candidate_version WHERE id = ?", R6_CANDIDATE_ID);
        jdbc.update("DELETE FROM notice WHERE id = ?", NOTICE_ID);
        REDIS.execInContainer("redis-cli", "FLUSHDB");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void commercialDecisionRequiresBothPreferencesAndUsesIndependentV25Facts() {
        seedCommercial(1);
        authenticate(USER_ID);

        assertThat(decisionService.decideHomeCommercial()).isNull();
        enablePrivacy(USER_ID);

        AdvertisingDecisionResponses.HomeCommercialDecision decision = decisionService.decideHomeCommercial();

        assertThat(decision).isNotNull();
        assertThat(decision.adLabel()).isEqualTo("广告");
        assertThat(decision.accessibleAdLabel()).isEqualTo("广告内容");
        assertThat(decision.deepLink()).startsWith("/venues/");
        assertThat(decision.token()).isNotBlank();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM advertising_decision WHERE user_id = ?", Long.class, USER_ID))
            .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM placement_decision WHERE user_id = ?", Long.class, USER_ID))
            .isZero();
        assertThat(jdbc.queryForObject(
            "SELECT token_hash FROM advertising_decision WHERE user_id = ?", String.class, USER_ID))
            .hasSize(64)
            .doesNotContain(decision.token());
    }

    @Test
    void R6AvailableDecisionBlocksCommercialFallback() {
        seedCommercial(1);
        seedR6AvailableCandidate();
        enablePrivacy(USER_ID);
        authenticate(USER_ID);

        assertThat(ownedPlacementReader.readHomePrimary().status())
            .isEqualTo(OwnedPlacementDecisionReader.Status.AVAILABLE);
        assertThat(decisionService.decideHomeCommercial()).isNull();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM advertising_decision WHERE user_id = ?", Long.class, USER_ID))
            .isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM placement_decision WHERE user_id = ?", Long.class, USER_ID))
            .isEqualTo(1L);
    }

    @Test
    void MySqlCapsRemainAuthoritativeAfterRedisHintDataIsCleared() throws Exception {
        seedCommercial(4);
        enablePrivacy(USER_ID);
        authenticate(USER_ID);

        for (int index = 0; index < 3; index++) {
            AdvertisingDecisionResponses.HomeCommercialDecision decision = decisionService.decideHomeCommercial();
            assertThat(decision).isNotNull();
            assertThat(eventService.recordEvent(
                decision.decisionId(), viewable(decision.token()), "frequency-" + index).resultStatus())
                .isEqualTo("ACCEPTED");
            if (index == 0) {
                assertThat(frequencyHint.tryReadUserSlot(USER_ID, 24)).isEqualTo(1L);
                REDIS.execInContainer("redis-cli", "FLUSHDB");
                assertThat(frequencyHint.tryReadUserSlot(USER_ID, 24)).isZero();
            }
        }

        assertThat(decisionService.decideHomeCommercial()).isNull();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM advertising_event
            WHERE user_id = ? AND slot_code = 'HOME_COMMERCIAL'
              AND event_type = 'CLIENT_VIEWABLE' AND result_status = 'ACCEPTED'
            """, Long.class, USER_ID)).isEqualTo(3L);
    }

    @Test
    void concurrentFirstDecisionsShareOneMySqlGuardedDecision() throws Exception {
        seedCommercial(1);
        enablePrivacy(USER_ID);
        int concurrency = 12;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<java.util.concurrent.Future<AdvertisingDecisionResponses.HomeCommercialDecision>> futures =
                java.util.stream.IntStream.range(0, concurrency)
                    .mapToObj(index -> executor.submit(() -> {
                        authenticate(USER_ID);
                        start.await();
                        try {
                            return decisionService.decideHomeCommercial();
                        } finally {
                            SecurityContextHolder.clearContext();
                        }
                    }))
                    .toList();
            start.countDown();
            List<AdvertisingDecisionResponses.HomeCommercialDecision> results = futures.stream()
                .map(this::get)
                .toList();

            assertThat(results).allMatch(result -> result != null);
            assertThat(results).extracting(AdvertisingDecisionResponses.HomeCommercialDecision::decisionId)
                .containsOnly(results.get(0).decisionId());
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM advertising_decision WHERE user_id = ?", Long.class, USER_ID))
                .isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void tokenBindingIdempotencyAndExpiryAreEnforcedByRealMySqlFacts() {
        seedCommercial(1);
        enablePrivacy(USER_ID);
        authenticate(USER_ID);
        AdvertisingDecisionResponses.HomeCommercialDecision decision = decisionService.decideHomeCommercial();
        assertThat(decision).isNotNull();

        assertThat(eventService.recordEvent(decision.decisionId(), click(decision.token()), "click-1").resultStatus())
            .isEqualTo("ACCEPTED");
        assertThat(eventService.recordEvent(decision.decisionId(), click(decision.token()), "click-1").replayed())
            .isTrue();
        assertThat(eventService.recordEvent(decision.decisionId(), click(decision.token()), "click-2").rejectionCode())
            .isEqualTo("DUPLICATE_EVENT_TYPE");

        authenticate(OTHER_USER_ID);
        assertThatThrownBy(() -> eventService.recordEvent(decision.decisionId(), click(decision.token()), "cross-user"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ADVERTISING_TOKEN_INVALID");

        authenticate(USER_ID);
        jdbc.update("UPDATE advertising_decision SET expires_at = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND) WHERE id = ?",
            decision.decisionId());
        assertThatThrownBy(() -> eventService.recordEvent(decision.decisionId(), click(decision.token()), "expired"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ADVERTISING_TOKEN_EXPIRED");
    }

    @Test
    void committedSubmissionIsAttributedByLatestClickAndDuplicateCallbackIsStable() {
        seedCommercial(1);
        enablePrivacy(USER_ID);
        authenticate(USER_ID);
        AdvertisingDecisionResponses.HomeCommercialDecision decision = decisionService.decideHomeCommercial();
        assertThat(decision).isNotNull();
        assertThat(eventService.recordEvent(decision.decisionId(), click(decision.token()), "conversion-click").resultStatus())
            .isEqualTo("ACCEPTED");

        LocalDateTime clickOccurredAt = jdbc.queryForObject(
            "SELECT occurred_at FROM advertising_event WHERE user_id = ? AND event_type = 'CLICK'",
            LocalDateTime.class, USER_ID);
        LocalDateTime conversionAt = clickOccurredAt.plusSeconds(1);
        AdvertisingConversionService.ConversionCommand command = new AdvertisingConversionService.ConversionCommand(
            "RESERVATION_SUBMITTED:990701", "RESERVATION_SUBMITTED", 990701L, USER_ID, 990701L,
            "PENDING_REVIEW", conversionAt);
        conversionService.accept(command);
        conversionService.accept(command);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM advertising_conversion_callback WHERE event_key = ?", Long.class,
            command.eventKey())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM advertising_attribution WHERE reservation_id = ?", Long.class,
            command.reservationId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM advertising_attribution WHERE reservation_id = ?", String.class,
            command.reservationId())).isEqualTo(AdvertisingConversionService.ATTRIBUTED);
        assertThat(jdbc.queryForObject(
            "SELECT result_code FROM advertising_conversion_callback WHERE event_key = ?", String.class,
            command.eventKey())).isEqualTo("ATTRIBUTED");
    }

    @Test
    void canceledUnattributedSubmissionKeepsTheLifecycleGapWithoutTouchingReservationTables() {
        enablePrivacy(USER_ID);
        authenticate(USER_ID);
        AdvertisingConversionService.ConversionCommand submission = new AdvertisingConversionService.ConversionCommand(
            "RESERVATION_SUBMITTED:990702", "RESERVATION_SUBMITTED", 990702L, USER_ID, 990702L,
            "PENDING_REVIEW", LocalDateTime.now());
        conversionService.accept(submission);

        AdvertisingConversionService.ConversionCommand revoked = new AdvertisingConversionService.ConversionCommand(
            "RESERVATION_STATUS:990702:PENDING_REVIEW:CANCELED:1", "RESERVATION_REVOKED", 990702L,
            USER_ID, 990702L, "CANCELED", LocalDateTime.now());
        conversionService.accept(revoked);

        assertThat(jdbc.queryForObject(
            "SELECT status FROM advertising_attribution WHERE reservation_id = ?", String.class,
            revoked.reservationId())).isEqualTo(AdvertisingConversionService.GAP);
        assertThat(jdbc.queryForObject(
            "SELECT result_code FROM advertising_conversion_callback WHERE event_key = ?", String.class,
            revoked.eventKey())).isEqualTo("UNATTRIBUTED_LIFECYCLE_GAP");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM reservation WHERE id = ?", Long.class, revoked.reservationId()))
            .isZero();
    }

    private void seedCommercial(int creativeCount) {
        LocalDateTime from = LocalDateTime.now().minusMinutes(5);
        LocalDateTime until = LocalDateTime.now().plusHours(2);
        jdbc.update("""
            INSERT INTO advertising_advertiser
              (id, advertiser_no, advertiser_name, status, created_by)
            VALUES (?, 'S03-ADVERTISER', 'S03 隔离广告主', 'ACTIVE', 1)
            """, ADVERTISER_ID);
        jdbc.update("""
            INSERT INTO advertising_campaign
              (id, campaign_no, advertiser_id, campaign_name, effective_from, effective_until,
               status, created_by, reviewed_by, published_by, reviewed_at, published_at)
            VALUES (?, 'S03-CAMPAIGN', ?, 'S03 隔离计划', ?, ?, 'PUBLISHED', 1, 2, 2, ?, ?)
            """, CAMPAIGN_ID, ADVERTISER_ID, from, until, from, from);
        for (int index = 0; index < creativeCount; index++) {
            jdbc.update("""
                INSERT INTO advertising_creative_version
                  (id, creative_no, campaign_id, version_no, headline, body_text,
                   call_to_action, target_type, target_id, status, created_by,
                   reviewed_by, published_by, reviewed_at, published_at)
                VALUES (?, ?, ?, ?, ?, 'S03 隔离正文', 'VIEW', 'VENUE_DETAIL', ?,
                        'PUBLISHED', 1, 2, 2, ?, ?)
                """, CREATIVE_BASE_ID + index, "S03-CREATIVE-" + index, CAMPAIGN_ID,
                index + 1, "S03 标题 " + index, 100L + index, from, from);
        }
    }

    private void seedR6AvailableCandidate() {
        LocalDateTime from = LocalDateTime.now().minusMinutes(1);
        LocalDateTime until = LocalDateTime.now().plusHours(1);
        jdbc.update("""
            INSERT INTO notice
              (id, title, content, type, target_venue_id, status, publish_time,
               create_time, update_time, deleted, target_rule_json)
            VALUES (?, 'R6 隔离自营', '<p>自营摘要</p>', 'GLOBAL', NULL, 'PUBLISHED',
                    ?, ?, ?, 0,
                    '{"targetScope":"ALL_USERS"}')
            """, NOTICE_ID, from, from, from);
        jdbc.update("""
            INSERT INTO placement_candidate_version
              (id, slot_id, candidate_no, source_type, source_id, source_version,
               effective_from, effective_until, audience_type, priority,
               frequency_cap_per_user, frequency_cap_window_hours, rule_version, status)
            SELECT ?, id, 'S03-R6-CANDIDATE', 'GLOBAL_NOTICE', ?, 'S03-R6',
                   ?, ?, 'ALL_AUTHENTICATED',
                   99, 1, 24, 'PLACEMENT_RULE_S03_TEST', 'PUBLISHED'
            FROM placement_slot WHERE slot_code = 'HOME_PRIMARY'
            """, R6_CANDIDATE_ID, NOTICE_ID, from, until);
    }

    private void enablePrivacy(long userId) {
        jdbc.update("""
            INSERT INTO advertising_privacy_preference
              (user_id, commercial_enabled, measurement_enabled, policy_version, changed_at)
            VALUES (?, 1, 1, 'G-R7-20260803-v1', CURRENT_TIMESTAMP)
            """, userId);
    }

    private AdvertisingDecisionRequests.RecordEventRequest viewable(String token) {
        return new AdvertisingDecisionRequests.RecordEventRequest(
            AdvertisingEventService.CLIENT_VIEWABLE, token, 1.0, 1000);
    }

    private AdvertisingDecisionRequests.RecordEventRequest click(String token) {
        return new AdvertisingDecisionRequests.RecordEventRequest(
            AdvertisingEventService.CLICK, token, null, null);
    }

    private void authenticate(long userId) {
        CurrentUser principal = new CurrentUser(userId, "s03-user-" + userId, Set.of("USER"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private <T> T get(java.util.concurrent.Future<T> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
