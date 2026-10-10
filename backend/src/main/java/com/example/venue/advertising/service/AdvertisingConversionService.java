package com.example.venue.advertising.service;

import com.example.venue.advertising.entity.AdvertisingAttribution;
import com.example.venue.advertising.entity.AdvertisingConversionCallback;
import com.example.venue.advertising.entity.AdvertisingEvent;
import com.example.venue.advertising.mapper.AdvertisingAttributionMapper;
import com.example.venue.advertising.mapper.AdvertisingConversionCallbackMapper;
import com.example.venue.advertising.mapper.AdvertisingConversionGuardMapper;
import com.example.venue.advertising.mapper.AdvertisingEventMapper;
import com.example.venue.common.BusinessException;
import com.example.venue.order.service.OrderSubmittedEvent;
import com.example.venue.reservation.service.ReservationLifecycleChangedEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Stores minimal business outbox facts and independently computes advertising attribution.
 * No amount, order payload, or business mapper is read here.
 */
@Service
public class AdvertisingConversionService {
    public static final String ATTRIBUTION_RULE_VERSION = "ADVERTISING_ATTRIBUTION_V1";
    public static final int ATTRIBUTION_WINDOW_DAYS = 7;
    public static final String ATTRIBUTED = "ATTRIBUTED";
    public static final String GAP = "GAP";
    public static final String REVOKED = "REVOKED";

    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> REVOKING_STATUSES = Set.of(
        "REJECTED", "CANCELED", "EXPIRED", "REFUNDING", "REFUNDED");
    private static final int RETRY_LIMIT = 50;

    private final AdvertisingConversionCallbackMapper callbackMapper;
    private final AdvertisingConversionGuardMapper guardMapper;
    private final AdvertisingAttributionMapper attributionMapper;
    private final AdvertisingEventMapper eventMapper;
    private final AdvertisingPrivacyPreferenceService privacyService;

    public AdvertisingConversionService(AdvertisingConversionCallbackMapper callbackMapper,
                                        AdvertisingAttributionMapper attributionMapper,
                                        AdvertisingEventMapper eventMapper,
                                        AdvertisingPrivacyPreferenceService privacyService,
                                        AdvertisingConversionGuardMapper guardMapper) {
        this.callbackMapper = callbackMapper;
        this.attributionMapper = attributionMapper;
        this.eventMapper = eventMapper;
        this.privacyService = privacyService;
        this.guardMapper = guardMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accept(ConversionCommand command) {
        validate(command);
        lockReservation(command.userId(), command.reservationId());
        AdvertisingConversionCallback callback = insertOrReplay(command);
        if ("PROCESSED".equals(callback.status)) {
            return;
        }
        process(callback);
    }

    /**
     * 在业务事务中持久化最小回连事实；归因处理仍在提交后独立执行。
     * 执行器拒绝或进程重启时可由扫描任务恢复 PENDING 事实。
     */
    @Transactional
    public void persistPending(ConversionCommand command) {
        validate(command);
        insertOrReplay(command);
    }

    // Claim with the unique key before any missing-row locking read. Two different
    // absent keys may share an InnoDB gap; SELECT FOR UPDATE then INSERT can deadlock
    // and lose the post-commit fact before it ever reaches the retry queue.
    private AdvertisingConversionCallback insertOrReplay(ConversionCommand command) {
        AdvertisingConversionCallback callback = createCallback(command);
        try {
            callbackMapper.insert(callback);
            return callback;
        } catch (DuplicateKeyException duplicate) {
            AdvertisingConversionCallback existing = callbackMapper.findByEventKeyForUpdate(command.eventKey());
            if (existing == null) throw duplicate;
            return existing;
        }
    }

    /**
     * 执行器拒绝或 worker 入口异常时，将已经存在的 outbox 事实转为可扫描的待重试状态。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPendingRetry(String eventKey) {
        if (eventKey == null || eventKey.isBlank()) {
            return;
        }
        AdvertisingConversionCallback callback = callbackMapper.findByEventKeyForUpdate(eventKey);
        if (callback == null || "PROCESSED".equals(callback.status)) {
            return;
        }
        markPendingRetry(callback);
    }

    private void lockReservation(Long userId, Long reservationId) {
        guardMapper.ensureScope(userId, reservationId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryPending() {
        List<AdvertisingConversionCallback> callbacks = callbackMapper.findPendingRetry(LocalDateTime.now(), RETRY_LIMIT);
        for (AdvertisingConversionCallback callback : callbacks) {
            lockReservation(callback.userId, callback.reservationId);
            AdvertisingConversionCallback locked = callbackMapper.findByEventKeyForUpdate(callback.eventKey);
            if (locked != null && retryable(locked.status)) {
                process(locked);
            }
        }
    }

    private void process(AdvertisingConversionCallback callback) {
        try {
            if (isStale(callback)) {
                markProcessed(callback, "STALE_EVENT_IGNORED");
                return;
            }
            String resultCode = isSubmission(callback.eventType)
                ? resolveSubmission(callback)
                : resolveRevocation(callback);
            markProcessed(callback, resultCode);
        } catch (RuntimeException exception) {
            markPendingRetry(callback);
        }
    }

    private String resolveSubmission(AdvertisingConversionCallback callback) {
        AdvertisingAttribution attribution = attributionMapper.findByReservationIdForUpdate(callback.reservationId);
        if (attribution != null && REVOKED.equals(attribution.status)) {
            return "ATTRIBUTION_ALREADY_REVOKED";
        }
        if (attribution != null && ATTRIBUTED.equals(attribution.status)) {
            return "ATTRIBUTION_ALREADY_EXISTS";
        }

        boolean measurementEnabled = privacyService.lockForAdvertisingFact(callback.userId);
        AdvertisingEvent click = measurementEnabled
            ? eventMapper.findLatestAcceptedClickForAttribution(
                callback.userId,
                callback.occurredAt.minusDays(ATTRIBUTION_WINDOW_DAYS),
                callback.occurredAt)
            : null;
        String policyVersion = click == null
            ? callback.policyVersion
            : firstNonBlank(click.policyVersion, callback.policyVersion);
        String reasonCode;
        if (!measurementEnabled) {
            reasonCode = "PRIVACY_DISABLED";
        } else if (click == null) {
            reasonCode = "NO_VALID_CLICK";
        } else {
            reasonCode = "LAST_VALID_CLICK_7D";
        }

        if (attribution == null) {
            attribution = newAttribution(callback, policyVersion);
        }
        applySubmission(attribution, callback, click, policyVersion, reasonCode);
        if (attribution.id == null) {
            attributionMapper.insert(attribution);
        } else {
            attributionMapper.updateById(attribution);
        }
        return click == null ? reasonCode : "ATTRIBUTED";
    }

    private String resolveRevocation(AdvertisingConversionCallback callback) {
        AdvertisingAttribution attribution = attributionMapper.findByReservationIdForUpdate(callback.reservationId);
        if (attribution == null) {
            attribution = newAttribution(callback, callback.policyVersion);
            attribution.status = GAP;
            attribution.reasonCode = "NO_PRIOR_ATTRIBUTION";
            attribution.sourceStatus = callback.sourceStatus;
            attribution.sourceEventKey = callback.eventKey;
            attributionMapper.insert(attribution);
            return "NO_PRIOR_ATTRIBUTION";
        }
        if (ATTRIBUTED.equals(attribution.status)) {
            attribution.status = REVOKED;
            attribution.reasonCode = revocationReason(callback.sourceStatus);
            attribution.sourceStatus = callback.sourceStatus;
            attribution.sourceEventKey = callback.eventKey;
            attribution.revokedAt = LocalDateTime.now();
            attribution.conversionOccurredAt = callback.occurredAt;
            attribution.updateTime = attribution.revokedAt;
            attributionMapper.updateById(attribution);
            return "ATTRIBUTION_REVOKED";
        }
        attribution.conversionOccurredAt = callback.occurredAt;
        attribution.sourceStatus = callback.sourceStatus;
        attribution.sourceEventKey = callback.eventKey;
        attribution.updateTime = LocalDateTime.now();
        attributionMapper.updateById(attribution);
        return "UNATTRIBUTED_LIFECYCLE_GAP";
    }

    private AdvertisingAttribution newAttribution(AdvertisingConversionCallback callback, String policyVersion) {
        AdvertisingAttribution attribution = new AdvertisingAttribution();
        attribution.attributionNo = nextNo("AAT");
        attribution.reservationId = callback.reservationId;
        attribution.userId = callback.userId;
        attribution.conversionOccurredAt = callback.occurredAt;
        attribution.policyVersion = policyVersion;
        attribution.ruleVersionSnapshot = ATTRIBUTION_RULE_VERSION;
        attribution.status = GAP;
        attribution.reasonCode = "NO_VALID_CLICK";
        attribution.sourceStatus = callback.sourceStatus;
        attribution.sourceEventKey = callback.eventKey;
        attribution.createTime = LocalDateTime.now();
        attribution.updateTime = attribution.createTime;
        return attribution;
    }

    private void applySubmission(AdvertisingAttribution attribution,
                                 AdvertisingConversionCallback callback,
                                 AdvertisingEvent click,
                                 String policyVersion,
                                 String reasonCode) {
        attribution.userId = callback.userId;
        attribution.conversionOccurredAt = callback.occurredAt;
        attribution.policyVersion = policyVersion;
        attribution.ruleVersionSnapshot = ATTRIBUTION_RULE_VERSION;
        attribution.sourceStatus = callback.sourceStatus;
        attribution.sourceEventKey = callback.eventKey;
        attribution.reasonCode = reasonCode;
        attribution.revokedAt = null;
        attribution.updateTime = LocalDateTime.now();
        if (click == null) {
            attribution.status = GAP;
            attribution.clickEventId = null;
            attribution.decisionId = null;
            attribution.advertiserId = null;
            attribution.campaignId = null;
            attribution.creativeVersionId = null;
            attribution.clickOccurredAt = null;
            return;
        }
        attribution.status = ATTRIBUTED;
        attribution.clickEventId = click.id;
        attribution.decisionId = click.decisionId;
        attribution.advertiserId = click.advertiserId;
        attribution.campaignId = click.campaignId;
        attribution.creativeVersionId = click.creativeVersionId;
        attribution.clickOccurredAt = click.occurredAt;
    }

    private void markPendingRetry(AdvertisingConversionCallback callback) {
        LocalDateTime now = LocalDateTime.now();
        int attempts = callback.attemptCount == null ? 0 : callback.attemptCount;
        attempts = Math.min(attempts + 1, 30);
        long delayMinutes = Math.min(60L, 1L << Math.min(attempts, 6));
        callback.status = "PENDING_RETRY";
        callback.resultCode = "ADVERTISING_CALLBACK_FAILED";
        callback.attemptCount = attempts;
        callback.nextRetryAt = now.plusMinutes(delayMinutes);
        callback.updateTime = now;
        callbackMapper.updateById(callback);
    }

    private void markProcessed(AdvertisingConversionCallback callback, String resultCode) {
        LocalDateTime now = LocalDateTime.now();
        callback.status = "PROCESSED";
        callback.resultCode = resultCode;
        callback.processedAt = now;
        callback.nextRetryAt = null;
        callback.updateTime = now;
        callbackMapper.updateById(callback);
    }

    private AdvertisingConversionCallback createCallback(ConversionCommand command) {
        AdvertisingConversionCallback callback = new AdvertisingConversionCallback();
        LocalDateTime now = LocalDateTime.now();
        callback.callbackNo = nextNo("ACB");
        callback.eventKey = command.eventKey();
        callback.eventType = command.eventType();
        callback.reservationId = command.reservationId();
        callback.userId = command.userId();
        callback.venueId = command.venueId();
        callback.sourceStatus = command.sourceStatus();
        callback.policyVersion = privacyService.policyVersion(command.userId());
        // V26 stores second-precision DATETIME values. Normalize before both the
        // outbox insert and in-process processing so ordering stays stable after
        // the fact is read back from MySQL.
        callback.occurredAt = command.occurredAt().truncatedTo(ChronoUnit.SECONDS);
        callback.status = "PENDING";
        callback.attemptCount = 0;
        callback.nextRetryAt = now;
        callback.createTime = now;
        callback.updateTime = now;
        return callback;
    }

    private boolean isStale(AdvertisingConversionCallback callback) {
        AdvertisingAttribution attribution = attributionMapper.findByReservationIdForUpdate(callback.reservationId);
        if (attribution == null || attribution.conversionOccurredAt == null
            || callback.occurredAt == null) {
            return false;
        }
        int timeOrder = callback.occurredAt.compareTo(attribution.conversionOccurredAt);
        if (timeOrder < 0) {
            return true;
        }
        if (timeOrder > 0 || Objects.equals(attribution.sourceEventKey, callback.eventKey)) {
            return false;
        }
        // Reservation revocation wins a timestamp tie over a late submission. This is
        // conservative when DATETIME precision collapses two lifecycle log timestamps.
        if (isSubmission(callback.eventType)) {
            return true;
        }
        return REVOKED.equals(attribution.status);
    }

    private boolean retryable(String status) {
        return "PENDING".equals(status) || "PENDING_RETRY".equals(status);
    }

    private boolean isSubmission(String eventType) {
        return "RESERVATION_SUBMITTED".equals(eventType) || "ORDER_SUBMITTED".equals(eventType);
    }

    private String revocationReason(String sourceStatus) {
        return switch (sourceStatus) {
            case "REJECTED" -> "RESERVATION_REJECTED";
            case "CANCELED" -> "RESERVATION_CANCELED";
            case "EXPIRED" -> "RESERVATION_EXPIRED";
            case "REFUNDING" -> "REFUND_REQUESTED";
            case "REFUNDED" -> "REFUND_SUCCEEDED";
            default -> "RESERVATION_REVOKED";
        };
    }

    private String nextNo(String prefix) {
        return prefix + "-" + LocalDateTime.now().format(NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private void validate(ConversionCommand command) {
        if (command == null || command.eventKey() == null || command.eventKey().isBlank()
            || command.eventType() == null || command.reservationId() == null || command.reservationId() <= 0
            || command.userId() == null || command.userId() <= 0 || command.sourceStatus() == null
            || command.occurredAt() == null) {
            throw BusinessException.badRequest("广告回连事实不完整");
        }
        if (!Set.of("RESERVATION_SUBMITTED", "ORDER_SUBMITTED", "RESERVATION_REVOKED")
            .contains(command.eventType())) {
            throw BusinessException.badRequest("广告回连事件类型不支持");
        }
        if ("RESERVATION_REVOKED".equals(command.eventType())
            && !REVOKING_STATUSES.contains(command.sourceStatus())) {
            throw BusinessException.badRequest("广告撤销状态不支持");
        }
    }

    public record ConversionCommand(
        String eventKey,
        String eventType,
        Long reservationId,
        Long userId,
        Long venueId,
        String sourceStatus,
        LocalDateTime occurredAt
    ) {
        public static ConversionCommand from(ReservationLifecycleChangedEvent event, String eventType) {
            return new ConversionCommand(
                event.eventKey(), eventType, event.reservationId(), event.userId(), event.venueId(),
                event.submitted() ? event.toStatus() : event.toStatus(), event.occurredAt());
        }

        public static ConversionCommand from(OrderSubmittedEvent event) {
            return new ConversionCommand(
                event.eventKey(), "ORDER_SUBMITTED", event.reservationId(), event.userId(), event.venueId(),
                event.sourceStatus(), event.occurredAt());
        }
    }
}
