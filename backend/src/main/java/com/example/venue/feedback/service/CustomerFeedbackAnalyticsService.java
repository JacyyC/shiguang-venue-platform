package com.example.venue.feedback.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.feedback.entity.CustomerComplaint;
import com.example.venue.feedback.entity.CustomerServiceCase;
import com.example.venue.feedback.entity.VenueReview;
import com.example.venue.feedback.mapper.CustomerComplaintMapper;
import com.example.venue.feedback.mapper.CustomerServiceCaseMapper;
import com.example.venue.feedback.mapper.VenueReviewMapper;
import com.example.venue.venue.service.VenueAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CustomerFeedbackAnalyticsService {
    private static final List<String> DATA_GAPS = List.of(
        "外部客服 SaaS 未接入。",
        "真实外部消息触达未启用。",
        "退款、补偿和处罚均需人工审核，不支持自动执行。",
        "生产天气 SLA 未接入。",
        "商业风控、设备指纹和地理 IP provider 未接入。",
        "Prometheus/APM、完整慢查询和分布式追踪未接入。"
    );
    private static final List<String> EVIDENCE_REFS = List.of(
        "customer.feedback:$",
        "customer.feedback:$.representativeSamples[*].contentSanitized"
    );

    private final VenueReviewMapper reviewMapper;
    private final CustomerComplaintMapper complaintMapper;
    private final CustomerServiceCaseMapper caseMapper;
    private final VenueAccessService venueAccessService;
    private final FeedbackTextSanitizer sanitizer;

    public CustomerFeedbackAnalyticsService(VenueReviewMapper reviewMapper,
                                            CustomerComplaintMapper complaintMapper,
                                            CustomerServiceCaseMapper caseMapper,
                                            VenueAccessService venueAccessService,
                                            FeedbackTextSanitizer sanitizer) {
        this.reviewMapper = reviewMapper;
        this.complaintMapper = complaintMapper;
        this.caseMapper = caseMapper;
        this.venueAccessService = venueAccessService;
        this.sanitizer = sanitizer;
    }

    @Transactional(readOnly = true)
    public LinkedHashMap<String, Object> feedbackContext(Map<String, Object> input) {
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        VenueScope scope = resolveVenueScope(safeInput);
        if (scope.empty()) {
            return feedbackContext(List.of(), List.of(), List.of());
        }

        List<VenueReview> reviews = safeList(reviewMapper.selectList(reviewWrapper(safeInput, scope)));
        List<CustomerComplaint> complaints = safeList(complaintMapper.selectList(complaintWrapper(safeInput, scope)));
        List<CustomerServiceCase> cases = safeList(caseMapper.selectList(caseWrapper(safeInput, scope)));
        return feedbackContext(reviews, complaints, cases);
    }

    private LinkedHashMap<String, Object> feedbackContext(List<VenueReview> reviews,
                                                          List<CustomerComplaint> complaints,
                                                          List<CustomerServiceCase> cases) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("reviewSummary", Map.of(
            "statusCounts", countBy(reviews, review -> value(review.status, "UNKNOWN")),
            "averageRating", averageRating(reviews)
        ));
        data.put("complaintSummary", Map.of(
            "statusCounts", countBy(complaints, complaint -> value(complaint.status, "UNKNOWN")),
            "typeCounts", countBy(complaints, complaint -> value(complaint.complaintType, "UNKNOWN")),
            "severityCounts", countBy(complaints, complaint -> value(complaint.severity, "UNKNOWN"))
        ));
        data.put("caseSummary", Map.of(
            "statusCounts", countBy(cases, serviceCase -> value(serviceCase.status, "UNKNOWN")),
            "manualFollowUpCounts", manualFollowUpCounts(cases)
        ));
        data.put("rootCauseSummary", countBy(cases, serviceCase -> value(serviceCase.rootCauseCode, "UNSPECIFIED")));
        data.put("resolutionSummary", countBy(cases, serviceCase -> value(serviceCase.resolutionType, "UNSPECIFIED")));
        data.put("linkedEntitySummary", linkedEntityCounts(reviews, complaints, cases));
        data.put("representativeSamples", representativeSamples(reviews, complaints, cases));
        data.put("dataGaps", DATA_GAPS);
        data.put("evidenceRefs", EVIDENCE_REFS);
        return data;
    }

    private VenueScope resolveVenueScope(Map<String, Object> input) {
        Long venueId = longValue(input.get("venueId"));
        if (venueId != null) {
            venueAccessService.requireVenueManageAccess(venueId);
            return new VenueScope(venueId, null, false);
        }
        List<Long> manageableVenueIds = venueAccessService.manageableVenueIds();
        return new VenueScope(null, manageableVenueIds, manageableVenueIds != null && manageableVenueIds.isEmpty());
    }

    private QueryWrapper<VenueReview> reviewWrapper(Map<String, Object> input, VenueScope scope) {
        QueryWrapper<VenueReview> wrapper = new QueryWrapper<VenueReview>().eq("deleted", 0);
        applyScope(wrapper, scope);
        Long spaceId = longValue(input.get("spaceId"));
        if (spaceId != null) {
            wrapper.eq("space_id", spaceId);
        }
        applyDays(wrapper, input, "submitted_at");
        return wrapper.orderByDesc("submitted_at").orderByDesc("id");
    }

    private QueryWrapper<CustomerComplaint> complaintWrapper(Map<String, Object> input, VenueScope scope) {
        QueryWrapper<CustomerComplaint> wrapper = new QueryWrapper<CustomerComplaint>().eq("deleted", 0);
        applyScope(wrapper, scope);
        Long spaceId = longValue(input.get("spaceId"));
        if (spaceId != null) {
            wrapper.eq("space_id", spaceId);
        }
        applyDays(wrapper, input, "submitted_at");
        return wrapper.orderByDesc("submitted_at").orderByDesc("id");
    }

    private QueryWrapper<CustomerServiceCase> caseWrapper(Map<String, Object> input, VenueScope scope) {
        QueryWrapper<CustomerServiceCase> wrapper = new QueryWrapper<CustomerServiceCase>().eq("deleted", 0);
        applyScope(wrapper, scope);
        applyDays(wrapper, input, "opened_at");
        return wrapper.orderByDesc("opened_at").orderByDesc("id");
    }

    private void applyScope(QueryWrapper<?> wrapper, VenueScope scope) {
        if (scope.venueId() != null) {
            wrapper.eq("venue_id", scope.venueId());
        } else if (scope.manageableVenueIds() != null) {
            wrapper.in("venue_id", scope.manageableVenueIds());
        }
    }

    private void applyDays(QueryWrapper<?> wrapper, Map<String, Object> input, String column) {
        Long days = longValue(input.get("days"));
        if (days != null && days > 0) {
            wrapper.ge(column, LocalDateTime.now().minusDays(Math.min(days, 180)));
        }
    }

    private BigDecimal averageRating(List<VenueReview> reviews) {
        List<Integer> ratings = reviews.stream()
            .map(review -> review.overallRating)
            .filter(Objects::nonNull)
            .toList();
        if (ratings.isEmpty()) {
            return new BigDecimal("0.00");
        }
        int sum = ratings.stream().mapToInt(Integer::intValue).sum();
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(ratings.size()), 2, RoundingMode.HALF_UP);
    }

    private Map<String, Long> manualFollowUpCounts(List<CustomerServiceCase> cases) {
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        counts.put("requiresRefundReview", cases.stream().filter(item -> flag(item.requiresRefundReview)).count());
        counts.put("requiresCouponReview", cases.stream().filter(item -> flag(item.requiresCouponReview)).count());
        counts.put("requiresSiteFollowUp", cases.stream().filter(item -> flag(item.requiresSiteFollowUp)).count());
        return counts;
    }

    private Map<String, Long> linkedEntityCounts(List<VenueReview> reviews,
                                                List<CustomerComplaint> complaints,
                                                List<CustomerServiceCase> cases) {
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        for (VenueReview review : reviews) {
            increment(counts, "venue", review.venueId);
            increment(counts, "space", review.spaceId);
            increment(counts, "reservation", review.reservationId);
            increment(counts, "order", review.orderId);
            increment(counts, "lifecycle", review.lifecycleId);
        }
        for (CustomerComplaint complaint : complaints) {
            increment(counts, "venue", complaint.venueId);
            increment(counts, "space", complaint.spaceId);
            increment(counts, "reservation", complaint.reservationId);
            increment(counts, "order", complaint.orderId);
            increment(counts, "lifecycle", complaint.lifecycleId);
            increment(counts, "notice", complaint.noticeId);
            increment(counts, "asset", complaint.assetId);
            increment(counts, "siteIncident", complaint.siteIncidentId);
        }
        for (CustomerServiceCase serviceCase : cases) {
            increment(counts, "venue", serviceCase.venueId);
            increment(counts, "complaint", serviceCase.complaintId);
        }
        return counts;
    }

    private List<Map<String, Object>> representativeSamples(List<VenueReview> reviews,
                                                            List<CustomerComplaint> complaints,
                                                            List<CustomerServiceCase> cases) {
        List<Map<String, Object>> samples = new ArrayList<>();
        reviews.stream().limit(5).map(this::reviewSample).forEach(samples::add);
        complaints.stream().limit(5).map(this::complaintSample).forEach(samples::add);
        cases.stream().limit(5).map(this::caseSample).forEach(samples::add);
        return samples.stream().limit(10).toList();
    }

    private Map<String, Object> reviewSample(VenueReview review) {
        LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
        sample.put("sampleType", "REVIEW");
        sample.put("venueId", review.venueId);
        sample.put("spaceId", review.spaceId);
        sample.put("status", review.status);
        sample.put("overallRating", review.overallRating);
        sample.put("contentSanitized", sanitizeForOutput(review.contentSanitized, review.contentText));
        sample.put("linkedEntityTypes", linkedTypes(
            "reservation", review.reservationId,
            "order", review.orderId,
            "lifecycle", review.lifecycleId
        ));
        return compact(sample);
    }

    private Map<String, Object> complaintSample(CustomerComplaint complaint) {
        LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
        sample.put("sampleType", "COMPLAINT");
        sample.put("venueId", complaint.venueId);
        sample.put("spaceId", complaint.spaceId);
        sample.put("status", complaint.status);
        sample.put("complaintType", complaint.complaintType);
        sample.put("severity", complaint.severity);
        sample.put("contentSanitized", sanitizeForOutput(complaint.contentSanitized, complaint.contentText));
        sample.put("linkedEntityTypes", linkedTypes(
            "reservation", complaint.reservationId,
            "order", complaint.orderId,
            "lifecycle", complaint.lifecycleId,
            "notice", complaint.noticeId,
            "asset", complaint.assetId,
            "siteIncident", complaint.siteIncidentId
        ));
        return compact(sample);
    }

    private Map<String, Object> caseSample(CustomerServiceCase serviceCase) {
        LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
        sample.put("sampleType", "CASE");
        sample.put("venueId", serviceCase.venueId);
        sample.put("status", serviceCase.status);
        sample.put("priority", serviceCase.priority);
        sample.put("rootCauseCode", serviceCase.rootCauseCode);
        sample.put("resolutionType", serviceCase.resolutionType);
        sample.put("manualFollowUp", Map.of(
            "requiresRefundReview", flag(serviceCase.requiresRefundReview),
            "requiresCouponReview", flag(serviceCase.requiresCouponReview),
            "requiresSiteFollowUp", flag(serviceCase.requiresSiteFollowUp)
        ));
        sample.put("linkedEntityTypes", linkedTypes("complaint", serviceCase.complaintId));
        return compact(sample);
    }

    private List<String> linkedTypes(Object... keyValues) {
        List<String> types = new ArrayList<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                types.add(keyValues[i].toString());
            }
        }
        return types;
    }

    private Map<String, Object> compact(LinkedHashMap<String, Object> sample) {
        return sample.entrySet().stream()
            .filter(entry -> entry.getValue() != null)
            .filter(entry -> !(entry.getValue() instanceof String value) || !value.isBlank())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
    }

    private String sanitizeForOutput(String storedSanitized, String rawText) {
        String chosen = storedSanitized != null && !storedSanitized.isBlank() ? storedSanitized : rawText;
        return sanitizer.sanitize(chosen).text();
    }

    private <T> Map<String, Long> countBy(List<T> records, Function<T, String> classifier) {
        return records.stream()
            .collect(Collectors.groupingBy(classifier, LinkedHashMap::new, Collectors.counting()));
    }

    private void increment(Map<String, Long> counts, String key, Object value) {
        if (value != null) {
            counts.merge(key, 1L, Long::sum);
        }
    }

    private <T> List<T> safeList(List<T> records) {
        return records == null ? List.of() : records;
    }

    private boolean flag(Integer value) {
        return Integer.valueOf(1).equals(value);
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null && !value.toString().isBlank()) {
            return Long.parseLong(value.toString());
        }
        return null;
    }

    private record VenueScope(Long venueId, List<Long> manageableVenueIds, boolean empty) {
    }
}
