package com.example.venue.funnel;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.common.CurrentUser;
import com.example.venue.funnel.dto.FunnelOverviewQuery;
import com.example.venue.funnel.dto.FunnelOverviewResponse;
import com.example.venue.funnel.dto.FunnelStepMeasurement;
import com.example.venue.funnel.dto.FunnelStepMetric;
import com.example.venue.funnel.mapper.FunnelEventMapper;
import com.example.venue.funnel.service.FunnelAnalyticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 漏斗 cohort 的数据库级证明（F-19 补证 + F-22 口径）：结论全部来自真实 MySQL 上执行的
 * `FunnelEventMapper.selectCohortStepCounts`，不用 mock 替代会话交集、场馆隔离与过滤语义。
 *
 * 种子的七条会话刻意各自停在不同步骤，并且搜索两步按真实事实不带场馆归属：
 * s1 到预约提交、s2 到价格预览、s3 到时段选择、s4 是乙馆全程、s5 只有搜索、
 * s6 是甲馆 H5 全程、s7 在查询窗口之外，另加 s8 从详情直接起步。
 */
// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(properties = {
    "spring.profiles.active=test",
    "spring.task.scheduling.enabled=false",
    "app.notice.outbox.scheduling-enabled=false",
    "app.community.retention.enabled=false"
})
class FunnelCohortMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("funnel_cohort_test")
        .withUsername("funnel")
        .withPassword("funnel")
        .withCommand("--character-set-server=utf8mb4",
                     "--collation-server=utf8mb4_unicode_ci",
                     "--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 6398);
    }

    private static final LocalDate START_DATE = LocalDate.of(2026, 7, 1);
    private static final LocalDate END_DATE = LocalDate.of(2026, 7, 7);
    private static final LocalDateTime DAY = START_DATE.atTime(10, 0);
    private static final long VENUE_A = 901L;
    private static final long VENUE_B = 902L;
    private static final long VENUE_ADMIN_USER = 777L;

    private static final String[] NINE_STEPS = {
        "SEARCH_SUBMITTED", "SEARCH_RESULT_VIEWED", "VENUE_DETAIL_VIEWED", "SLOT_SELECTED",
        "PRICE_PREVIEW_SHOWN", "RESERVATION_SUBMITTED", "ORDER_CREATED", "PAYMENT_INITIATED",
        "PAYMENT_RESULT_RECORDED"
    };
    private static final String[] VENUE_CHAIN = {
        "VENUE_DETAIL_VIEWED", "SLOT_SELECTED", "PRICE_PREVIEW_SHOWN", "RESERVATION_SUBMITTED",
        "ORDER_CREATED", "PAYMENT_INITIATED", "PAYMENT_RESULT_RECORDED"
    };

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FunnelEventMapper eventMapper;
    @Autowired private FunnelAnalyticsService analyticsService;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM funnel_event");
        jdbc.update("DELETE FROM venue_admin_relation WHERE user_id = " + VENUE_ADMIN_USER);
        jdbc.update("DELETE FROM venue WHERE id IN (" + VENUE_A + ", " + VENUE_B + ")");
        insertVenue(VENUE_A, "FC 甲馆");
        insertVenue(VENUE_B, "FC 乙馆");
        jdbc.update("INSERT INTO venue_admin_relation (user_id, venue_id) VALUES (?, ?)",
            VENUE_ADMIN_USER, VENUE_A);

        journey("fc-s1", VENUE_A, "WEB", DAY, "RESERVATION_SUBMITTED");
        journey("fc-s2", VENUE_A, "WEB", DAY.plusMinutes(5), "PRICE_PREVIEW_SHOWN");
        journey("fc-s3", VENUE_A, "WEB", DAY.plusMinutes(10), "SLOT_SELECTED");
        journey("fc-s4", VENUE_B, "WEB", DAY.plusMinutes(15), "PAYMENT_RESULT_RECORDED");
        journey("fc-s5", null, "WEB", DAY.plusMinutes(20), "SEARCH_RESULT_VIEWED");
        journey("fc-s6", VENUE_A, "H5", DAY.plusMinutes(25), "PAYMENT_RESULT_RECORDED");
        journey("fc-s7", VENUE_A, "WEB", DAY.plusDays(30), "PAYMENT_RESULT_RECORDED");
        journeyFrom("fc-s8", "VENUE_DETAIL_VIEWED", VENUE_A, "WEB", DAY.plusMinutes(35), "ORDER_CREATED");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void platformCohortReturnsNineMonotonicSteps() {
        Map<String, Long> counts = countsByEvent(cohort(null, null, false));

        assertThat(eventNames(cohort(null, null, false))).containsExactly(NINE_STEPS);
        // 平台口径以搜索为锚点：6 条会话带搜索（fc-s8 没有搜索、fc-s7 在窗口外），
        // 交集逐步收敛：详情与选时段 5、价格预览 4、预约提交 3（s2 停在预览）、订单起 2。
        assertThat(counts.get("SEARCH_SUBMITTED")).isEqualTo(6L);
        assertThat(counts.get("SEARCH_RESULT_VIEWED")).isEqualTo(6L);
        assertThat(counts.get("VENUE_DETAIL_VIEWED")).isEqualTo(5L);
        assertThat(counts.get("SLOT_SELECTED")).isEqualTo(5L);
        assertThat(counts.get("PRICE_PREVIEW_SHOWN")).isEqualTo(4L);
        assertThat(counts.get("RESERVATION_SUBMITTED")).isEqualTo(3L);
        assertThat(counts.get("ORDER_CREATED")).isEqualTo(2L);
        assertThat(counts.get("PAYMENT_INITIATED")).isEqualTo(2L);
        assertThat(counts.get("PAYMENT_RESULT_RECORDED")).isEqualTo(2L);

        List<Long> series = new ArrayList<>();
        for (String step : NINE_STEPS) {
            series.add(counts.get(step));
        }
        for (int i = 1; i < series.size(); i++) {
            assertThat(series.get(i)).as("逐步交集单调不增：%s", NINE_STEPS[i]).isLessThanOrEqualTo(series.get(i - 1));
        }
    }

    @Test
    void venueScopedCohortStartsAtVenueDetailAndIsolatesOtherVenues() {
        Map<String, Long> counts = countsByEvent(cohort(VENUE_A, null, true));

        // 场馆口径不再产出搜索两步，链从「场馆详情查看」起
        assertThat(eventNames(cohort(VENUE_A, null, true))).containsExactly(VENUE_CHAIN);
        // 甲馆自身：s1、s2、s3、s6、s8 五条详情；乙馆 s4 与无归属 s5 都不计入
        assertThat(counts.get("VENUE_DETAIL_VIEWED")).isEqualTo(5L);
        assertThat(counts.get("SLOT_SELECTED")).isEqualTo(5L);
        // s3 停在选时段，价格预览只剩 4 条
        assertThat(counts.get("PRICE_PREVIEW_SHOWN")).isEqualTo(4L);
        assertThat(counts.get("RESERVATION_SUBMITTED")).isEqualTo(3L);
        assertThat(counts.get("ORDER_CREATED")).isEqualTo(2L);
        assertThat(counts.get("PAYMENT_INITIATED")).isEqualTo(1L);
        assertThat(counts.get("PAYMENT_RESULT_RECORDED")).isEqualTo(1L);

        Map<String, Long> other = countsByEvent(cohort(VENUE_B, null, true));
        assertThat(other.get("VENUE_DETAIL_VIEWED")).isEqualTo(1L);
        assertThat(other.get("RESERVATION_SUBMITTED")).isEqualTo(1L);
        assertThat(other.get("PAYMENT_RESULT_RECORDED")).isEqualTo(1L);

        // 可管理集合与显式场馆过滤必须给出同一份甲馆数字
        Map<String, Long> viaSet = countsByEvent(cohort(null, List.of(VENUE_A), true));
        assertThat(viaSet).isEqualTo(counts);
    }

    @Test
    void sourceChannelAndDateFiltersNarrowTheVenueChain() {
        Map<String, Long> web = countsByEvent(cohort(VENUE_A, null, true, "WEB"));
        Map<String, Long> h5 = countsByEvent(cohort(VENUE_A, null, true, "H5"));

        assertThat(web.get("VENUE_DETAIL_VIEWED")).isEqualTo(4L);
        assertThat(web.get("PAYMENT_RESULT_RECORDED")).isZero();
        assertThat(h5.get("VENUE_DETAIL_VIEWED")).isEqualTo(1L);
        assertThat(h5.get("PAYMENT_RESULT_RECORDED")).isEqualTo(1L);

        // 只查 7 月 2 日起：7 月 1 日的甲馆会话与窗口外的 fc-s7 都不出现
        Map<String, Long> narrowed = countsByEvent(eventMapper.selectCohortStepCounts(
            START_DATE.plusDays(1).atStartOfDay(), END_DATE.plusDays(1).atStartOfDay(), VENUE_A, null, null, true));
        assertThat(narrowed.get("VENUE_DETAIL_VIEWED")).isZero();
    }

    @Test
    void measuredEmptyWindowStaysZeroAndOnlySearchStepsAreNotApplicable() {
        authenticate(VENUE_ADMIN_USER, Set.of("VENUE_ADMIN"));
        FunnelOverviewQuery query = new FunnelOverviewQuery();
        query.startDate = LocalDate.of(2026, 1, 1);
        query.endDate = LocalDate.of(2026, 1, 7);
        query.venueId = VENUE_A;

        Map<String, FunnelStepMetric> byEvent = byEventName(analyticsService.overview(query));

        assertThat(byEvent.get("SEARCH_SUBMITTED").measurement).isEqualTo(FunnelStepMeasurement.NOT_APPLICABLE);
        assertThat(byEvent.get("SEARCH_SUBMITTED").count).isNull();
        assertThat(byEvent.get("SEARCH_RESULT_VIEWED").conversionRate).isNull();
        FunnelStepMetric detail = byEvent.get("VENUE_DETAIL_VIEWED");
        // 真实零计数与「不适用」必须可区分：空窗口里七步都是 0，而不是 null
        assertThat(detail.measurement).isEqualTo(FunnelStepMeasurement.MEASURED);
        assertThat(detail.count).isZero();
        assertThat(detail.startStep).isTrue();
        assertThat(byEvent.get("PAYMENT_RESULT_RECORDED").count).isZero();
        assertThat(byEvent.get("PAYMENT_RESULT_RECORDED").conversionRate).isEqualByComparingTo("0.0000");
    }

    @Test
    void venueAdministratorGetsSevenMeasuredStepsWithoutPlatformTotals() {
        authenticate(VENUE_ADMIN_USER, Set.of("VENUE_ADMIN"));
        List<FunnelStepMetric> metrics = analyticsService.overview(queryWithoutVenue()).stepMetrics;

        assertThat(metrics).hasSize(9);
        assertThat(metrics.get(0).measurement).isEqualTo(FunnelStepMeasurement.NOT_APPLICABLE);
        assertThat(metrics.get(1).measurement).isEqualTo(FunnelStepMeasurement.NOT_APPLICABLE);
        // 场馆账号既拿不到平台搜索总量，也拿不到把它当 0 的假零转化
        assertThat(metrics.get(0).count).isNull();
        assertThat(metrics.get(1).count).isNull();
        assertThat(metrics).filteredOn(metric -> metric.count != null).hasSize(7);
        assertThat(metrics.get(2).startStep).isTrue();
        assertThat(metrics.get(2).count).isEqualTo(5L);
        assertThat(metrics.get(3).conversionRate).isEqualByComparingTo("1.0000");
        assertThat(metrics.get(5).conversionRate).isEqualByComparingTo("0.7500");
        assertThat(metrics.get(6).conversionRate).isEqualByComparingTo("0.6667");
        assertThat(metrics.get(8).count).isEqualTo(1L);
        assertThat(metrics).filteredOn(metric -> metric.startStep).hasSize(1);
    }

    @Test
    void platformAdministratorKeepsAllNineMeasuredSteps() {
        authenticate(1L, Set.of("SYSTEM_ADMIN"));
        List<FunnelStepMetric> metrics = analyticsService.overview(queryWithoutVenue()).stepMetrics;

        assertThat(metrics).hasSize(9);
        assertThat(metrics).allSatisfy(metric -> {
            assertThat(metric.measurement).isEqualTo(FunnelStepMeasurement.MEASURED);
            assertThat(metric.count).isNotNull();
        });
        assertThat(metrics.get(0).startStep).isTrue();
        assertThat(metrics).filteredOn(metric -> metric.startStep).hasSize(1);
        assertThat(metrics.get(2).conversionRate).isEqualByComparingTo("0.8333");
    }

    private FunnelOverviewQuery queryWithoutVenue() {
        FunnelOverviewQuery query = new FunnelOverviewQuery();
        query.startDate = START_DATE;
        query.endDate = END_DATE;
        return query;
    }

    private List<Map<String, Object>> cohort(Long venueId, List<Long> venueIds, boolean venueScoped) {
        return cohort(venueId, venueIds, venueScoped, null);
    }

    private List<Map<String, Object>> cohort(Long venueId, List<Long> venueIds, boolean venueScoped, String channel) {
        return eventMapper.selectCohortStepCounts(
            START_DATE.atStartOfDay(), END_DATE.plusDays(1).atStartOfDay(), venueId, venueIds, channel, venueScoped);
    }

    private void insertVenue(long id, String name) {
        jdbc.update("INSERT INTO venue (id, name, address, area, status, create_time, update_time, deleted) "
            + "VALUES (?, ?, '隔离环境', 'FC区', 'ACTIVE', NOW(), NOW(), 0)", id, name);
    }

    private void journey(String sessionKey, Long venueId, String channel, LocalDateTime at, String lastEvent) {
        journeyFrom(sessionKey, "SEARCH_SUBMITTED", venueId, channel, at, lastEvent);
    }

    /**
     * 按九步顺序写入同一会话的事件，到 {@code lastEvent} 为止。搜索两步不写场馆，
     * 详情及之后才带归属，与产品真实写入一致。
     */
    private void journeyFrom(String sessionKey, String firstEvent, Long venueId, String channel, LocalDateTime at,
                             String lastEvent) {
        boolean started = false;
        for (int index = 0; index < NINE_STEPS.length; index++) {
            String eventName = NINE_STEPS[index];
            started = started || eventName.equals(firstEvent);
            if (!started) {
                continue;
            }
            insertEvent(sessionKey, eventName, index, venueId, channel, at);
            if (eventName.equals(lastEvent)) {
                return;
            }
        }
        throw new IllegalStateException("会话 " + sessionKey + " 未到达 " + lastEvent);
    }

    private void insertEvent(String sessionKey, String eventName, int stepIndex, Long venueId, String channel,
                             LocalDateTime at) {
        boolean attributedToVenue = venueId != null && stepIndex >= 2;
        jdbc.update("INSERT INTO funnel_event (event_no, session_key, event_name, event_stage, source_channel, "
            + "provider_env, venue_id, occurred_at, deleted) VALUES (?, ?, ?, ?, ?, 'DEV', ?, ?, 0)",
            "FC-" + sessionKey + "-" + eventName, sessionKey, eventName, stageOf(eventName), channel,
            attributedToVenue ? venueId : null, at.plusMinutes(stepIndex));
    }

    private String stageOf(String eventName) {
        return switch (eventName) {
            case "SEARCH_SUBMITTED", "SEARCH_RESULT_VIEWED" -> "SEARCH";
            case "VENUE_DETAIL_VIEWED", "SLOT_SELECTED" -> "DETAIL";
            case "PRICE_PREVIEW_SHOWN" -> "PRICE";
            case "RESERVATION_SUBMITTED" -> "RESERVATION";
            case "ORDER_CREATED" -> "ORDER";
            default -> "PAYMENT";
        };
    }

    private List<String> eventNames(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> String.valueOf(row.get("eventName"))).toList();
    }

    private Map<String, Long> countsByEvent(List<Map<String, Object>> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put(String.valueOf(row.get("eventName")), ((Number) row.get("count")).longValue());
        }
        return counts;
    }

    private Map<String, FunnelStepMetric> byEventName(FunnelOverviewResponse response) {
        Map<String, FunnelStepMetric> byEvent = new LinkedHashMap<>();
        response.stepMetrics.forEach(metric -> byEvent.put(metric.eventName, metric));
        return byEvent;
    }

    private void authenticate(long userId, Set<String> roles) {
        CurrentUser user = new CurrentUser(userId, "fc-user-" + userId, roles, Set.of("funnel:analytics:view"));
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
