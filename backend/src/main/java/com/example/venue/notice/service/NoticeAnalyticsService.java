package com.example.venue.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.BusinessException;
import com.example.venue.common.PageResult;
import com.example.venue.notice.dto.NoticeAudienceResponse;
import com.example.venue.notice.dto.NoticeDeliveryBatchResponse;
import com.example.venue.notice.dto.NoticeDeliveryOutboxResponse;
import com.example.venue.notice.dto.NoticeDeliveryOverviewResponse;
import com.example.venue.notice.dto.NoticeDeliveryQuery;
import com.example.venue.notice.dto.NoticeExperimentResultResponse;
import com.example.venue.notice.dto.NotificationProviderOptionResponse;
import com.example.venue.notice.entity.NoticeAudienceSnapshot;
import com.example.venue.notice.entity.NoticeDeliveryBatch;
import com.example.venue.notice.entity.NoticeEmailDelivery;
import com.example.venue.notice.entity.NoticeExperiment;
import com.example.venue.notice.entity.NoticeExperimentVariant;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.mapper.NoticeAudienceSnapshotMapper;
import com.example.venue.notice.mapper.NoticeDeliveryBatchMapper;
import com.example.venue.notice.mapper.NoticeEmailDeliveryMapper;
import com.example.venue.notice.mapper.NoticeExperimentMapper;
import com.example.venue.notice.mapper.NoticeExperimentVariantMapper;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.provider.NotificationProvider;
import com.example.venue.notice.provider.NotificationProviderDescriptor;
import com.example.venue.notice.provider.NotificationProviderRouter;
import com.example.venue.provider.dto.ProviderOverviewResponse;
import com.example.venue.provider.dto.ProviderStatusResponse;
import com.example.venue.provider.service.ProviderAdminService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class NoticeAnalyticsService {
    private static final BigDecimal ZERO_RATE = new BigDecimal("0.0000");
    private static final List<String> RETRYABLE_OUTBOX_STATUSES = List.of("FAILED", "RETRY_WAITING");
    private static final String EMAIL_DATA_GAP = "Mailpit 仅证明 SMTP 接受，不证明互联网送达或退信。";

    private final NoticeDeliveryBatchMapper batchMapper;
    private final NoticeAudienceSnapshotMapper audienceMapper;
    private final NoticeOutboxMapper outboxMapper;
    private final NoticeEmailDeliveryMapper emailDeliveryMapper;
    private final NoticeExperimentMapper experimentMapper;
    private final NoticeExperimentVariantMapper variantMapper;
    private final NotificationProviderRouter providerRouter;
    private final ProviderAdminService providerAdminService;

    public NoticeAnalyticsService(NoticeDeliveryBatchMapper batchMapper,
                                  NoticeAudienceSnapshotMapper audienceMapper,
                                  NoticeOutboxMapper outboxMapper,
                                  NoticeEmailDeliveryMapper emailDeliveryMapper,
                                  NoticeExperimentMapper experimentMapper,
                                  NoticeExperimentVariantMapper variantMapper,
                                  NotificationProviderRouter providerRouter,
                                  ProviderAdminService providerAdminService) {
        this.batchMapper = batchMapper;
        this.audienceMapper = audienceMapper;
        this.outboxMapper = outboxMapper;
        this.emailDeliveryMapper = emailDeliveryMapper;
        this.experimentMapper = experimentMapper;
        this.variantMapper = variantMapper;
        this.providerRouter = providerRouter;
        this.providerAdminService = providerAdminService;
    }

    public NoticeDeliveryOverviewResponse overview(NoticeDeliveryQuery query) {
        ResolvedDeliveryQuery resolved = resolvedQuery(query);
        NoticeDeliveryQuery safeQuery = resolved.query();
        boolean inApp = isChannel(resolved.descriptor(), "IN_APP");
        boolean email = isChannel(resolved.descriptor(), "EMAIL");
        List<NoticeDeliveryBatch> batches = inApp
            ? safeList(batchMapper.selectList(batchWrapper(safeQuery)))
            : List.of();
        List<NoticeAudienceSnapshot> audiences = inApp
            ? audiencesForBatches(batchIds(batches), safeQuery)
            : List.of();
        List<NoticeDeliveryBatch> matchedBatches = inApp
            ? matchingBatches(batches, audiences, safeQuery)
            : List.of();
        List<NoticeOutbox> outboxes = email
            ? safeList(outboxMapper.selectList(outboxWrapper(safeQuery).orderByDesc("id")))
            : List.of();
        Map<Long, NoticeEmailDelivery> emailDeliveries = emailDeliveriesByOutbox(outboxes);

        NoticeDeliveryOverviewResponse response = new NoticeDeliveryOverviewResponse();
        response.batchCount = matchedBatches.size();
        response.audienceCount = audiences.size();
        response.sentCount = countDelivery(audiences, "SENT");
        response.failedCount = countDelivery(audiences, "FAILED");
        response.readCount = countRead(audiences, "READ");
        response.unreadCount = countRead(audiences, "UNREAD");
        response.readRate = rate(response.readCount, response.audienceCount);
        response.failureReasonCounts = inApp ? failureReasonCounts(audiences, safeQuery) : new LinkedHashMap<>();
        if (email) {
            response.acceptedCount = countAccepted(outboxes, emailDeliveries);
            response.retryWaitingCount = countOutboxStatus(outboxes, "RETRY_WAITING");
            response.failedCount = countOutboxStatus(outboxes, "FAILED");
            response.dataGaps.add(EMAIL_DATA_GAP);
        }
        response.providerEnv = resolved.descriptor().providerEnv();
        response.providerName = resolved.descriptor().providerName();
        response.configVersion = resolved.descriptor().configVersion();
        response.channel = resolved.descriptor().channel();
        return response;
    }

    public PageResult<NoticeDeliveryBatchResponse> batches(NoticeDeliveryQuery query) {
        ResolvedDeliveryQuery resolved = resolvedQuery(query);
        NoticeDeliveryQuery safeQuery = resolved.query();
        if (!isChannel(resolved.descriptor(), "IN_APP")) {
            return PageResult.of(List.of(), 0, safeQuery.getPageNum(), safeQuery.getPageSize());
        }
        List<NoticeDeliveryBatch> candidates = safeList(batchMapper.selectList(
            batchWrapper(safeQuery).orderByDesc("id")));
        List<NoticeAudienceSnapshot> audiences = audiencesForBatches(batchIds(candidates), safeQuery);
        List<NoticeDeliveryBatch> matchedBatches = matchingBatches(candidates, audiences, safeQuery);
        Map<Long, List<NoticeAudienceSnapshot>> audiencesByBatch = audiencesByBatch(audiences);
        List<NoticeDeliveryBatchResponse> records = pageSlice(matchedBatches, safeQuery).stream()
            .map(batch -> toBatchResponse(batch, audiencesByBatch.getOrDefault(batch.id, List.of())))
            .toList();
        return PageResult.of(records, matchedBatches.size(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    public PageResult<NoticeDeliveryOutboxResponse> outboxes(NoticeDeliveryQuery query) {
        ResolvedDeliveryQuery resolved = resolvedOutboxQuery(query);
        NoticeDeliveryQuery safeQuery = resolved.query();
        Page<NoticeOutbox> page = outboxMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            outboxWrapper(safeQuery).orderByDesc("id"));
        List<NoticeOutbox> outboxes = safeList(page.getRecords());
        Map<Long, NoticeEmailDelivery> emailDeliveries = emailDeliveriesByOutbox(outboxes);
        List<NoticeDeliveryOutboxResponse> records = outboxes.stream()
            .map(outbox -> toOutboxResponse(outbox, emailDeliveries.get(outbox.id)))
            .toList();
        return PageResult.of(records, page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    public NoticeDeliveryBatchResponse batch(Long id) {
        NoticeDeliveryBatch batch = requireBatch(id);
        NoticeDeliveryQuery query = resolvedQuery(new NoticeDeliveryQuery()).query();
        return toBatchResponse(batch, audiencesForBatches(List.of(batch.id), query));
    }

    public PageResult<NoticeAudienceResponse> audiences(Long batchId, NoticeDeliveryQuery query) {
        NoticeDeliveryBatch batch = requireBatch(batchId);
        NoticeDeliveryQuery safeQuery = resolvedQuery(query).query();
        Page<NoticeAudienceSnapshot> page = audienceMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            audienceWrapper(batch.id, safeQuery).orderByDesc("id"));
        List<NoticeAudienceSnapshot> audienceRecords = safeList(page.getRecords());
        Map<Long, Long> outboxIdsByAudience = retryableOutboxIds(batch.id, audienceRecords, safeQuery);
        List<NoticeAudienceResponse> records = audienceRecords.stream()
            .map(audience -> toAudienceResponse(audience, outboxIdsByAudience.get(audience.id)))
            .toList();
        return PageResult.of(records, page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    public NoticeExperimentResultResponse experimentResults(Long id, NoticeDeliveryQuery requestedQuery) {
        NoticeExperiment experiment = experimentMapper.selectById(id);
        if (experiment == null || Integer.valueOf(1).equals(experiment.deleted)) {
            throw BusinessException.notFound("公告实验不存在");
        }
        ResolvedDeliveryQuery resolved = resolvedQuery(requestedQuery);
        NoticeDeliveryQuery query = resolved.query();
        query.experimentId = id;
        boolean inApp = isChannel(resolved.descriptor(), "IN_APP");
        List<NoticeDeliveryBatch> batches = inApp
            ? safeList(batchMapper.selectList(batchWrapper(query)))
            : List.of();
        List<NoticeAudienceSnapshot> audiences = inApp
            ? audiencesForBatches(batchIds(batches), query)
            : List.of();
        NoticeExperimentResultResponse response = new NoticeExperimentResultResponse();
        response.experimentId = experiment.id;
        response.experimentNo = experiment.experimentNo;
        response.name = experiment.name;
        response.status = experiment.status;
        response.targetMetric = experiment.targetMetric;
        response.sampleScope = experiment.sampleScope;
        response.providerKey = resolved.descriptor().providerKey();
        response.providerEnv = resolved.descriptor().providerEnv();
        response.providerName = resolved.descriptor().providerName();
        response.channel = resolved.descriptor().channel();
        response.displayName = resolved.descriptor().displayName();
        response.configVersion = resolved.descriptor().configVersion();
        response.audienceCount = audiences.size();
        response.sentCount = countDelivery(audiences, "SENT");
        response.failedCount = countDelivery(audiences, "FAILED");
        response.readCount = countRead(audiences, "READ");
        response.readRate = rate(response.readCount, response.audienceCount);

        List<NoticeExperimentVariant> variants = safeList(variantMapper.findByExperimentId(id));
        for (NoticeExperimentVariant variant : variants) {
            response.variants.add(toVariantResult(variant, audiences));
        }
        return response;
    }

    public List<NotificationProviderOptionResponse> providerOptions() {
        String defaultProviderKey = defaultProvider().descriptor().providerKey();
        Map<String, ProviderStatusResponse> dynamicProviders = dynamicMessageProviders();
        return providerRouter.catalog().stream()
            .map(descriptor -> toProviderOption(
                descriptor, dynamicProviders.get(descriptor.providerKey()), defaultProviderKey))
            .toList();
    }

    private NotificationProviderOptionResponse toProviderOption(NotificationProviderDescriptor descriptor,
                                                                 ProviderStatusResponse status,
                                                                 String defaultProviderKey) {
        String providerKey = status == null ? descriptor.providerKey() : textOr(status.providerKey, descriptor.providerKey());
        String providerDomain = status == null
            ? descriptor.providerDomain()
            : textOr(status.providerDomain, descriptor.providerDomain());
        String providerEnv = status == null ? descriptor.providerEnv() : textOr(status.providerEnv, descriptor.providerEnv());
        String providerName = status == null
            ? descriptor.providerName()
            : textOr(status.providerName, descriptor.providerName());
        String displayName = status == null
            ? descriptor.displayName()
            : textOr(status.displayName, descriptor.displayName());
        String configVersion = status == null
            ? descriptor.configVersion()
            : textOr(status.configVersion, descriptor.configVersion());
        boolean enabled = status == null ? descriptor.enabled() : status.enabled;
        return new NotificationProviderOptionResponse(
            providerKey,
            providerDomain,
            providerEnv,
            providerName,
            descriptor.channel(),
            displayName,
            configVersion,
            enabled,
            enabled && Objects.equals(defaultProviderKey, providerKey));
    }

    private Map<String, ProviderStatusResponse> dynamicMessageProviders() {
        ProviderOverviewResponse overview = providerAdminService.overview();
        List<ProviderStatusResponse> statuses = overview == null ? List.of() : safeList(overview.providers);
        Map<String, ProviderStatusResponse> byKey = new LinkedHashMap<>();
        for (ProviderStatusResponse status : statuses) {
            if (status != null && "MESSAGE".equals(status.providerDomain)
                && status.providerKey != null && !status.providerKey.isBlank()) {
                byKey.put(status.providerKey, status);
            }
        }
        return byKey;
    }

    private NoticeExperimentResultResponse.VariantResult toVariantResult(
        NoticeExperimentVariant variant, List<NoticeAudienceSnapshot> audiences) {
        List<NoticeAudienceSnapshot> variantAudiences = audiences.stream()
            .filter(audience -> Objects.equals(audience.experimentVariantId, variant.id))
            .toList();
        NoticeExperimentResultResponse.VariantResult result = new NoticeExperimentResultResponse.VariantResult();
        result.variantId = variant.id;
        result.variantCode = variant.variantCode;
        result.title = variant.title;
        result.allocationPercent = variant.allocationPercent;
        result.audienceCount = variantAudiences.size();
        result.sentCount = countDelivery(variantAudiences, "SENT");
        result.failedCount = countDelivery(variantAudiences, "FAILED");
        result.readCount = countRead(variantAudiences, "READ");
        result.readRate = rate(result.readCount, result.audienceCount);
        return result;
    }

    private NoticeDeliveryBatch requireBatch(Long id) {
        if (id == null) {
            throw BusinessException.badRequest("公告投递批次不存在");
        }
        NoticeDeliveryBatch batch = batchMapper.selectById(id);
        if (batch == null || Integer.valueOf(1).equals(batch.deleted)) {
            throw BusinessException.notFound("公告投递批次不存在");
        }
        return batch;
    }

    private NoticeDeliveryBatchResponse toBatchResponse(NoticeDeliveryBatch batch,
                                                        List<NoticeAudienceSnapshot> audiences) {
        NoticeDeliveryBatchResponse response = new NoticeDeliveryBatchResponse();
        response.batchId = batch.id;
        response.batchNo = batch.batchNo;
        response.noticeId = batch.noticeId;
        response.targetScope = batch.targetScope;
        response.audienceCount = nullToZero(batch.audienceCount);
        response.deliverableCount = nullToZero(batch.deliverableCount);
        response.excludedCount = nullToZero(batch.excludedCount);
        response.status = batch.status;
        response.providerEnv = batch.providerEnv;
        response.providerName = batch.providerName;
        response.configVersion = batch.configVersion;
        response.sourceType = batch.sourceType;
        response.publisherId = batch.publisherId;
        response.experimentId = batch.experimentId;
        response.createTime = batch.createTime;
        response.updateTime = batch.updateTime;

        response.sentCount = countDelivery(audiences, "SENT");
        response.failedCount = countDelivery(audiences, "FAILED");
        response.readCount = countRead(audiences, "READ");
        response.unreadCount = countRead(audiences, "UNREAD");
        return response;
    }

    private NoticeAudienceResponse toAudienceResponse(NoticeAudienceSnapshot audience, Long outboxId) {
        NoticeAudienceResponse response = new NoticeAudienceResponse();
        response.audienceId = audience.id;
        response.batchId = audience.batchId;
        response.noticeId = audience.noticeId;
        response.userId = audience.userId;
        response.venueId = audience.venueId;
        response.memberLevelId = audience.memberLevelId;
        response.segmentCode = audience.segmentCode;
        response.experimentVariantId = audience.experimentVariantId;
        response.deliveryStatus = audience.deliveryStatus;
        response.readStatus = audience.readStatus;
        response.excludeReason = audience.excludeReason;
        response.messageId = audience.messageId;
        response.lastDeliveryLogId = audience.lastDeliveryLogId;
        response.outboxId = outboxId;
        response.readTime = audience.readTime;
        response.createTime = audience.createTime;
        response.updateTime = audience.updateTime;
        return response;
    }

    private NoticeDeliveryOutboxResponse toOutboxResponse(NoticeOutbox outbox,
                                                           NoticeEmailDelivery emailDelivery) {
        NoticeDeliveryOutboxResponse response = new NoticeDeliveryOutboxResponse();
        response.outboxId = outbox.id;
        response.outboxNo = outbox.outboxNo;
        response.eventType = outbox.eventType;
        response.recipientKind = outbox.recipientKind;
        response.maskedRecipient = emailDelivery == null ? null : emailDelivery.recipientMasked;
        response.channel = outbox.channel;
        response.providerKey = outbox.providerKey;
        response.providerEnv = outbox.providerEnv;
        response.providerName = outbox.providerName;
        response.configVersion = outbox.configVersion;
        response.templateKey = outbox.templateKey;
        response.templateVersion = outbox.templateVersion;
        response.status = outbox.status;
        response.statusText = "EMAIL".equals(outbox.channel) && "SENT".equals(outbox.status)
            ? "SMTP 已接受"
            : outbox.status;
        response.acceptanceStatus = emailDelivery == null ? null : emailDelivery.acceptanceStatus;
        response.downstreamStatus = emailDelivery == null ? null : emailDelivery.downstreamStatus;
        response.attemptCount = outbox.attemptCount;
        response.nextRetryTime = outbox.nextRetryTime;
        response.failureCode = outbox.lastErrorCode;
        response.createTime = outbox.createTime;
        response.updateTime = outbox.updateTime;
        response.acceptedAt = emailDelivery == null ? null : emailDelivery.acceptedAt;
        return response;
    }

    private Map<Long, Long> retryableOutboxIds(Long batchId,
                                               List<NoticeAudienceSnapshot> audiences,
                                               NoticeDeliveryQuery query) {
        List<Long> audienceIds = audiences.stream()
            .map(audience -> audience.id)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (batchId == null || audienceIds.isEmpty()) {
            return Map.of();
        }
        QueryWrapper<NoticeOutbox> wrapper = new QueryWrapper<NoticeOutbox>()
            .eq("deleted", 0)
            .eq("batch_id", batchId)
            .eq("provider_env", query.providerEnv())
            .eq("provider_name", query.providerName())
            .in("audience_id", audienceIds)
            .in("status", RETRYABLE_OUTBOX_STATUSES);
        Map<Long, Long> outboxIdsByAudience = new LinkedHashMap<>();
        for (NoticeOutbox outbox : safeList(outboxMapper.selectList(wrapper))) {
            if (outbox.audienceId != null && outbox.id != null) {
                outboxIdsByAudience.merge(outbox.audienceId, outbox.id, Long::max);
            }
        }
        return outboxIdsByAudience;
    }

    private QueryWrapper<NoticeDeliveryBatch> batchWrapper(NoticeDeliveryQuery query) {
        QueryWrapper<NoticeDeliveryBatch> wrapper = new QueryWrapper<NoticeDeliveryBatch>()
            .eq("deleted", 0)
            .eq("provider_env", query.providerEnv())
            .eq("provider_name", query.providerName());
        if (query.batchId != null) {
            wrapper.eq("id", query.batchId);
        }
        if (query.noticeId != null) {
            wrapper.eq("notice_id", query.noticeId);
        }
        if (query.experimentId != null) {
            wrapper.eq("experiment_id", query.experimentId);
        }
        if (query.normalizedStatus() != null) {
            wrapper.eq("status", query.normalizedStatus());
        }
        addTimeRange(wrapper, query);
        return wrapper;
    }

    private QueryWrapper<NoticeOutbox> outboxWrapper(NoticeDeliveryQuery query) {
        QueryWrapper<NoticeOutbox> wrapper = new QueryWrapper<NoticeOutbox>()
            .eq("deleted", 0)
            .eq("provider_env", query.providerEnv())
            .eq("provider_name", query.providerName());
        if (query.normalizedChannel() != null) {
            wrapper.eq("channel", query.normalizedChannel());
        }
        if (query.batchId != null) {
            wrapper.eq("batch_id", query.batchId);
        }
        if (query.noticeId != null) {
            wrapper.eq("notice_id", query.noticeId);
        }
        if (query.userId != null) {
            wrapper.eq("user_id", query.userId);
        }
        if (query.normalizedStatus() != null) {
            wrapper.eq("status", query.normalizedStatus());
        }
        addTimeRange(wrapper, query);
        return wrapper;
    }

    private QueryWrapper<NoticeAudienceSnapshot> audienceWrapper(Long batchId, NoticeDeliveryQuery query) {
        QueryWrapper<NoticeAudienceSnapshot> wrapper = new QueryWrapper<NoticeAudienceSnapshot>()
            .eq("deleted", 0)
            .eq("batch_id", batchId);
        if (query.noticeId != null) {
            wrapper.eq("notice_id", query.noticeId);
        }
        if (query.userId != null) {
            wrapper.eq("user_id", query.userId);
        }
        if (query.normalizedDeliveryStatus() != null) {
            wrapper.eq("delivery_status", query.normalizedDeliveryStatus());
        }
        if (query.normalizedReadStatus() != null) {
            wrapper.eq("read_status", query.normalizedReadStatus());
        }
        addTimeRange(wrapper, query);
        return wrapper;
    }

    private List<NoticeAudienceSnapshot> audiencesForBatches(List<Long> batchIds, NoticeDeliveryQuery query) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        QueryWrapper<NoticeAudienceSnapshot> wrapper = new QueryWrapper<NoticeAudienceSnapshot>()
            .eq("deleted", 0)
            .in("batch_id", batchIds);
        if (query.noticeId != null) {
            wrapper.eq("notice_id", query.noticeId);
        }
        if (query.userId != null) {
            wrapper.eq("user_id", query.userId);
        }
        if (query.normalizedDeliveryStatus() != null) {
            wrapper.eq("delivery_status", query.normalizedDeliveryStatus());
        }
        if (query.normalizedReadStatus() != null) {
            wrapper.eq("read_status", query.normalizedReadStatus());
        }
        addTimeRange(wrapper, query);
        return safeList(audienceMapper.selectList(wrapper));
    }

    private Map<String, Long> failureReasonCounts(List<NoticeAudienceSnapshot> audiences, NoticeDeliveryQuery query) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (NoticeAudienceSnapshot audience : audiences) {
            if (audience.excludeReason != null && !audience.excludeReason.isBlank()) {
                counts.merge(audience.excludeReason, 1L, Long::sum);
            }
        }
        if (audiences.isEmpty()) {
            return counts;
        }
        List<Long> batchIds = audiences.stream()
            .map(audience -> audience.batchId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        List<Long> audienceIds = audiences.stream()
            .map(audience -> audience.id)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (batchIds.isEmpty() || audienceIds.isEmpty()) {
            return counts;
        }
        QueryWrapper<NoticeOutbox> wrapper = new QueryWrapper<NoticeOutbox>()
            .eq("deleted", 0)
            .eq("provider_env", query.providerEnv())
            .eq("provider_name", query.providerName())
            .in("batch_id", batchIds)
            .in("audience_id", audienceIds)
            .in("status", RETRYABLE_OUTBOX_STATUSES);
        addTimeRange(wrapper, query);
        List<NoticeOutbox> outboxes = safeList(outboxMapper.selectList(wrapper));
        for (NoticeOutbox outbox : outboxes) {
            counts.merge(failureReason(outbox), 1L, Long::sum);
        }
        return counts;
    }

    private String failureReason(NoticeOutbox outbox) {
        if (outbox.lastErrorCode != null && !outbox.lastErrorCode.isBlank()) {
            return outbox.lastErrorCode;
        }
        if (outbox.lastErrorMessage != null && !outbox.lastErrorMessage.isBlank()) {
            return outbox.lastErrorMessage;
        }
        return outbox.status == null ? "UNKNOWN" : outbox.status;
    }

    private long countDelivery(List<NoticeAudienceSnapshot> audiences, String status) {
        return audiences.stream()
            .filter(audience -> status.equals(audience.deliveryStatus))
            .count();
    }

    private long countRead(List<NoticeAudienceSnapshot> audiences, String status) {
        return audiences.stream()
            .filter(audience -> status.equals(audience.readStatus))
            .count();
    }

    private long countAccepted(List<NoticeOutbox> outboxes,
                               Map<Long, NoticeEmailDelivery> emailDeliveries) {
        return outboxes.stream()
            .filter(outbox -> "EMAIL".equals(outbox.channel) && "SENT".equals(outbox.status))
            .filter(outbox -> {
                NoticeEmailDelivery delivery = emailDeliveries.get(outbox.id);
                return delivery != null && "ACCEPTED".equals(delivery.acceptanceStatus);
            })
            .count();
    }

    private long countOutboxStatus(List<NoticeOutbox> outboxes, String status) {
        return outboxes.stream().filter(outbox -> status.equals(outbox.status)).count();
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return ZERO_RATE;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private boolean hasAudienceFilters(NoticeDeliveryQuery query) {
        return query.userId != null
            || query.normalizedDeliveryStatus() != null
            || query.normalizedReadStatus() != null;
    }

    private List<NoticeDeliveryBatch> matchingBatches(List<NoticeDeliveryBatch> batches,
                                                      List<NoticeAudienceSnapshot> audiences,
                                                      NoticeDeliveryQuery query) {
        if (!hasAudienceFilters(query)) {
            return batches;
        }
        Set<Long> matchedBatchIds = audiences.stream()
            .map(audience -> audience.batchId)
            .filter(Objects::nonNull)
            .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        return batches.stream()
            .filter(batch -> matchedBatchIds.contains(batch.id))
            .toList();
    }

    private Map<Long, List<NoticeAudienceSnapshot>> audiencesByBatch(List<NoticeAudienceSnapshot> audiences) {
        Map<Long, List<NoticeAudienceSnapshot>> byBatch = new LinkedHashMap<>();
        for (NoticeAudienceSnapshot audience : audiences) {
            if (audience.batchId != null) {
                byBatch.computeIfAbsent(audience.batchId, ignored -> new ArrayList<>()).add(audience);
            }
        }
        return byBatch;
    }

    private List<NoticeDeliveryBatch> pageSlice(List<NoticeDeliveryBatch> batches, NoticeDeliveryQuery query) {
        long pageNum = Math.max(1, query.getPageNum());
        long pageSize = Math.max(1, query.getPageSize());
        long start = (pageNum - 1) * pageSize;
        if (start >= batches.size()) {
            return List.of();
        }
        int from = (int) start;
        int to = (int) Math.min(start + pageSize, batches.size());
        return batches.subList(from, to);
    }

    private List<Long> batchIds(List<NoticeDeliveryBatch> batches) {
        return batches.stream()
            .map(batch -> batch.id)
            .filter(Objects::nonNull)
            .toList();
    }

    private ResolvedDeliveryQuery resolvedQuery(NoticeDeliveryQuery query) {
        NoticeDeliveryQuery safeQuery = query == null ? new NoticeDeliveryQuery() : query;
        NotificationProviderDescriptor descriptor = resolveProvider(
            safeQuery.providerEnv(), safeQuery.providerName()).descriptor();
        safeQuery.providerEnv = descriptor.providerEnv();
        safeQuery.providerName = descriptor.providerName();
        return new ResolvedDeliveryQuery(safeQuery, descriptor);
    }

    private ResolvedDeliveryQuery resolvedOutboxQuery(NoticeDeliveryQuery query) {
        NoticeDeliveryQuery safeQuery = query == null ? new NoticeDeliveryQuery() : query;
        NotificationProviderDescriptor descriptor;
        if (safeQuery.providerEnv() == null && safeQuery.providerName() == null) {
            descriptor = defaultProvider().descriptor();
        } else {
            descriptor = providerRouter.catalog().stream()
                .filter(candidate -> Objects.equals(candidate.providerEnv(), safeQuery.providerEnv()))
                .filter(candidate -> Objects.equals(candidate.providerName(), safeQuery.providerName()))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound(
                    "NOTICE_PROVIDER_NOT_FOUND: 通知 Provider 不存在"));
        }
        safeQuery.providerEnv = descriptor.providerEnv();
        safeQuery.providerName = descriptor.providerName();
        safeQuery.channel = descriptor.channel();
        return new ResolvedDeliveryQuery(safeQuery, descriptor);
    }

    private NotificationProvider resolveProvider(String providerEnv, String providerName) {
        if (providerEnv == null && providerName == null) {
            return defaultProvider();
        }
        return providerRouter.require(providerEnv, providerName);
    }

    private NotificationProvider defaultProvider() {
        return providerRouter.require(NoticeDeliveryService.PROVIDER_ENV, NoticeDeliveryService.PROVIDER_NAME);
    }

    private Map<Long, NoticeEmailDelivery> emailDeliveriesByOutbox(List<NoticeOutbox> outboxes) {
        List<Long> emailOutboxIds = outboxes.stream()
            .filter(outbox -> "EMAIL".equals(outbox.channel))
            .map(outbox -> outbox.id)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (emailOutboxIds.isEmpty()) {
            return Map.of();
        }
        List<NoticeEmailDelivery> deliveries = safeList(emailDeliveryMapper.selectList(
            new QueryWrapper<NoticeEmailDelivery>()
                .select("outbox_id", "recipient_masked", "acceptance_status", "downstream_status", "accepted_at")
                .in("outbox_id", emailOutboxIds)));
        Map<Long, NoticeEmailDelivery> byOutbox = new LinkedHashMap<>();
        for (NoticeEmailDelivery delivery : deliveries) {
            if (delivery.outboxId != null) {
                byOutbox.put(delivery.outboxId, delivery);
            }
        }
        return byOutbox;
    }

    private boolean isChannel(NotificationProviderDescriptor descriptor, String channel) {
        return channel.equals(descriptor.channel());
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record ResolvedDeliveryQuery(
        NoticeDeliveryQuery query,
        NotificationProviderDescriptor descriptor
    ) {
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private <T> void addTimeRange(QueryWrapper<T> wrapper, NoticeDeliveryQuery query) {
        if (query.startTime != null) {
            wrapper.ge("create_time", query.startTime);
        }
        if (query.endTime != null) {
            wrapper.lt("create_time", query.endTime);
        }
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
