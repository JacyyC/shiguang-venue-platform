package com.example.venue.performance;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.VenueReservationApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
    classes = VenueReservationApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.security.rate-limit-per-minute=10000",
        "app.provider.observability.scheduled-enabled=false",
        "app.community.retention.enabled=false",
        "app.community.activity.notification.initial-delay-ms=3600000",
        "app.community.activity.reconciliation.initial-delay-ms=3600000",
        "app.community.activity.expiry.initial-delay-ms=3600000",
        "app.notice.outbox.poll-delay-ms=3600000",
        "spring.datasource.hikari.maximum-pool-size=48",
        "logging.level.com.example.venue=ERROR"
    }
)
class WaveAScalePerformanceIT {
    private static final int CONCURRENCY = 100;
    private static final long VENUE_BASE = 1_000_000L;
    private static final long SPACE_BASE = 2_000_000L;
    private static final long GROUP_BASE = 6_000_000L;
    private static final long POST_BASE = 7_000_000L;
    private static final long SEAT_BASE = 4_000_000L;
    private static final long APPLICATION_BASE = 9_200_000L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("wave_a_scale")
        .withUsername("wave_a")
        .withPassword("wave_a")
        .withEnv("TZ", "Asia/Shanghai")
        .withCommand("--character-set-server=utf8mb4",
                     "--collation-server=utf8mb4_unicode_ci",
                     "--log-bin-trust-function-creators=1");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("m.daocloud.io/docker.io/library/redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            String jdbcUrl = MYSQL.getJdbcUrl();
            String separator = jdbcUrl.contains("?") ? "&" : "?";
            return jdbcUrl + separator
                + "connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true";
        });
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired @Qualifier("activityNotificationTaskExecutor")
    private ThreadPoolTaskExecutor notificationExecutor;
    @Autowired @Qualifier("advertisingConversionTaskExecutor")
    private ThreadPoolTaskExecutor conversionExecutor;

    // Prewarm side effects must finish before measurement and before container teardown,
    // including when an earlier assertion fails. Preserve all workload and latency gates.
    @AfterEach
    void awaitBackgroundTasks() throws InterruptedException {
        System.out.printf("WAVE_A_BACKGROUND notification_active=%d notification_queued=%d conversion_active=%d conversion_queued=%d%n",
            notificationExecutor.getActiveCount(), notificationExecutor.getThreadPoolExecutor().getQueue().size(),
            conversionExecutor.getActiveCount(), conversionExecutor.getThreadPoolExecutor().getQueue().size());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (notificationExecutor.getActiveCount() == 0 && notificationExecutor.getThreadPoolExecutor().getQueue().isEmpty()
                && conversionExecutor.getActiveCount() == 0 && conversionExecutor.getThreadPoolExecutor().getQueue().isEmpty()) return;
            Thread.sleep(20L);
        }
        assertThat(notificationExecutor.getActiveCount()).isZero();
        assertThat(notificationExecutor.getThreadPoolExecutor().getQueue()).isEmpty();
        assertThat(conversionExecutor.getActiveCount()).isZero();
        assertThat(conversionExecutor.getThreadPoolExecutor().getQueue()).isEmpty();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void acceptedScaleMeetsQueryPlanAndConcurrentApiP95Gates() throws Exception {
        loadScaleFixture();
        verifyAcceptedScale();
        verifyQueryPlans();
        verifyPostExecutionPlan();

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        String token = login(client);

        List<String> listPaths = listPaths();
        warmUp(client, token, listPaths);
        awaitBackgroundTasks();
        awaitNotificationDispatches(CONCURRENCY);

        List<TimedResponse> listResponses = runConcurrently(
            index -> authenticatedGet(listPaths.get(index % listPaths.size()), token), client);
        assertSuccessful("list/search", listResponses);
        printEndpointP95(listResponses);
        long listP95 = p95Millis(listResponses);
        assertThat(listP95).as("Wave A list/search p95").isLessThanOrEqualTo(800L);

        LocalDateTime inventoryStart = LocalDate.now().plusDays(30).atTime(9, 0);
        LocalDateTime inventoryEnd = inventoryStart.plusHours(1);
        List<TimedResponse> inventoryResponses = runConcurrently(index -> authenticatedGet(
            "/api/reservations/resource-availability?spaceId=" + scaleWriteSpaceId(index)
                + "&startTime=" + encode(inventoryStart.toString())
                + "&endTime=" + encode(inventoryEnd.toString())
                + "&participantCount=1",
            token), client);
        assertSuccessful("inventory", inventoryResponses);
        long inventoryP95 = p95Millis(inventoryResponses);
        System.out.printf("WAVE_A_INVENTORY_P95 p95_ms=%d requests=%d%n",
            inventoryP95, inventoryResponses.size());
        assertThat(inventoryP95).as("Wave A inventory p95").isLessThanOrEqualTo(1000L);

        LocalDateTime writeStart = LocalDate.now().plusDays(31).atTime(18, 0);
        LocalDateTime writeEnd = writeStart.plusHours(1);
        Map<String, SqlTiming> sqlBefore = sqlTimings();
        List<TimedResponse> writeResponses = runConcurrently(index -> authenticatedReservationPost(
            scaleWriteSpaceId(index), writeStart, writeEnd, index, token), client);
        printSqlDelta(sqlBefore, sqlTimings());
        assertSuccessful("reservation write", writeResponses);
        long writeP95 = p95Millis(writeResponses);
        System.out.printf("WAVE_A_RESERVATION_WRITE_P95 p95_ms=%d requests=%d%n",
            writeP95, writeResponses.size());
        assertThat(writeP95).as("Wave A reservation write p95").isLessThanOrEqualTo(1500L);

        List<TimedResponse> quotaWriteResponses = runConcurrently(index ->
            authenticatedApplicationAccept(
                APPLICATION_BASE + CONCURRENCY + index + 1L, "measured-" + index, token), client);
        assertSuccessful("quota write", quotaWriteResponses);
        long quotaWriteP95 = p95Millis(quotaWriteResponses);
        System.out.printf("WAVE_A_QUOTA_WRITE_P95 p95_ms=%d requests=%d%n",
            quotaWriteP95, quotaWriteResponses.size());
        assertThat(quotaWriteP95).as("Wave A quota write p95").isLessThanOrEqualTo(1500L);

        assertThat(queryCount("reservation")).isEqualTo(2200L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM reservation_occupancy
            WHERE slot_date = ? AND start_time = ? AND end_time = ? AND active_flag = 1
            """, Long.class, writeStart.toLocalDate(), LocalTime.of(18, 0), LocalTime.of(19, 0)))
            .isEqualTo(100L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM reservation_occupancy_seat seat_allocation
            JOIN reservation_occupancy occupancy ON occupancy.id = seat_allocation.occupancy_id
            WHERE occupancy.slot_date = ?
              AND occupancy.start_time = ?
              AND occupancy.end_time = ?
              AND occupancy.active_flag = 1
            """, Long.class, writeStart.toLocalDate(), LocalTime.of(18, 0), LocalTime.of(19, 0)))
            .isEqualTo(50L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_activity_application
            WHERE id > ? AND id <= ? AND status = 'ACCEPTED'
            """, Long.class, APPLICATION_BASE + CONCURRENCY,
            APPLICATION_BASE + CONCURRENCY * 2L))
            .isEqualTo(100L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_activity_participation
            WHERE application_id > ? AND application_id <= ? AND status = 'ACTIVE'
            """, Long.class, APPLICATION_BASE + CONCURRENCY,
            APPLICATION_BASE + CONCURRENCY * 2L))
            .isEqualTo(100L);
        awaitNotificationDispatches(CONCURRENCY * 2L);

        System.out.printf(
            "WAVE_A_SCALE_P95 list_search_ms=%d inventory_ms=%d reservation_write_ms=%d "
                + "quota_write_ms=%d concurrency=%d%n",
            listP95, inventoryP95, writeP95, quotaWriteP95, CONCURRENCY);
    }

    private record SqlTiming(String sql, long calls, double seconds) {}

    private Map<String, SqlTiming> sqlTimings() throws Exception {
        Map<String, SqlTiming> result = new java.util.HashMap<>();
        // Read-only diagnostics on this class-owned container, never an application database.
        try (var connection = java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT DIGEST, DIGEST_TEXT, COUNT_STAR, SUM_TIMER_WAIT/1000000000000.0 AS seconds "
                 + "FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='wave_a_scale'")) {
            while (rows.next()) result.put(rows.getString(1), new SqlTiming(rows.getString(2), rows.getLong(3), rows.getDouble(4)));
        }
        return result;
    }

    private void printSqlDelta(Map<String, SqlTiming> before, Map<String, SqlTiming> after) {
        after.entrySet().stream().map(entry -> {
            SqlTiming end = entry.getValue();
            SqlTiming start = before.getOrDefault(entry.getKey(), new SqlTiming(end.sql(), 0, 0));
            return new SqlTiming(end.sql(), end.calls() - start.calls(), end.seconds() - start.seconds());
        }).filter(row -> row.calls() > 0).sorted(java.util.Comparator.comparingDouble(SqlTiming::seconds).reversed())
            .limit(20).forEach(row -> System.out.printf("WAVE_A_SQL calls=%d seconds=%.3f digest=%s%n",
                row.calls(), row.seconds(), row.sql()));
    }

    private void loadScaleFixture() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
            new ClassPathResource("wave-a-scale-fixture.sql"));
        populator.setContinueOnError(false);
        populator.execute(dataSource);
    }

    private void verifyAcceptedScale() {
        assertThat(queryCount("venue")).isEqualTo(5L);
        assertThat(queryCount("venue_space")).isEqualTo(200L);
        assertThat(queryCount("venue_resource_seat")).isEqualTo(5000L);
        assertThat(queryCount("sys_user")).isEqualTo(20000L);
        assertThat(queryCount("community_group")).isEqualTo(500L);
        assertThat(queryCount("community_post")).isEqualTo(100000L);
        assertThat(queryCount("community_comment")).isEqualTo(500000L);
        assertThat(queryCount("community_activity") + queryCount("community_companion_request"))
            .isEqualTo(10000L);
        assertThat(queryCount("community_activity_application")).isEqualTo(200L);
        assertThat(queryCount("reservation")).isEqualTo(2000L);
        long dailyCommunityWrites = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_post WHERE create_time >= CURRENT_DATE
            """, Long.class);
        assertThat(dailyCommunityWrites).isEqualTo(3000L);

        System.out.println(
            "WAVE_A_SCALE_COUNTS venues=5 resources=200 seats=5000 users=20000 groups=500 "
                + "posts=100000 comments=500000 activities_and_companions=10000 "
                + "daily_community_writes=3000 reservations=2000");
    }

    private void verifyQueryPlans() {
        assertIndexed("g", """
            EXPLAIN SELECT g.id
            FROM community_group g
            LEFT JOIN sys_user owner ON owner.id = g.owner_user_id AND owner.deleted = 0
            LEFT JOIN community_group_member mine ON mine.group_id = g.id AND mine.user_id = 1
            WHERE g.status = 'ACTIVE'
              AND g.name LIKE 'scale-group-%'
              AND NOT EXISTS (
                SELECT 1 FROM community_user_block b
                WHERE b.status = 'ACTIVE'
                  AND ((b.blocker_user_id = 1 AND b.blocked_user_id = g.owner_user_id)
                    OR (b.blocked_user_id = 1 AND b.blocker_user_id = g.owner_user_id))
              )
            ORDER BY g.id DESC LIMIT 51
            """);
        assertIndexed("p", """
            EXPLAIN SELECT p.id
            FROM community_post p
            JOIN community_group g ON g.id = p.group_id AND g.status = 'ACTIVE'
            LEFT JOIN community_group_member mine ON mine.group_id = g.id AND mine.user_id = 1
            LEFT JOIN sys_user author ON author.id = p.author_user_id AND author.deleted = 0
            WHERE p.status = 'PUBLISHED'
              AND (g.visibility = 'PLATFORM_VISIBLE' OR mine.status = 'ACTIVE')
              AND p.title LIKE 'scale-post-09%'
              AND NOT EXISTS (
                SELECT 1 FROM community_user_block b
                WHERE b.status = 'ACTIVE'
                  AND ((b.blocker_user_id = 1 AND b.blocked_user_id IN (p.author_user_id, g.owner_user_id))
                    OR (b.blocked_user_id = 1 AND b.blocker_user_id IN (p.author_user_id, g.owner_user_id)))
              )
            ORDER BY p.id DESC LIMIT 51
            """);
        assertIndexed("c", """
            EXPLAIN SELECT c.id
            FROM community_comment c
            WHERE c.post_id = 7000001 AND c.status = 'PUBLISHED'
            ORDER BY c.id DESC LIMIT 51
            """);
        assertIndexed("a", """
            EXPLAIN SELECT a.id
            FROM community_activity a
            JOIN community_group g ON g.id = a.group_id AND g.status = 'ACTIVE'
            WHERE a.status IN ('PUBLISHED', 'FULL')
              AND a.activity_type = 'STUDY'
              AND a.end_time > CURRENT_TIMESTAMP
            ORDER BY a.id DESC LIMIT 51
            """);
        assertIndexed("r", """
            EXPLAIN SELECT r.id
            FROM community_companion_request r
            JOIN community_group g ON g.id = r.group_id AND g.status = 'ACTIVE'
            WHERE r.status = 'PUBLISHED'
              AND r.activity_type = 'STUDY'
              AND r.window_end > CURRENT_TIMESTAMP
            ORDER BY r.id DESC LIMIT 51
            """);
        assertIndexed("o", """
            EXPLAIN SELECT o.id
            FROM reservation_occupancy o
            WHERE o.space_id = 2000001
              AND o.slot_date = DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY)
              AND o.active_flag = 1
              AND o.start_time < '10:00:00'
              AND o.end_time > '09:00:00'
            ORDER BY o.id
            """);
    }

    private void assertIndexed(String alias, String sql) {
        Map<String, Object> row = jdbcTemplate.queryForList(sql).stream()
            .filter(candidate -> alias.equals(Objects.toString(candidate.get("table"), "")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("EXPLAIN did not contain alias " + alias));
        String key = Objects.toString(row.get("key"), "");
        String accessType = Objects.toString(row.get("type"), "");
        assertThat(key).as("EXPLAIN index for %s: %s", alias, row).isNotBlank();
        assertThat(accessType).as("EXPLAIN access type for %s: %s", alias, row)
            .isNotEqualToIgnoringCase("ALL");
        System.out.printf("WAVE_A_QUERY_PLAN alias=%s key=%s type=%s rows=%s%n",
            alias, key, accessType, row.get("rows"));
    }

    private void verifyPostExecutionPlan() {
        String postQuery = """
            SELECT p.id, p.post_no, p.group_id, g.name AS group_name,
                   CASE WHEN author.id IS NULL THEN NULL ELSE p.author_user_id END AS author_user_id,
                   author.nickname AS author_nickname, author.avatar AS author_avatar,
                   p.title, p.content_text, p.status, p.version_no,
                   p.published_at, p.create_time, p.update_time
            FROM community_post p
            JOIN community_group g ON g.id = p.group_id AND g.status = 'ACTIVE'
            LEFT JOIN sys_user author ON author.id = p.author_user_id AND author.deleted = 0
            WHERE p.status = 'PUBLISHED'
              AND (g.visibility = 'PLATFORM_VISIBLE' OR EXISTS (
                SELECT 1 FROM community_group_member mine
                WHERE mine.group_id = g.id AND mine.user_id = 1 AND mine.status = 'ACTIVE'
              ))
              AND p.title LIKE 'scale-post-09%'
              AND NOT EXISTS (
                SELECT 1 FROM community_user_block outgoing
                WHERE outgoing.blocker_user_id = 1
                  AND outgoing.status = 'ACTIVE'
                  AND outgoing.blocked_user_id IN (p.author_user_id, g.owner_user_id)
              )
              AND NOT EXISTS (
                SELECT 1 FROM community_user_block incoming
                WHERE incoming.blocked_user_id = 1
                  AND incoming.status = 'ACTIVE'
                  AND incoming.blocker_user_id IN (p.author_user_id, g.owner_user_id)
              )
            ORDER BY p.id DESC LIMIT 51
            """;
        String plan = Objects.toString(jdbcTemplate.queryForObject(
            "EXPLAIN ANALYZE " + postQuery, Object.class), "");
        assertThat(plan).as("post ordered-limit execution plan")
            .contains("idx_community_post_cursor")
            .contains("(reverse)")
            .doesNotContain("Sort:");
        System.out.println(
            "WAVE_A_POST_EXECUTION_PLAN index=idx_community_post_cursor ordered_limit=true");
    }

    private void warmUp(HttpClient client, String token, List<String> listPaths) throws Exception {
        for (int round = 0; round < 3; round++) {
            for (String path : listPaths) {
                TimedResponse response = send(client, authenticatedGet(path, token));
                assertSuccessful("warm-up", List.of(response));
            }
        }
        LocalDateTime start = LocalDate.now().plusDays(30).atTime(9, 0);
        String path = "/api/reservations/resource-availability?spaceId=" + (SPACE_BASE + 1)
            + "&startTime=" + encode(start.toString())
            + "&endTime=" + encode(start.plusHours(1).toString())
            + "&participantCount=1";
        for (int round = 0; round < 3; round++) {
            assertSuccessful("inventory warm-up", List.of(send(client, authenticatedGet(path, token))));
        }
        LocalDateTime reservationStart = LocalDate.now().plusDays(32).atTime(8, 0);
        assertSuccessful("reservation write warm-up", runConcurrently(index ->
            authenticatedReservationPost(scaleWriteSpaceId(index), reservationStart,
                reservationStart.plusHours(1), CONCURRENCY + index, token), client));
        assertSuccessful("quota write warm-up", runConcurrently(index ->
            authenticatedApplicationAccept(
                APPLICATION_BASE + index + 1L, "warm-up-" + index, token), client));
    }

    private List<String> listPaths() {
        return List.of(
            "/api/community/groups?namePrefix=scale-group-&limit=50",
            "/api/community/posts?titlePrefix=scale-post-09&limit=50",
            "/api/community/activities?activityType=STUDY&limit=50",
            "/api/community/companion-requests?activityType=STUDY&limit=50"
        );
    }

    private String login(HttpClient client) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/auth/login"))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"admin\",\"password\":\"password\"}"))
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("code").asInt()).as(response.body()).isZero();
        String token = body.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private HttpRequest authenticatedGet(String path, String token) {
        return HttpRequest.newBuilder(uri(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    }

    private HttpRequest authenticatedReservationPost(Long spaceId,
                                                       LocalDateTime startTime,
                                                       LocalDateTime endTime,
                                                       int index,
                                                       String token) {
        long spaceOrdinal = spaceId - SPACE_BASE;
        String seatSelection = spaceOrdinal > 100
            ? ",\"seatIds\":[" + (SEAT_BASE + 1L + (spaceOrdinal - 101L) * 50L) + "]"
            : "";
        String body = "{"
            + "\"spaceId\":" + spaceId + ","
            + "\"startTime\":\"" + startTime + "\","
            + "\"endTime\":\"" + endTime + "\","
            + "\"participantCount\":1,"
            + "\"contactName\":\"Scale User\","
            + "\"contactPhone\":\"13800000000\","
            + "\"remark\":\"Wave A S08\""
            + seatSelection
            + "}";
        return HttpRequest.newBuilder(uri("/api/reservations"))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("X-Funnel-Session", "wave-a-scale-" + index)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    }

    private HttpRequest authenticatedApplicationAccept(long applicationId,
                                                       String idempotencySuffix,
                                                       String token) {
        return HttpRequest.newBuilder(uri("/api/community/applications/" + applicationId + "/accept"))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "wave-a-scale-quota-" + idempotencySuffix)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    }

    private long scaleWriteSpaceId(int index) {
        return SPACE_BASE + index * 2L + 1L;
    }

    private List<TimedResponse> runConcurrently(IntFunction<HttpRequest> requestFactory,
                                                 HttpClient client) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TimedResponse>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < CONCURRENCY; index++) {
                int requestIndex = index;
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent start");
                    }
                    return send(client, requestFactory.apply(requestIndex));
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<TimedResponse> responses = new ArrayList<>(CONCURRENCY);
            for (Future<TimedResponse> future : futures) {
                responses.add(future.get(45, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            start.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private TimedResponse send(HttpClient client, HttpRequest request) throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new TimedResponse(request.uri().getPath(), response.statusCode(), response.body(), elapsedMillis);
    }

    private void assertSuccessful(String label, List<TimedResponse> responses) throws Exception {
        for (TimedResponse response : responses) {
            assertThat(response.statusCode()).as("%s response: %s", label, response.body()).isEqualTo(200);
            assertThat(objectMapper.readTree(response.body()).path("code").asInt())
                .as("%s response: %s", label, response.body()).isZero();
        }
    }

    private long p95Millis(List<TimedResponse> responses) {
        List<Long> sorted = responses.stream()
            .map(TimedResponse::elapsedMillis)
            .sorted(Comparator.naturalOrder())
            .toList();
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95d) - 1);
        return sorted.get(index);
    }

    private void printEndpointP95(List<TimedResponse> responses) {
        responses.stream().map(TimedResponse::path).distinct().sorted().forEach(path -> {
            List<TimedResponse> endpointResponses = responses.stream()
                .filter(response -> path.equals(response.path()))
                .toList();
            System.out.printf("WAVE_A_ENDPOINT_P95 path=%s p95_ms=%d requests=%d%n",
                path, p95Millis(endpointResponses), endpointResponses.size());
        });
    }

    private long queryCount(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void awaitNotificationDispatches(long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        long total = 0;
        long settled = 0;
        do {
            total = queryCount("community_activity_notification_task");
            settled = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM community_activity_notification_task
                WHERE status IN ('DELIVERED', 'RETRY_WAITING', 'FAILED')
                """, Long.class);
            if (total == expected && settled == total) {
                System.out.printf("WAVE_A_NOTIFICATION_TASKS total=%d settled=%d%n", total, settled);
                return;
            }
            Thread.sleep(20L);
        } while (System.nanoTime() < deadline);
        assertThat(total).as("Wave A activity notification task count").isEqualTo(expected);
        assertThat(settled).as("Wave A settled activity notification task count").isEqualTo(total);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record TimedResponse(String path, int statusCode, String body, long elapsedMillis) {
    }
}
