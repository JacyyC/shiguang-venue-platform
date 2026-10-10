package com.example.venue.funnel;

import com.example.venue.common.CurrentUser;
import com.example.venue.common.Result;
import com.example.venue.funnel.controller.FunnelAdminController;
import com.example.venue.funnel.dto.FunnelEventResponse;
import com.example.venue.funnel.dto.FunnelOverviewQuery;
import com.example.venue.funnel.dto.FunnelOverviewResponse;
import com.example.venue.funnel.dto.FunnelStepMeasurement;
import com.example.venue.funnel.entity.FunnelEvent;
import com.example.venue.funnel.mapper.FunnelEventMapper;
import com.example.venue.funnel.service.FunnelAnalyticsService;
import com.example.venue.venue.service.VenueAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunnelAnalyticsServiceTest {
    private static final LocalDate START_DATE = LocalDate.of(2026, 7, 1);
    private static final LocalDate END_DATE = LocalDate.of(2026, 7, 7);
    private static final LocalDateTime START_TIME = START_DATE.atStartOfDay();
    private static final LocalDateTime END_TIME = END_DATE.plusDays(1).atStartOfDay();

    @Mock
    private FunnelEventMapper eventMapper;
    @Mock
    private VenueAccessService venueAccessService;

    private FunnelAnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        analyticsService = new FunnelAnalyticsService(eventMapper, venueAccessService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void overviewCalculatesStepConversionAndDropoffReasons() {
        authenticate("admin", Set.of("SYSTEM_ADMIN"), Set.of("funnel:analytics:view"));
        FunnelOverviewQuery query = query();
        query.sourceChannel = "WEB";
        // AUD-P2-10 改 DC-01：cohort 在 SQL 端逐步 JOIN，Service 直接读 count（4,3,2,2,1,1,1,1,0）
        when(eventMapper.selectCohortStepCounts(eq(START_TIME), eq(END_TIME), isNull(), isNull(), eq("WEB"), eq(false)))
            .thenReturn(cohortCounts(4L, 3L, 2L, 2L, 1L, 1L, 1L, 1L, 0L));
        when(eventMapper.countByDropoffReason(START_TIME, END_TIME, null, null, "WEB")).thenReturn(List.of(
            row("reasonCode", "NO_SELLABLE_SLOT", "count", 3L),
            row("reasonCode", "PAYMENT_TIMEOUT", "count", 1L)
        ));
        when(eventMapper.countBySourceChannel(START_TIME, END_TIME, null, null, "WEB")).thenReturn(List.of(
            row("sourceChannel", "WEB", "count", 10L)
        ));
        when(eventMapper.topKeywords(START_TIME, END_TIME, null, null, "WEB")).thenReturn(List.of(
            row("keyword", "羽毛球", "count", 5L)
        ));

        FunnelOverviewResponse response = analyticsService.overview(query);

        assertThat(response.stepMetrics).extracting(metric -> metric.eventName).containsExactly(
            "SEARCH_SUBMITTED",
            "SEARCH_RESULT_VIEWED",
            "VENUE_DETAIL_VIEWED",
            "SLOT_SELECTED",
            "PRICE_PREVIEW_SHOWN",
            "RESERVATION_SUBMITTED",
            "ORDER_CREATED",
            "PAYMENT_INITIATED",
            "PAYMENT_RESULT_RECORDED");
        // AUD-P2-10 改 DC-01：数列 (4,3,2,2,1,1,1,1,0) 来自 SQL 端 cohort 逐步交集（s5 未经过首步被排除）
        assertThat(response.stepMetrics).extracting(metric -> metric.count)
            .containsExactly(4L, 3L, 2L, 2L, 1L, 1L, 1L, 1L, 0L);
        assertThat(response.stepMetrics).extracting(metric -> metric.conversionRate).containsExactly(
            new BigDecimal("1.0000"),
            new BigDecimal("0.7500"),
            new BigDecimal("0.6667"),
            new BigDecimal("1.0000"),
            new BigDecimal("0.5000"),
            new BigDecimal("1.0000"),
            new BigDecimal("1.0000"),
            new BigDecimal("1.0000"),
            new BigDecimal("0.0000"));
        assertThat(response.stepMetrics).allSatisfy(metric -> assertThat(metric.conversionRate.scale()).isEqualTo(4));
        assertThat(response.dropoffReasons).anySatisfy(reason -> {
            assertThat(reason.reasonCode).isEqualTo("NO_SELLABLE_SLOT");
            assertThat(reason.count).isEqualTo(3L);
        });
        assertThat(response.sourceChannels).singleElement().satisfies(source -> {
            assertThat(source.sourceChannel).isEqualTo("WEB");
            assertThat(source.count).isEqualTo(10L);
        });
        assertThat(response.topKeywords).containsExactly("羽毛球");
        assertThat(response.dataGaps).contains("真实支付网关未接入，支付结果来自 DEV/模拟支付链路。");
        assertThat(response.dataGaps).anyMatch(gap -> gap.contains("真实") && gap.contains("通知"));
        assertThat(response.dataGaps).anyMatch(gap -> gap.contains("外部广告") && gap.contains("归因"));
    }

    @Test
    void stepMetricsComputeCohortFromPriorStepSessions() {
        authenticate("admin", Set.of("SYSTEM_ADMIN"), Set.of("funnel:analytics:view"));
        // AUD-P2-10 改 DC-01：session-b 未经过 SEARCH_SUBMITTED，SQL 端 cohort 交集为空——
        // SEARCH_SUBMITTED=1（session-a），SEARCH_RESULT_VIEWED=0（session-b 不在前一步 cohort 内）
        when(eventMapper.selectCohortStepCounts(eq(START_TIME), eq(END_TIME), isNull(), isNull(), isNull(), eq(false)))
            .thenReturn(cohortCounts(1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L));

        FunnelOverviewResponse response = analyticsService.overview(query());

        assertThat(response.stepMetrics.get(0).count).isEqualTo(1L);
        assertThat(response.stepMetrics.get(0).conversionRate).isEqualByComparingTo("1.0000");
        // session-b 未经过 SEARCH_SUBMITTED，SQL 端 cohort 交集为空，转化率应为 0%
        assertThat(response.stepMetrics.get(1).count).isEqualTo(0L);
        assertThat(response.stepMetrics.get(1).conversionRate).isEqualByComparingTo("0.0000");
        assertThat(response.stepMetrics).allSatisfy(metric ->
            assertThat(metric.conversionRate).isLessThanOrEqualTo(new BigDecimal("1.0000")));
    }

    @Test
    void eventCountQueryDeduplicatesSessions() {
        String sql = new FunnelEventMapper.SqlProvider().countByEventName(new LinkedHashMap<>());

        assertThat(sql).contains("count(distinct session_key)");
    }

    @Test
    void cohortSqlKeepsNineStepsForPlatformScope() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("venueScoped", false);

        String sql = new FunnelEventMapper.SqlProvider().selectCohortStepCounts(params);

        assertThat(sql).startsWith("with step1_sessions as");
        assertThat(countOccurrences(sql, " union all ")).isEqualTo(8);
        assertThat(sql).contains("'SEARCH_SUBMITTED' as eventName");
        assertThat(sql).contains("'PAYMENT_RESULT_RECORDED' as eventName");
    }

    @Test
    void cohortSqlStartsVenueChainAtVenueDetailAndKeepsScopeFilters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("venueScoped", true);
        params.put("venueId", 20L);
        params.put("sourceChannel", "WEB");

        String sql = new FunnelEventMapper.SqlProvider().selectCohortStepCounts(params);

        // 场馆口径：前两步不再产出计数，交集链从场馆详情开始
        assertThat(sql).doesNotContain("'SEARCH_SUBMITTED' as eventName");
        assertThat(sql).doesNotContain("'SEARCH_RESULT_VIEWED' as eventName");
        assertThat(sql).contains("'VENUE_DETAIL_VIEWED' as eventName");
        assertThat(countOccurrences(sql, " union all ")).isEqualTo(6);
        assertThat(sql).contains("venue_id = #{venueId}");
        assertThat(sql).contains("source_channel = #{sourceChannel}");
        assertThat(sql).contains("join step3_sessions s on f.session_key = s.session_key");
        assertThat(sql).contains("session_key is not null");
    }

    private long countOccurrences(String text, String needle) {
        int hits = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            hits++;
        }
        return hits;
    }

    @Test
    void overviewRestrictsVenueAdminToManageableVenues() {
        authenticate("venue_admin", Set.of("VENUE_ADMIN"), Set.of("funnel:analytics:view"));
        FunnelOverviewQuery query = query();
        List<Long> manageableVenueIds = List.of(10L, 20L);
        when(venueAccessService.manageableVenueIds()).thenReturn(manageableVenueIds);
        // F-22：场馆管理员是场馆口径，SQL 只产出从「场馆详情查看」起的七步；前两步不再返回计数
        when(eventMapper.selectCohortStepCounts(eq(START_TIME), eq(END_TIME), isNull(), eq(manageableVenueIds),
            isNull(), eq(true))).thenReturn(venueCohortCounts(5L, 4L, 3L, 3L, 3L, 2L, 0L));

        FunnelOverviewResponse response = analyticsService.overview(query);

        assertThat(response.stepMetrics).hasSize(9);
        assertThat(response.stepMetrics.subList(0, 2)).allSatisfy(metric -> {
            assertThat(metric.measurement).isEqualTo(FunnelStepMeasurement.NOT_APPLICABLE);
            assertThat(metric.count).isNull();
            assertThat(metric.conversionRate).isNull();
            assertThat(metric.notApplicableReason).isNotBlank();
        });
        assertThat(response.stepMetrics.get(2).measurement).isEqualTo(FunnelStepMeasurement.MEASURED);
        assertThat(response.stepMetrics.get(2).startStep).isTrue();
        assertThat(response.stepMetrics.get(2).count).isEqualTo(5L);
        assertThat(response.stepMetrics.get(3).count).isEqualTo(4L);
        // 相邻转化以上一个可计量步骤为分母，而不是被不适用的前两步稀释
        assertThat(response.stepMetrics.get(3).conversionRate).isEqualByComparingTo("0.8000");
        // 真实零计数仍然表达为 0，不与「不适用」混淆
        assertThat(response.stepMetrics.get(8).count).isZero();
        assertThat(response.stepMetrics.get(8).measurement).isEqualTo(FunnelStepMeasurement.MEASURED);
        assertThat(response.dataGaps).anyMatch(gap -> gap.contains("场馆口径") && gap.contains("不适用"));
        verify(venueAccessService).manageableVenueIds();
        verify(venueAccessService, never()).requireVenueManageAccess(org.mockito.ArgumentMatchers.any());
        verify(eventMapper).selectCohortStepCounts(eq(START_TIME), eq(END_TIME), isNull(), eq(manageableVenueIds),
            isNull(), eq(true));
        verify(eventMapper).countByDropoffReason(START_TIME, END_TIME, null, manageableVenueIds, null);
        verify(eventMapper).countBySourceChannel(START_TIME, END_TIME, null, manageableVenueIds, null);
        verify(eventMapper).topKeywords(START_TIME, END_TIME, null, manageableVenueIds, null);
    }

    @Test
    void overviewRequiresManageAccessWhenVenueIdIsPresent() {
        authenticate("venue_admin", Set.of("VENUE_ADMIN"), Set.of("funnel:analytics:view"));
        FunnelOverviewQuery query = query();
        query.venueId = 20L;
        when(eventMapper.selectCohortStepCounts(eq(START_TIME), eq(END_TIME), eq(20L), isNull(), isNull(), eq(true)))
            .thenReturn(venueCohortCounts(6L, 4L, 4L, 2L, 2L, 1L, 1L));

        FunnelOverviewResponse response = analyticsService.overview(query);

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(venueAccessService, never()).manageableVenueIds();
        // F-22：显式场馆过滤同样是场馆口径，SQL 从场馆详情起步
        verify(eventMapper).selectCohortStepCounts(eq(START_TIME), eq(END_TIME), eq(20L), isNull(), isNull(), eq(true));
        assertThat(response.stepMetrics).extracting(metric -> metric.measurement)
            .containsExactly(
                FunnelStepMeasurement.NOT_APPLICABLE, FunnelStepMeasurement.NOT_APPLICABLE,
                FunnelStepMeasurement.MEASURED, FunnelStepMeasurement.MEASURED, FunnelStepMeasurement.MEASURED,
                FunnelStepMeasurement.MEASURED, FunnelStepMeasurement.MEASURED, FunnelStepMeasurement.MEASURED,
                FunnelStepMeasurement.MEASURED);
        // 起点是本场馆详情，不是借来的平台搜索总量
        assertThat(response.stepMetrics.get(2).startStep).isTrue();
        assertThat(response.stepMetrics.get(2).conversionRate).isEqualByComparingTo("1.0000");
        assertThat(response.stepMetrics.get(4).conversionRate).isEqualByComparingTo("1.0000");
        assertThat(response.stepMetrics.get(5).conversionRate).isEqualByComparingTo("0.5000");
        assertThat(response.dataGaps).anyMatch(gap -> gap.contains("场馆口径"));
    }

    @Test
    void platformScopeMeasuresAllNineStepsWithoutVenueGapNote() {
        authenticate("admin", Set.of("SYSTEM_ADMIN"), Set.of("funnel:analytics:view"));
        when(eventMapper.selectCohortStepCounts(eq(START_TIME), eq(END_TIME), isNull(), isNull(), isNull(), eq(false)))
            .thenReturn(cohortCounts(9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L));

        FunnelOverviewResponse response = analyticsService.overview(query());

        assertThat(response.stepMetrics).allSatisfy(metric -> {
            assertThat(metric.measurement).isEqualTo(FunnelStepMeasurement.MEASURED);
            assertThat(metric.count).isNotNull();
        });
        assertThat(response.stepMetrics.get(0).startStep).isTrue();
        assertThat(response.stepMetrics).filteredOn(metric -> metric.startStep).hasSize(1);
        assertThat(response.dataGaps).noneMatch(gap -> gap.contains("场馆口径"));
    }

    @Test
    void samplesReturnMaskedFieldsOnly() {
        authenticate("admin", Set.of("SYSTEM_ADMIN"), Set.of("funnel:analytics:view"));
        FunnelOverviewQuery query = query();
        query.sourceChannel = "web";
        FunnelEvent event = event("SEARCH_SUBMITTED", "SEARCH");
        event.sessionKey = "raw-session-key";
        event.visitorIdHash = "raw-visitor-hash";
        event.keywordHash = "raw-keyword-hash";
        event.metadataJson = "{\"rawUserAgent\":\"Mozilla/5.0\"}";
        when(eventMapper.selectAnalyticsSamples(START_TIME, END_TIME, null, null, null, "WEB", 20))
            .thenReturn(List.of(event));

        List<FunnelEventResponse> samples = analyticsService.samples(query);

        assertThat(samples).singleElement().satisfies(sample -> {
            assertThat(sample.id).isEqualTo(501L);
            assertThat(sample.eventName).isEqualTo("SEARCH_SUBMITTED");
            assertThat(sample.eventStage).isEqualTo("SEARCH");
            assertThat(sample.resultStatus).isEqualTo("SUCCESS");
            assertThat(sample.venueId).isEqualTo(10L);
            assertThat(sample.occurredAt).isEqualTo(START_TIME.plusHours(1));
        });
        assertThat(List.of(FunnelEventResponse.class.getDeclaredFields()))
            .extracting(Field::getName)
            .doesNotContain("sessionKey", "visitorIdHash", "keywordHash", "metadataJson", "userAgent", "deviceFingerprint");
        verify(eventMapper).selectAnalyticsSamples(START_TIME, END_TIME, null, null, null, "WEB", 20);
    }

    @Test
    void adminEndpointsUseExpectedRoutesPermissionsAndDelegateToService() throws Exception {
        Method overview = FunnelAdminController.class.getDeclaredMethod("overview", FunnelOverviewQuery.class);
        assertThat(overview.getAnnotation(GetMapping.class).value()).containsExactly("/api/admin/funnel/overview");
        assertThat(overview.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('funnel:analytics:view')");

        Method samples = FunnelAdminController.class.getDeclaredMethod("samples", FunnelOverviewQuery.class);
        assertThat(samples.getAnnotation(GetMapping.class).value()).containsExactly("/api/admin/funnel/samples");
        assertThat(samples.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('funnel:analytics:view')");

        FunnelAnalyticsService service = mock(FunnelAnalyticsService.class);
        FunnelAdminController controller = new FunnelAdminController(service);
        FunnelOverviewQuery query = query();
        FunnelOverviewResponse overviewResponse = new FunnelOverviewResponse();
        FunnelEventResponse sample = new FunnelEventResponse();
        when(service.overview(query)).thenReturn(overviewResponse);
        when(service.samples(query)).thenReturn(List.of(sample));

        Result<FunnelOverviewResponse> overviewResult = controller.overview(query);
        Result<List<FunnelEventResponse>> samplesResult = controller.samples(query);

        assertThat(overviewResult.getData()).isSameAs(overviewResponse);
        assertThat(samplesResult.getData()).containsExactly(sample);
        verify(service).overview(query);
        verify(service).samples(query);
    }

    private FunnelOverviewQuery query() {
        FunnelOverviewQuery query = new FunnelOverviewQuery();
        query.startDate = START_DATE;
        query.endDate = END_DATE;
        return query;
    }

    private void authenticate(String username, Set<String> roles, Set<String> permissions) {
        CurrentUser user = new CurrentUser(1L, username, roles, permissions);
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private Map<String, Object> row(Object... entries) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            row.put((String) entries[i], entries[i + 1]);
        }
        return row;
    }

    private FunnelEvent event(String eventName, String eventStage) {
        FunnelEvent event = new FunnelEvent();
        event.id = 501L;
        event.eventName = eventName;
        event.eventStage = eventStage;
        event.resultStatus = "SUCCESS";
        event.venueId = 10L;
        event.occurredAt = START_TIME.plusHours(1);
        event.deleted = 0;
        return event;
    }

    // AUD-P2-10 改 DC-01：cohort 计数 mock——SQL 端 selectCohortStepCounts 返回 Map 行（eventName/count），
    // 按 EVENT_ORDER 顺序提供 count；与 Service 端 countByEvent 解析逻辑对应。
    private List<Map<String, Object>> cohortCounts(long... counts) {
        String[] events = {
            "SEARCH_SUBMITTED", "SEARCH_RESULT_VIEWED", "VENUE_DETAIL_VIEWED",
            "SLOT_SELECTED", "PRICE_PREVIEW_SHOWN", "RESERVATION_SUBMITTED",
            "ORDER_CREATED", "PAYMENT_INITIATED", "PAYMENT_RESULT_RECORDED"
        };
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < events.length; i++) {
            rows.add(row("eventName", events[i], "count", counts[i]));
        }
        return rows;
    }

    /** F-22：场馆口径的 SQL 返回行——从「场馆详情查看」起的七步，前两步根本不出现。 */
    private List<Map<String, Object>> venueCohortCounts(long... counts) {
        String[] events = {
            "VENUE_DETAIL_VIEWED", "SLOT_SELECTED", "PRICE_PREVIEW_SHOWN", "RESERVATION_SUBMITTED",
            "ORDER_CREATED", "PAYMENT_INITIATED", "PAYMENT_RESULT_RECORDED"
        };
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < events.length; i++) {
            rows.add(row("eventName", events[i], "count", counts[i]));
        }
        return rows;
    }
}
