package com.example.venue.notice.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.enums.ProviderEnv;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.Constants;
import com.example.venue.common.CurrentUser;
import com.example.venue.notice.dto.AudiencePreviewRequest;
import com.example.venue.notice.dto.NoticeRequest;
import com.example.venue.notice.entity.Notice;
import com.example.venue.notice.entity.NoticeAudienceSnapshot;
import com.example.venue.notice.entity.NoticeDeliveryBatch;
import com.example.venue.notice.entity.NoticeExperiment;
import com.example.venue.notice.entity.NoticeExperimentVariant;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.mapper.NoticeAudienceSnapshotMapper;
import com.example.venue.notice.mapper.NoticeDeliveryBatchMapper;
import com.example.venue.notice.mapper.NoticeExperimentMapper;
import com.example.venue.notice.mapper.NoticeExperimentVariantMapper;
import com.example.venue.notice.mapper.NoticeMapper;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRoute;
import com.example.venue.notice.routing.NotificationRoutingPolicy;
import com.example.venue.security.SecurityContext;
import com.example.venue.venue.service.VenueAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class NoticeDeliveryService {
    public static final String PROVIDER_ENV = "DEV";
    public static final String PROVIDER_NAME = "IN_APP_MESSAGE";
    public static final String CONFIG_VERSION = "IN_APP_MESSAGE_V1";
    public static final String CHANNEL = "IN_APP";

    private static final DateTimeFormatter BATCH_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> SUPPORTED_SCOPES = Set.of(
        "ALL_USERS",
        "MEMBER_LEVEL",
        "VENUE_RECENT_RESERVERS",
        "CAMPAIGN_PARTICIPANTS",
        "SPECIFIC_USERS"
    );

    private final NoticeMapper noticeMapper;
    private final NoticeDeliveryBatchMapper batchMapper;
    private final NoticeAudienceSnapshotMapper audienceMapper;
    private final NoticeOutboxMapper outboxMapper;
    private final NoticeAudienceService audienceService;
    private final VenueAccessService venueAccessService;
    private final BusinessEventService businessEventService;
    private final NoticeExperimentMapper experimentMapper;
    private final NoticeExperimentVariantMapper variantMapper;
    private final ObjectMapper objectMapper;
    private final NotificationRoutingPolicy routingPolicy;

    public NoticeDeliveryService(NoticeMapper noticeMapper, NoticeDeliveryBatchMapper batchMapper,
                                 NoticeAudienceSnapshotMapper audienceMapper, NoticeOutboxMapper outboxMapper,
                                 NoticeAudienceService audienceService, VenueAccessService venueAccessService,
                                 BusinessEventService businessEventService,
                                 NoticeExperimentMapper experimentMapper,
                                 NoticeExperimentVariantMapper variantMapper,
                                 ObjectMapper objectMapper,
                                 NotificationRoutingPolicy routingPolicy) {
        this.noticeMapper = noticeMapper;
        this.batchMapper = batchMapper;
        this.audienceMapper = audienceMapper;
        this.outboxMapper = outboxMapper;
        this.audienceService = audienceService;
        this.venueAccessService = venueAccessService;
        this.businessEventService = businessEventService;
        this.experimentMapper = experimentMapper;
        this.variantMapper = variantMapper;
        this.objectMapper = objectMapper;
        this.routingPolicy = routingPolicy;
    }

    @Transactional
    public Notice publish(NoticeRequest request) {
        CurrentUser user = SecurityContext.currentUser();
        if (user == null) {
            throw BusinessException.forbidden("未登录");
        }
        if (request == null) {
            throw BusinessException.badRequest("公告参数不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        String status = normalizeStatus(request.status);
        String targetScope = normalizeScope(request.targetScope);
        validateVenueAccess(request, user, targetScope);
        ExperimentAssignment experimentAssignment = "PUBLISHED".equals(status)
            ? loadRunningExperiment(request.experimentId)
            : null;
        Notice notice = new Notice();
        notice.title = request.title;
        notice.content = sanitizeHtml(request.content);
        notice.type = normalizeType(request.type, request.targetVenueId);
        notice.targetVenueId = request.targetVenueId;
        notice.status = status;
        notice.publisherId = user.id();
        notice.publishTime = "PUBLISHED".equals(status) ? now : null;
        notice.targetRuleJson = targetRuleJson(targetScope, request);
        notice.experimentId = request.experimentId;
        notice.createTime = now;
        notice.updateTime = now;
        notice.deleted = 0;
        noticeMapper.insert(notice);

        if ("DRAFT".equals(status)) {
            return notice;
        }

        NoticeDeliveryBatch batch = createBatch(notice, targetScope, request, user, now);
        batchMapper.insert(batch);
        notice.deliveryBatchId = batch.id;
        notice.updateTime = LocalDateTime.now();
        noticeMapper.updateById(notice);

        List<NoticeAudienceSnapshot> snapshots = audienceService.snapshotAudience(batch.id, notice.id, audienceRequest(targetScope, request));
        assignExperimentVariants(snapshots, experimentAssignment);
        for (NoticeAudienceSnapshot audience : snapshots) {
            if (!isDeliverable(audience)) {
                continue;
            }
            audience.deliveryStatus = "PENDING";
            audience.updateTime = LocalDateTime.now();
            audienceMapper.updateById(audience);
            enqueueRoutes(batch, notice, audience, payloadForPublish(notice, audience, experimentAssignment));
        }
        batch.audienceCount = snapshots.size();
        batch.deliverableCount = (int) snapshots.stream().filter(this::isDeliverable).count();
        batch.excludedCount = batch.audienceCount - batch.deliverableCount;
        if (batch.deliverableCount == 0) {
            batch.status = "COMPLETED";
        }

        recordNoticePublished(notice, batch, user);
        batch.updateTime = LocalDateTime.now();
        batchMapper.updateById(batch);
        return notice;
    }

    @Transactional
    public void retryOutbox(Long outboxId) {
        if (outboxId == null) {
            throw BusinessException.badRequest("投递任务不存在");
        }
        NoticeOutbox outbox = outboxMapper.selectById(outboxId);
        if (outbox == null || !Integer.valueOf(0).equals(outbox.deleted)) {
            throw BusinessException.notFound("投递任务不存在");
        }
        Notice authorizedNotice = requireRetryOutboxAccess(outbox);
        if ("EMAIL".equals(outbox.channel)) {
            retryFailedEmailOutbox(outbox);
            return;
        }
        if (isBusinessNotificationInAppOutbox(outbox)) {
            retryFailedBusinessInAppOutbox(outbox);
            return;
        }
        validateRetryableOutbox(outbox);
        Notice notice = authorizedNotice == null ? noticeMapper.selectById(outbox.noticeId) : authorizedNotice;
        if (notice == null) {
            throw BusinessException.notFound("公告不存在");
        }
        NoticeAudienceSnapshot audience = audienceMapper.selectById(outbox.audienceId);
        if (!belongsToOutbox(audience, outbox)) {
            throw BusinessException.notFound("公告受众不存在");
        }
        NoticeDeliveryBatch batch = batchMapper.selectById(outbox.batchId);
        if (batch == null || Integer.valueOf(1).equals(batch.deleted)
            || !notice.id.equals(batch.noticeId)) {
            throw BusinessException.notFound("公告投递批次不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        int expectedAttemptCount = outbox.attemptCount == null ? 0 : outbox.attemptCount;
        int updated = outboxMapper.update(null, new UpdateWrapper<NoticeOutbox>()
            .set("status", "PENDING")
            .set("next_retry_time", null)
            .set("last_error_code", null)
            .set("last_error_message", null)
            .set("update_time", now)
            .eq("id", outbox.id)
            .eq("deleted", 0)
            .eq("attempt_count", expectedAttemptCount)
            .in("status", List.of("FAILED", "RETRY_WAITING")));
        if (updated != 1) {
            throw BusinessException.badRequest("投递任务状态已变化，请刷新后重试");
        }
        outbox.status = "PENDING";
        outbox.nextRetryTime = null;
        outbox.lastErrorCode = null;
        outbox.lastErrorMessage = null;
        outbox.updateTime = now;
        audience.deliveryStatus = "PENDING";
        audience.updateTime = now;
        audienceMapper.updateById(audience);

        batch.status = "PROCESSING";
        batch.updateTime = now;
        batchMapper.updateById(batch);
        recordRetryEnqueued(notice, batch, outbox, audience);
    }

    private void retryFailedEmailOutbox(NoticeOutbox outbox) {
        if (!"FAILED".equals(outbox.status)) {
            throw BusinessException.badRequest("当前投递任务状态不能重试");
        }
        LocalDateTime now = LocalDateTime.now();
        int expectedAttemptCount = outbox.attemptCount == null ? 0 : outbox.attemptCount;
        int updated = outboxMapper.update(null, new UpdateWrapper<NoticeOutbox>()
            .set("status", "PENDING")
            .set("next_retry_time", null)
            .set("last_error_code", null)
            .set("last_error_message", null)
            .set("update_time", now)
            .eq("id", outbox.id)
            .eq("deleted", 0)
            .eq("attempt_count", expectedAttemptCount)
            .eq("status", "FAILED"));
        if (updated != 1) {
            throw BusinessException.badRequest("投递任务状态已变化，请刷新后重试");
        }
        outbox.status = "PENDING";
        outbox.nextRetryTime = null;
        outbox.lastErrorCode = null;
        outbox.lastErrorMessage = null;
        outbox.updateTime = now;
    }

    private boolean isBusinessNotificationInAppOutbox(NoticeOutbox outbox) {
        // 业务通知类 IN_APP outbox 由 NoticeService.createBusinessNotification/createMessage 产生，
        // 不携带公告投递上下文（noticeId/audienceId/batchId 均为 null），不能走公告重试路径。
        return !"EMAIL".equals(outbox.channel)
            && outbox.noticeId == null
            && outbox.audienceId == null
            && outbox.batchId == null;
    }

    private void retryFailedBusinessInAppOutbox(NoticeOutbox outbox) {
        validateRetryableOutbox(outbox);
        LocalDateTime now = LocalDateTime.now();
        int expectedAttemptCount = outbox.attemptCount == null ? 0 : outbox.attemptCount;
        int updated = outboxMapper.update(null, new UpdateWrapper<NoticeOutbox>()
            .set("status", "PENDING")
            .set("next_retry_time", null)
            .set("last_error_code", null)
            .set("last_error_message", null)
            .set("update_time", now)
            .eq("id", outbox.id)
            .eq("deleted", 0)
            .eq("attempt_count", expectedAttemptCount)
            .in("status", List.of("FAILED", "RETRY_WAITING")));
        if (updated != 1) {
            throw BusinessException.badRequest("投递任务状态已变化，请刷新后重试");
        }
        outbox.status = "PENDING";
        outbox.nextRetryTime = null;
        outbox.lastErrorCode = null;
        outbox.lastErrorMessage = null;
        outbox.updateTime = now;
    }

    private void validateRetryableOutbox(NoticeOutbox outbox) {
        if ("SENT".equals(outbox.status)) {
            throw BusinessException.badRequest("已发送的投递任务不能重复重试");
        }
        if (!"FAILED".equals(outbox.status) && !"RETRY_WAITING".equals(outbox.status)) {
            throw BusinessException.badRequest("当前投递任务状态不能重试");
        }
    }

    private Notice requireRetryOutboxAccess(NoticeOutbox outbox) {
        CurrentUser user = SecurityContext.currentUser();
        if (user == null) {
            throw BusinessException.forbidden("未登录");
        }
        if (user.hasRole(Constants.ROLE_SYSTEM_ADMIN)) {
            return null;
        }
        if (!user.hasRole(Constants.ROLE_VENUE_ADMIN) || outbox.noticeId == null) {
            throw BusinessException.forbidden("无权重试该公告投递任务");
        }
        Notice notice = noticeMapper.selectById(outbox.noticeId);
        if (notice == null || Integer.valueOf(1).equals(notice.deleted)) {
            throw BusinessException.notFound("公告不存在");
        }
        if (notice.targetVenueId == null) {
            throw BusinessException.forbidden("无权重试该公告投递任务");
        }
        venueAccessService.requireVenueManageAccess(notice.targetVenueId);
        return notice;
    }

    private void validateVenueAccess(NoticeRequest request, CurrentUser user, String targetScope) {
        if (request.targetVenueId == null
            && user.hasRole(Constants.ROLE_VENUE_ADMIN)
            && !user.hasRole(Constants.ROLE_SYSTEM_ADMIN)) {
            throw BusinessException.forbidden("场馆管理员只能发布已绑定场馆的公告");
        }
        if (user.hasRole(Constants.ROLE_VENUE_ADMIN)
            && !user.hasRole(Constants.ROLE_SYSTEM_ADMIN)
            && !"VENUE_RECENT_RESERVERS".equals(targetScope)) {
            throw BusinessException.forbidden("场馆管理员只能发布场馆近期预约用户公告");
        }
        if (request.targetVenueId != null) {
            venueAccessService.requireVenueManageAccess(request.targetVenueId);
        }
    }

    private NoticeDeliveryBatch createBatch(Notice notice, String targetScope, NoticeRequest request,
                                            CurrentUser user, LocalDateTime now) {
        NoticeDeliveryBatch batch = new NoticeDeliveryBatch();
        batch.batchNo = nextBatchNo(now);
        batch.noticeId = notice.id;
        batch.targetScope = targetScope;
        batch.targetRuleJson = notice.targetRuleJson;
        batch.audienceCount = 0;
        batch.deliverableCount = 0;
        batch.excludedCount = 0;
        batch.status = "PENDING";
        batch.providerEnv = PROVIDER_ENV;
        batch.providerName = PROVIDER_NAME;
        batch.configVersion = CONFIG_VERSION;
        batch.sourceType = BusinessSourceType.ADMIN.name();
        batch.publisherId = user.id();
        batch.experimentId = request.experimentId;
        batch.createTime = now;
        batch.updateTime = now;
        batch.deleted = 0;
        return batch;
    }

    private ExperimentAssignment loadRunningExperiment(Long experimentId) {
        if (experimentId == null) {
            return null;
        }
        NoticeExperiment experiment = experimentMapper.selectById(experimentId);
        if (experiment == null || Integer.valueOf(1).equals(experiment.deleted)
            || !"RUNNING".equals(experiment.status)) {
            throw BusinessException.badRequest("公告实验必须是运行中的有效实验");
        }
        List<NoticeExperimentVariant> variants = safeVariants(variantMapper.findByExperimentId(experimentId));
        if (variants.isEmpty()) {
            throw BusinessException.badRequest("运行中的公告实验必须包含版本");
        }
        int allocationTotal = 0;
        for (NoticeExperimentVariant variant : variants) {
            if (variant.id == null) {
                throw BusinessException.badRequest("公告实验版本数据不完整");
            }
            int allocation = variant.allocationPercent == null ? -1 : variant.allocationPercent;
            if (allocation < 0 || allocation > 100) {
                throw BusinessException.badRequest("实验版本流量占比必须在 0 到 100 之间");
            }
            allocationTotal += allocation;
        }
        if (allocationTotal != 100) {
            throw BusinessException.badRequest("实验版本流量占比合计必须为 100");
        }
        return new ExperimentAssignment(experiment, variants);
    }

    private void assignExperimentVariants(List<NoticeAudienceSnapshot> snapshots, ExperimentAssignment assignment) {
        if (assignment == null || snapshots == null || snapshots.isEmpty()) {
            return;
        }
        List<NoticeAudienceSnapshot> deliverable = snapshots.stream()
            .filter(this::isDeliverable)
            .toList();
        int total = deliverable.size();
        for (int index = 0; index < total; index++) {
            NoticeExperimentVariant variant = assignment.variantFor(index, total);
            deliverable.get(index).experimentVariantId = variant.id;
        }
    }

    private DeliveryPayload payloadForPublish(Notice notice, NoticeAudienceSnapshot audience,
                                              ExperimentAssignment assignment) {
        if (assignment == null || audience.experimentVariantId == null) {
            return DeliveryPayload.fromNotice(notice);
        }
        NoticeExperimentVariant variant = assignment.variantById(audience.experimentVariantId);
        return variant == null ? DeliveryPayload.fromNotice(notice) : DeliveryPayload.fromVariant(notice, variant);
    }

    private void enqueueRoutes(NoticeDeliveryBatch batch, Notice notice, NoticeAudienceSnapshot audience,
                               DeliveryPayload payload) {
        NotificationEvent event = new NotificationEvent(
            "NOTICE_PUBLISHED", audience.userId, payload.title(),
            Jsoup.clean(payload.content(), Safelist.none()), "NOTICE", notice.id,
            batch.id, audience.id, notice.id);
        List<NotificationRoute> routes = routingPolicy.routesFor(event);
        if (routes == null || routes.isEmpty()) {
            throw BusinessException.badRequest("NOTICE_ROUTE_NOT_FOUND: 公告通知路由不存在");
        }
        for (NotificationRoute route : routes) {
            outboxMapper.insert(createOutbox(batch, notice, audience, payload, route));
        }
    }

    private NoticeOutbox createOutbox(NoticeDeliveryBatch batch, Notice notice, NoticeAudienceSnapshot audience,
                                      DeliveryPayload payload, NotificationRoute route) {
        if (route == null || route.channel() == null || route.providerKey() == null
            || route.providerEnv() == null || route.providerName() == null || route.configVersion() == null) {
            throw BusinessException.badRequest("NOTICE_ROUTE_INVALID: 公告通知路由无效");
        }
        LocalDateTime now = LocalDateTime.now();
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.outboxNo = nextOutboxNo(now);
        outbox.batchId = batch.id;
        outbox.audienceId = audience.id;
        outbox.noticeId = notice.id;
        outbox.userId = audience.userId;
        outbox.channel = route.channel();
        outbox.providerEnv = route.providerEnv();
        outbox.providerName = route.providerName();
        outbox.configVersion = route.configVersion();
        outbox.status = "PENDING";
        outbox.attemptCount = 0;
        outbox.idempotencyKey = idempotencyKey(batch.id, audience.userId, route.providerName());
        outbox.requestSummaryJson = requestSummaryJson(notice, audience, payload);
        outbox.metadataJson = metadataJson(route);
        outbox.createTime = now;
        outbox.updateTime = now;
        outbox.deleted = 0;
        return outbox;
    }

    private boolean belongsToOutbox(NoticeAudienceSnapshot audience, NoticeOutbox outbox) {
        return audience != null
            && !Integer.valueOf(1).equals(audience.deleted)
            && outbox.audienceId != null && outbox.audienceId.equals(audience.id)
            && outbox.batchId != null && outbox.batchId.equals(audience.batchId)
            && outbox.noticeId != null && outbox.noticeId.equals(audience.noticeId)
            && outbox.userId != null && outbox.userId.equals(audience.userId);
    }

    private void recordRetryEnqueued(Notice notice, NoticeDeliveryBatch batch, NoticeOutbox outbox,
                                     NoticeAudienceSnapshot audience) {
        CurrentUser user = SecurityContext.currentUser();
        BusinessEventRecordRequest request = BusinessEventRecordRequest.of(
                BusinessEventType.NOTICE_DELIVERED,
                BusinessDomain.NOTICE,
                "NOTICE",
                notice.id,
                BusinessSourceType.ADMIN,
                "PENDING")
            .relatedEntity("NOTICE_OUTBOX", outbox.id)
            .provider(resolveProviderEnv(outbox.providerEnv), outbox.providerName)
            .idempotencyKey("NOTICE_RETRY_ENQUEUED:" + outbox.id + ":" + outbox.attemptCount)
            .metadata("retryOutboxId", outbox.id)
            .metadata("audienceId", audience.id)
            .metadata("batchId", outbox.batchId)
            .metadata("batchStatus", batch == null ? null : batch.status);
        if (user != null) {
            request.actor(user.id(), user.username());
        }
        businessEventService.record(request);
    }

    private void recordNoticePublished(Notice notice, NoticeDeliveryBatch batch, CurrentUser user) {
        businessEventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.NOTICE_PUBLISHED,
                BusinessDomain.NOTICE,
                "NOTICE",
                notice.id,
                BusinessSourceType.ADMIN,
                "SUCCESS")
            .relatedEntity("NOTICE_DELIVERY_BATCH", batch.id)
            .actor(user.id(), user.username())
            .provider(ProviderEnv.DEV, PROVIDER_NAME)
            .idempotencyKey("NOTICE_PUBLISHED:" + batch.id)
            .metadata("targetScope", batch.targetScope)
            .metadata("audienceCount", batch.audienceCount)
            .metadata("deliverableCount", batch.deliverableCount)
            .metadata("excludedCount", batch.excludedCount));
    }

    private boolean isDeliverable(NoticeAudienceSnapshot audience) {
        return audience != null
            && !"EXCLUDED".equals(audience.deliveryStatus)
            && audience.excludeReason == null
            && audience.userId != null;
    }

    private AudiencePreviewRequest audienceRequest(String targetScope, NoticeRequest request) {
        AudiencePreviewRequest audienceRequest = new AudiencePreviewRequest();
        audienceRequest.targetScope = targetScope;
        audienceRequest.targetVenueId = request.targetVenueId;
        audienceRequest.memberLevelId = request.memberLevelId;
        audienceRequest.campaignId = request.campaignId;
        audienceRequest.userIds = request.userIds;
        return audienceRequest;
    }

    private String targetRuleJson(String targetScope, NoticeRequest request) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("targetScope", targetScope);
        rule.put("targetVenueId", request.targetVenueId);
        rule.put("memberLevelId", request.memberLevelId);
        rule.put("campaignId", request.campaignId);
        rule.put("userIds", request.userIds);
        rule.put("experimentId", request.experimentId);
        return toJson(rule);
    }

    private String requestSummaryJson(Notice notice, NoticeAudienceSnapshot audience, DeliveryPayload payload) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("noticeId", notice.id);
        summary.put("batchId", audience.batchId);
        summary.put("audienceId", audience.id);
        summary.put("userId", audience.userId);
        summary.put("channel", CHANNEL);
        summary.put("title", payload.title());
        summary.put("content", Jsoup.clean(payload.content(), Safelist.none()));
        summary.put("experimentId", payload.experimentId());
        summary.put("experimentVariantId", payload.experimentVariantId());
        summary.put("variantCode", payload.variantCode());
        return toJson(summary);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "PUBLISHED";
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!"PUBLISHED".equals(normalized) && !"DRAFT".equals(normalized)) {
            throw BusinessException.badRequest("公告状态只能为 PUBLISHED 或 DRAFT");
        }
        return normalized;
    }

    private String normalizeType(String type, Long targetVenueId) {
        if (type == null || type.isBlank()) {
            return targetVenueId == null ? "GLOBAL" : "VENUE";
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!"GLOBAL".equals(normalized) && !"VENUE".equals(normalized)) {
            throw BusinessException.badRequest("公告类型只能为 GLOBAL 或 VENUE");
        }
        if ("VENUE".equals(normalized) && targetVenueId == null) {
            throw BusinessException.badRequest("场馆公告必须绑定场馆");
        }
        return normalized;
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "ALL_USERS";
        }
        String normalized = scope.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_SCOPES.contains(normalized)) {
            throw BusinessException.badRequest("不支持的公告目标人群");
        }
        return normalized;
    }

    private String sanitizeHtml(String html) {
        if (html == null) {
            throw BusinessException.badRequest("公告内容不能为空");
        }
        return Jsoup.clean(html, Safelist.relaxed());
    }

    private String metadataJson(NotificationRoute route) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("providerKey", route.providerKey());
        metadata.put("source", "NOTICE_CAMPAIGN");
        return toJson(metadata);
    }

    private ProviderEnv resolveProviderEnv(String value) {
        try {
            String env = value == null ? PROVIDER_ENV : value.trim().toUpperCase(Locale.ROOT);
            return ProviderEnv.valueOf(env);
        } catch (IllegalArgumentException ex) {
            return ProviderEnv.DEV;
        }
    }

    private String idempotencyKey(Long batchId, Long userId, String providerName) {
        return "NOTICE_OUTBOX:" + batchId + ":" + userId + ":" + providerName;
    }

    private String nextBatchNo(LocalDateTime now) {
        return "NDB" + now.format(BATCH_NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String nextOutboxNo(LocalDateTime now) {
        return "NO" + now.format(BATCH_NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw BusinessException.badRequest("公告投递参数无法序列化");
        }
    }

    private List<NoticeExperimentVariant> safeVariants(List<NoticeExperimentVariant> variants) {
        if (variants == null || variants.isEmpty()) {
            return List.of();
        }
        List<NoticeExperimentVariant> ordered = variants.stream()
            .filter(variant -> variant != null)
            .collect(Collectors.toCollection(ArrayList::new));
        ordered.sort(Comparator.comparing(variant -> variant.id, Comparator.nullsLast(Long::compareTo)));
        return ordered;
    }

    private record DeliveryPayload(String title, String content, Long experimentId,
                                   Long experimentVariantId, String variantCode) {
        static DeliveryPayload fromNotice(Notice notice) {
            return new DeliveryPayload(plainText(notice.title), plainText(notice.content),
                notice.experimentId, null, null);
        }

        static DeliveryPayload fromVariant(Notice notice, NoticeExperimentVariant variant) {
            return new DeliveryPayload(plainText(variant.title), plainText(variant.content),
                notice.experimentId, variant.id, variant.variantCode);
        }

        private static String plainText(String value) {
            return value == null ? null : Jsoup.clean(value, Safelist.none());
        }
    }

    private record ExperimentAssignment(NoticeExperiment experiment,
                                        List<NoticeExperimentVariant> variants,
                                        Map<Long, NoticeExperimentVariant> variantsById) {
        ExperimentAssignment(NoticeExperiment experiment, List<NoticeExperimentVariant> variants) {
            this(experiment, variants, variants.stream()
                .filter(variant -> variant.id != null)
                .collect(Collectors.toMap(variant -> variant.id, Function.identity(),
                    (left, right) -> left, LinkedHashMap::new)));
        }

        NoticeExperimentVariant variantFor(int index, int total) {
            int bucket = (int) (((long) index * 100) / total);
            int cumulative = 0;
            for (NoticeExperimentVariant variant : variants) {
                cumulative += variant.allocationPercent == null ? 0 : variant.allocationPercent;
                if (bucket < cumulative) {
                    return variant;
                }
            }
            return variants.get(variants.size() - 1);
        }

        NoticeExperimentVariant variantById(Long id) {
            return variantsById.get(id);
        }
    }

}
