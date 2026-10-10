package com.example.venue.funnel.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.Constants;
import com.example.venue.common.CurrentUser;
import com.example.venue.funnel.dto.FunnelDropoffMetric;
import com.example.venue.funnel.dto.FunnelEventResponse;
import com.example.venue.funnel.dto.FunnelOverviewQuery;
import com.example.venue.funnel.dto.FunnelOverviewResponse;
import com.example.venue.funnel.dto.FunnelSourceMetric;
import com.example.venue.funnel.dto.FunnelStepMeasurement;
import com.example.venue.funnel.dto.FunnelStepMetric;
import com.example.venue.funnel.entity.FunnelEvent;
import com.example.venue.funnel.mapper.FunnelEventMapper;
import com.example.venue.security.SecurityContext;
import com.example.venue.venue.service.VenueAccessService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FunnelAnalyticsService {
    private static final List<String> EVENT_ORDER = List.of(
        "SEARCH_SUBMITTED",
        "SEARCH_RESULT_VIEWED",
        "VENUE_DETAIL_VIEWED",
        "SLOT_SELECTED",
        "PRICE_PREVIEW_SHOWN",
        "RESERVATION_SUBMITTED",
        "ORDER_CREATED",
        "PAYMENT_INITIATED",
        "PAYMENT_RESULT_RECORDED");
    private static final Map<String, String> STEP_LABELS = Map.of(
        "SEARCH_SUBMITTED", "搜索提交",
        "SEARCH_RESULT_VIEWED", "搜索结果查看",
        "VENUE_DETAIL_VIEWED", "场馆详情查看",
        "SLOT_SELECTED", "时段选择",
        "PRICE_PREVIEW_SHOWN", "价格预览",
        "RESERVATION_SUBMITTED", "预约提交",
        "ORDER_CREATED", "订单创建",
        "PAYMENT_INITIATED", "支付发起",
        "PAYMENT_RESULT_RECORDED", "支付结果记录");
    private static final Map<String, String> DROPOFF_LABELS = Map.of(
        "NO_SELLABLE_SLOT", "无可售时段",
        "PAYMENT_TIMEOUT", "支付超时",
        "USER_CANCELLED", "用户取消",
        "PRICE_CHANGED", "价格变动",
        "COUPON_UNAVAILABLE", "优惠不可用");
    private static final List<String> DATA_GAPS = List.of(
        "真实支付网关未接入，支付结果来自 DEV/模拟支付链路。",
        "真实通知投递未接入，站内信/模拟记录不能代表短信、邮件、飞书或企业微信送达。",
        "外部广告归因未接入，sourceChannel/campaignId 仅代表站内或前端传入线索。");
    private static final Map<String, String> NOT_APPLICABLE_REASONS = Map.of(
        "SEARCH_SUBMITTED", "搜索提交发生在选定具体场馆之前，没有本场馆的归属事实；平台总量请用不带场馆过滤的查询。",
        "SEARCH_RESULT_VIEWED", "搜索结果查看同样不归属具体场馆，场馆口径从「场馆详情查看」开始计量。");
    private static final String VENUE_SCOPE_GAP = "场馆口径只计量从「场馆详情查看」起的会话交集；"
        + "搜索提交与搜索结果查看没有场馆归属，标为不适用而不是 0。";
    private static final BigDecimal ONE_RATE = new BigDecimal("1.0000");
    private static final BigDecimal ZERO_RATE = new BigDecimal("0.0000");
    private static final int SAMPLE_LIMIT = 20;

    private final FunnelEventMapper eventMapper;
    private final VenueAccessService venueAccessService;

    public FunnelAnalyticsService(FunnelEventMapper eventMapper, VenueAccessService venueAccessService) {
        this.eventMapper = eventMapper;
        this.venueAccessService = venueAccessService;
    }

    public FunnelOverviewResponse overview(FunnelOverviewQuery query) {
        QueryWindow window = normalizeWindow(query);
        Scope scope = resolveScope(query);
        String sourceChannel = normalizeSourceChannel(query);

        FunnelOverviewResponse response = new FunnelOverviewResponse();
        // AUD-P2-10 改 DC-01：cohort 在 SQL 端完成逐步 JOIN 传递 session_key 交集
        response.stepMetrics = computeCohortStepMetrics(window, scope, sourceChannel);
        response.dropoffReasons = dropoffReasons(eventMapper.countByDropoffReason(
            window.startTime(), window.endTime(), scope.venueId(), scope.venueIds(), sourceChannel));
        response.sourceChannels = sourceChannels(eventMapper.countBySourceChannel(
            window.startTime(), window.endTime(), scope.venueId(), scope.venueIds(), sourceChannel));
        response.topKeywords = topKeywords(eventMapper.topKeywords(
            window.startTime(), window.endTime(), scope.venueId(), scope.venueIds(), sourceChannel));
        response.dataGaps = new ArrayList<>(DATA_GAPS);
        if (scope.venueScoped()) {
            response.dataGaps.add(VENUE_SCOPE_GAP);
        }
        response.sampleEvents = sampleResponses(window, scope, sourceChannel);
        return response;
    }

    public List<FunnelEventResponse> samples(FunnelOverviewQuery query) {
        QueryWindow window = normalizeWindow(query);
        Scope scope = resolveScope(query);
        String sourceChannel = normalizeSourceChannel(query);
        return sampleResponses(window, scope, sourceChannel);
    }

    // DC-01：cohort 在 SQL 端用逐步 JOIN 传递 session_key 交集（见 FunnelEventMapper.selectCohortStepCounts），
    // Service 端不再 select * 全量拉内存做 Set.retainAll；这里按 EVENT_ORDER 组装 metric。
    // F-22：SQL 只返回本口径真正计量的步骤；未返回的步骤是「不适用」而不是零转化，count/转化率为空，
    // 转化率分母取上一个可计量步骤，起点步骤标 startStep。
    private List<FunnelStepMetric> computeCohortStepMetrics(QueryWindow window, Scope scope, String sourceChannel) {
        List<Map<String, Object>> rows = eventMapper.selectCohortStepCounts(
            window.startTime(), window.endTime(), scope.venueId(), scope.venueIds(), sourceChannel,
            scope.venueScoped());
        Map<String, Long> measuredCountByEvent = new LinkedHashMap<>();
        for (Map<String, Object> row : safeRows(rows)) {
            String eventName = stringValue(row.get("eventName"));
            if (eventName != null && EVENT_ORDER.contains(eventName)) {
                measuredCountByEvent.put(eventName, longValue(row.get("count")));
            }
        }

        List<FunnelStepMetric> metrics = new ArrayList<>();
        long previousMeasured = -1;
        boolean startTaken = false;
        for (String eventName : EVENT_ORDER) {
            FunnelStepMetric metric = new FunnelStepMetric();
            metric.eventName = eventName;
            metric.label = STEP_LABELS.getOrDefault(eventName, eventName);
            if (!measuredCountByEvent.containsKey(eventName)) {
                metric.measurement = FunnelStepMeasurement.NOT_APPLICABLE;
                metric.count = null;
                metric.conversionRate = null;
                metric.notApplicableReason = NOT_APPLICABLE_REASONS.getOrDefault(
                    eventName, "该步骤在当前查询口径下没有可归属的事件。");
                metrics.add(metric);
                continue;
            }
            long count = measuredCountByEvent.get(eventName);
            metric.measurement = FunnelStepMeasurement.MEASURED;
            metric.count = count;
            metric.startStep = !startTaken;
            metric.conversionRate = metric.startStep
                ? conversionRate(count, count, true)
                : conversionRate(count, previousMeasured, false);
            metrics.add(metric);
            previousMeasured = count;
            startTaken = true;
        }
        return metrics;
    }

    private List<FunnelDropoffMetric> dropoffReasons(List<Map<String, Object>> rows) {
        List<FunnelDropoffMetric> metrics = new ArrayList<>();
        for (Map<String, Object> row : safeRows(rows)) {
            String reasonCode = stringValue(row.get("reasonCode"));
            if (reasonCode == null) {
                continue;
            }
            FunnelDropoffMetric metric = new FunnelDropoffMetric();
            metric.reasonCode = reasonCode;
            metric.label = DROPOFF_LABELS.getOrDefault(reasonCode, reasonCode);
            metric.count = longValue(row.get("count"));
            metrics.add(metric);
        }
        return metrics;
    }

    private List<FunnelSourceMetric> sourceChannels(List<Map<String, Object>> rows) {
        List<FunnelSourceMetric> metrics = new ArrayList<>();
        for (Map<String, Object> row : safeRows(rows)) {
            String sourceChannel = stringValue(row.get("sourceChannel"));
            FunnelSourceMetric metric = new FunnelSourceMetric();
            metric.sourceChannel = sourceChannel == null ? "UNKNOWN" : sourceChannel;
            metric.count = longValue(row.get("count"));
            metrics.add(metric);
        }
        return metrics;
    }

    private List<String> topKeywords(List<Map<String, Object>> rows) {
        List<String> keywords = new ArrayList<>();
        for (Map<String, Object> row : safeRows(rows)) {
            String keyword = stringValue(row.get("keyword"));
            if (keyword == null) {
                keyword = stringValue(row.get("keywordNormalized"));
            }
            if (keyword != null) {
                keywords.add(keyword);
            }
        }
        return keywords;
    }

    private List<FunnelEventResponse> sampleResponses(QueryWindow window, Scope scope, String sourceChannel) {
        List<FunnelEvent> events = eventMapper.selectAnalyticsSamples(
            window.startTime(), window.endTime(), scope.venueId(), scope.venueIds(), null, sourceChannel, SAMPLE_LIMIT);
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<FunnelEventResponse> responses = new ArrayList<>();
        for (FunnelEvent event : events) {
            responses.add(toSafeResponse(event));
        }
        return responses;
    }

    private FunnelEventResponse toSafeResponse(FunnelEvent event) {
        FunnelEventResponse response = new FunnelEventResponse();
        response.id = event.id;
        response.eventName = event.eventName;
        response.eventStage = event.eventStage;
        response.resultStatus = event.resultStatus;
        response.venueId = event.venueId;
        response.occurredAt = event.occurredAt;
        return response;
    }

    private BigDecimal conversionRate(long count, long previousCount, boolean firstStep) {
        if (firstStep) {
            return count > 0 ? ONE_RATE : ZERO_RATE;
        }
        if (previousCount <= 0) {
            return ZERO_RATE;
        }
        return BigDecimal.valueOf(Math.min(count, previousCount))
            .divide(BigDecimal.valueOf(previousCount), 4, RoundingMode.HALF_UP);
    }

    private QueryWindow normalizeWindow(FunnelOverviewQuery query) {
        LocalDate today = LocalDate.now();
        LocalDate startDate = query == null || query.startDate == null ? today.minusDays(6) : query.startDate;
        LocalDate endDate = query == null || query.endDate == null ? today : query.endDate;
        if (startDate.isAfter(endDate)) {
            throw BusinessException.badRequest("漏斗查询开始日期不能晚于结束日期");
        }
        return new QueryWindow(startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());
    }

    private Scope resolveScope(FunnelOverviewQuery query) {
        CurrentUser user = SecurityContext.currentUser();
        if (user == null) {
            throw BusinessException.forbidden("未登录");
        }
        Long venueId = query == null ? null : query.venueId;
        if (venueId != null) {
            venueAccessService.requireVenueManageAccess(venueId);
            return new Scope(venueId, null, true);
        }
        if (user.hasRole(Constants.ROLE_SYSTEM_ADMIN)) {
            return new Scope(null, null, false);
        }
        List<Long> venueIds = venueAccessService.manageableVenueIds();
        return new Scope(null, venueIds == null ? List.of() : venueIds, true);
    }

    private String normalizeSourceChannel(FunnelOverviewQuery query) {
        if (query == null || query.sourceChannel == null || query.sourceChannel.isBlank()) {
            return null;
        }
        return query.sourceChannel.trim().toUpperCase(Locale.ROOT);
    }

    private List<Map<String, Object>> safeRows(List<Map<String, Object>> rows) {
        return rows == null ? List.of() : rows;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private record QueryWindow(LocalDateTime startTime, LocalDateTime endTime) {
    }

    private record Scope(Long venueId, List<Long> venueIds, boolean venueScoped) {
    }
}
