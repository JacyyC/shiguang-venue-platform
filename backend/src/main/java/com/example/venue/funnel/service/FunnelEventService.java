package com.example.venue.funnel.service;

import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.enums.ProviderEnv;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.funnel.dto.FunnelEventBatchRequest;
import com.example.venue.funnel.dto.FunnelEventRequest;
import com.example.venue.funnel.dto.FunnelEventResponse;
import com.example.venue.funnel.entity.FunnelEvent;
import com.example.venue.funnel.entity.FunnelSession;
import com.example.venue.funnel.mapper.FunnelEventMapper;
import com.example.venue.funnel.mapper.FunnelSessionMapper;
import com.example.venue.common.Constants;
import com.example.venue.security.SecurityContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class FunnelEventService {
    private static final Logger log = LoggerFactory.getLogger(FunnelEventService.class);
    private static final String PROVIDER_ENV = "DEV";
    private static final String DEFAULT_SOURCE_CHANNEL = "WEB";
    private static final String DEFAULT_RESULT_STATUS = "SUCCESS";
    private static final String PROVIDER_NAME = "FUNNEL";
    private static final DateTimeFormatter EVENT_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    // A26-P2-24：批量漏斗事件条数上限，与 FunnelEventBatchRequest.events 上的 @Size(max=100) 对齐。
    // Service 层强制校验，即使 Controller 未加 @Valid 也能拒绝超长批量。
    private static final int MAX_BATCH_SIZE = 100;
    // A26-P2-24：occurredAt 时间窗下界（允许过去 7 天内的事件），上界为当前时间（不允许未来）。
    // 避免匿名端点伪造 occurredAt 把事件精确投放进管理端任意日期窗口。
    private static final long OCCURRED_AT_PAST_WINDOW_HOURS = 24L * 7;
    private static final Set<String> BROWSER_RECORDABLE_EVENT_NAMES = Set.of(
        "SEARCH_SUBMITTED",
        "SEARCH_RESULT_VIEWED",
        "VENUE_DETAIL_VIEWED",
        "SLOT_SELECTED",
        "PRICE_PREVIEW_SHOWN",
        "COUPON_SHOWN",
        "COUPON_SELECTED",
        "DROPOFF_RECORDED",
        "SHARE_CLICKED");
    private static final Set<String> SERVER_AUTHORITATIVE_EVENT_NAMES = Set.of(
        "FAVORITE_ADDED",
        "FAVORITE_REMOVED",
        "RESERVATION_SUBMITTED",
        "ORDER_CREATED",
        "PAYMENT_INITIATED",
        "PAYMENT_RESULT_RECORDED");
    private static final Set<String> BLOCKED_METADATA_KEYS = Set.of(
        "contactname", "contactphone", "phone", "mobile", "remark", "rawpayload",
        "rawuseragent", "useragent", "ip", "token", "password", "paymentcredential");
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{7,}");
    private static final Pattern IPV4_ADDRESS = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern USER_AGENT_TOKEN = Pattern.compile(
        "(?i)\\b(Mozilla/\\S+|AppleWebKit/\\S+|Chrome/\\S+|Safari/\\S+|Firefox/\\S+|Edg/\\S+)");
    private static final String SENSITIVE_VALUE_KEY_ALTERNATIVES =
        "authorization|api[_-]?key|api\\s+key|access[_-]?token|refresh[_-]?token|id[_-]?token|token|password"
            + "|credential|secret|client[_-]?secret|signature|sign|private[_-]?key|jwt";
    private static final String SENSITIVE_VALUE_KEYS = "(?:" + SENSITIVE_VALUE_KEY_ALTERNATIVES + ")";
    private static final String SENSITIVE_JSON_KEYS =
        "(?:authorization|" + SENSITIVE_VALUE_KEY_ALTERNATIVES + ")";
    private static final String AUTHORIZATION_SEGMENT_BOUNDARY_KEYS =
        "(?:authorization|" + SENSITIVE_VALUE_KEY_ALTERNATIVES + ")";
    private static final Pattern JWT_TOKEN = Pattern.compile("\\beyJ[\\w-]+\\.[\\w-]+\\.[\\w-]+\\b");
    private static final Pattern AUTHORIZATION_BEARER = Pattern.compile(
        "(?i)\\b(Authorization\\s*[:=]\\s*Bearer\\s+)([^\\s,;]+)");
    private static final Pattern BARE_BEARER = Pattern.compile("(?i)\\b(Bearer\\s+)([^\\s,;]+)");
    private static final Pattern JSON_SENSITIVE_VALUE = Pattern.compile(
        "(?i)([\"']" + SENSITIVE_JSON_KEYS + "[\"']\\s*:\\s*[\"'])([^\"']+)([\"'])");
    private static final Pattern QUOTED_KEY_VALUE_SENSITIVE_VALUE = Pattern.compile(
        "(?i)(\\b" + SENSITIVE_VALUE_KEYS + "\\b\\s*[:=]\\s*)([\"'])(.*?)(\\2)");
    private static final Pattern AUTHORIZATION_SCHEME_VALUE = Pattern.compile(
        "(?i)\\b(Authorization\\s*[:=]\\s*)([\"']?)(?:Bearer|Basic|Digest|Token)\\s+([^\"'\\s,;]+)([\"']?)");
    private static final Pattern AUTHORIZATION_VALUE_SEGMENT = Pattern.compile(
        "(?i)(\\bAuthorization\\s*[:=]\\s*)([^\"',;{}\\[\\]()\\r\\n]+?)"
            + "(?=\\s+\\b" + AUTHORIZATION_SEGMENT_BOUNDARY_KEYS + "\\b\\s*[:=]|[,;{}\\[\\]()\\r\\n]|$)");
    private static final Pattern KEY_VALUE_SENSITIVE_VALUE = Pattern.compile(
        "(?i)(\\b" + SENSITIVE_VALUE_KEYS + "\\b\\s*[:=]\\s*)([^\"'\\s,;&{}\\[\\]()]+)");

    private final FunnelEventMapper eventMapper;
    private final FunnelSessionMapper sessionMapper;
    private final BusinessEventService businessEventService;
    private final ObjectMapper objectMapper;
    private final EntityExistenceService entityExistenceService;
    // A26-P2-23：通过 @Lazy 自注入拿到自身代理，用于在 catch (DuplicateKeyException) 后用
    // REQUIRES_NEW 事务重查并发已提交行（RR 隔离级别下普通重查看不到）。测试场景默认 null，回退普通查询。
    private FunnelEventService self;

    public FunnelEventService(FunnelEventMapper eventMapper,
                              FunnelSessionMapper sessionMapper,
                              BusinessEventService businessEventService,
                              ObjectMapper objectMapper,
                              EntityExistenceService entityExistenceService) {
        this.eventMapper = eventMapper;
        this.sessionMapper = sessionMapper;
        this.businessEventService = businessEventService;
        this.objectMapper = objectMapper;
        this.entityExistenceService = entityExistenceService;
    }

    @Autowired
    public void setSelf(@Lazy FunnelEventService self) {
        this.self = self;
    }

    @Transactional
    public FunnelEventResponse record(FunnelEventRequest request) {
        return toResponse(recordInternal(request));
    }

    @Transactional
    public List<FunnelEventResponse> recordBatch(FunnelEventBatchRequest request) {
        if (request == null || request.events == null || request.events.isEmpty()) {
            return List.of();
        }
        // A26-P2-24：批量条数上限校验，避免单请求产生过多 funnel_session/funnel_event 行。
        // 与 FunnelEventBatchRequest.events 上的 @Size(max=100) 对齐，Service 层强制生效。
        if (request.events.size() > MAX_BATCH_SIZE) {
            throw BusinessException.badRequest("批量漏斗事件条数不能超过 " + MAX_BATCH_SIZE);
        }
        for (FunnelEventRequest eventRequest : request.events) {
            validate(eventRequest, false);
        }
        List<FunnelEventResponse> responses = new ArrayList<>();
        for (FunnelEventRequest eventRequest : request.events) {
            responses.add(record(eventRequest));
        }
        return responses;
    }

    @Transactional
    public FunnelEvent recordMilestone(String eventName, String eventStage, Long userId, Long venueId,
                                       Long reservationId, Long orderId, BigDecimal payableAmount,
                                       String resultStatus, String reasonCode, String idempotencyKey,
                                       Map<String, Object> metadata) {
        // A26-P1-03：旧 11 参数签名委托新 12 参数方法，sessionKey 从 FunnelSessionContext（ThreadLocal）
        // 自动获取。该 ThreadLocal 由 FunnelSessionFilter 从 X-Funnel-Session header 提取并绑定。
        // 非请求线程（定时任务、回调链路）返回 null，由 recordInternal 记 WARN 并保留 server-<uuid> 兜底。
        return recordMilestone(eventName, eventStage, userId, venueId, reservationId, orderId, payableAmount,
            resultStatus, reasonCode, idempotencyKey, metadata, FunnelSessionContext.current());
    }

    /**
     * A26-P1-03 新增重载：服务端权威里程碑事件（RESERVATION_SUBMITTED/ORDER_CREATED/
     * PAYMENT_INITIATED/PAYMENT_RESULT_RECORDED）必须显式传入 sessionKey，使漏斗 cohort
     * 第 6~9 步事件与前序浏览器事件共享同一 sessionKey，避免转化率结构性恒为 0。
     *
     * <p>调用方从请求上下文（FunnelSessionContext.current() 或 FunnelEventRequest.sessionKey）
     * 提取并传入。sessionKey 为 null 时由 recordInternal 记 WARN 并保留 server-&lt;uuid&gt; 兜底，
     * 不阻断业务流程。</p>
     */
    @Transactional
    public FunnelEvent recordMilestone(String eventName, String eventStage, Long userId, Long venueId,
                                       Long reservationId, Long orderId, BigDecimal payableAmount,
                                       String resultStatus, String reasonCode, String idempotencyKey,
                                       Map<String, Object> metadata, String sessionKey) {
        String sourceChannel = milestoneSourceChannel(metadata);
        FunnelEventRequest request = new FunnelEventRequest();
        request.eventName = eventName;
        request.eventStage = eventStage;
        request.sourceChannel = sourceChannel;
        request.venueId = venueId;
        request.reservationId = reservationId;
        request.orderId = orderId;
        request.payableAmount = payableAmount;
        request.resultStatus = resultStatus;
        request.dropoffReasonCode = reasonCode;
        request.idempotencyKey = idempotencyKey;
        request.metadata = normalizedMilestoneMetadata(metadata, sourceChannel);
        request.sessionKey = resolveMilestoneSessionKey(reservationId, orderId, sessionKey);

        FunnelEvent event = recordInternal(request, userId);
        mirrorBusinessEvent(event, userId, request.metadata);
        return event;
    }

    private String resolveMilestoneSessionKey(Long reservationId, Long orderId, String requestSessionKey) {
        if (reservationId != null || orderId != null) {
            String inherited = blankToNull(eventMapper.findContinuationSessionKey(reservationId, orderId));
            if (inherited != null) {
                return inherited;
            }
        }
        return blankToNull(requestSessionKey);
    }

    private FunnelEvent recordInternal(FunnelEventRequest request) {
        return recordInternal(request, null, false);
    }

    private FunnelEvent recordInternal(FunnelEventRequest request, Long actorUserId) {
        return recordInternal(request, actorUserId, true);
    }

    private FunnelEvent recordInternal(FunnelEventRequest request, Long actorUserId,
                                       boolean allowServerAuthoritativeEvent) {
        validate(request, allowServerAuthoritativeEvent);
        // AUD-P2-11: 写入前校验场馆存在，避免匿名请求污染不存在或已删除场馆的运营统计
        if (request.venueId != null) {
            entityExistenceService.requireVenueExists(request.venueId);
        }
        String sourceChannel = normalizeSourceChannel(request.sourceChannel);
        String idempotencyKey = blankToNull(request.idempotencyKey);
        if (idempotencyKey != null) {
            FunnelEvent existing = eventMapper.findByIdempotency(PROVIDER_ENV, sourceChannel, idempotencyKey);
            if (existing != null) {
                return existing;
            }
        }

        LocalDateTime now = LocalDateTime.now();
        CurrentUser currentUser = SecurityContext.currentUser();
        Long userId = actorUserId != null ? actorUserId : currentUser == null ? null : currentUser.id();
        String sessionKey = blankToNull(request.sessionKey);
        if (sessionKey == null) {
            // A26-P1-03：服务端权威里程碑事件（RESERVATION_SUBMITTED/ORDER_CREATED/PAYMENT_INITIATED/
            // PAYMENT_RESULT_RECORDED）应通过 FunnelSessionContext 或 FunnelEventRequest.sessionKey
            // 传入浏览器 sessionKey。sessionKey 为 null 意味着调用方未从请求上下文获取到 sessionKey
            // （如定时任务、回调链路、未带 X-Funnel-Session header 的请求），此时记 WARN 提示运营排查
            // 漏斗断链风险，并保留 server-<uuid> 兜底以避免阻断业务流程。
            // 浏览器直录事件（record/recordBatch）的 sessionKey 由前端 FunnelEventRequest.sessionKey 提供，
            // 为 null 时同样兜底，不阻断前端流程。
            log.warn("漏斗事件 sessionKey 缺失，使用 server 兜底，eventName={}, venueId={}, reservationId={}, orderId={}",
                request.eventName, request.venueId, request.reservationId, request.orderId);
            sessionKey = "server-" + UUID.randomUUID();
        }
        String visitorIdHash = sha256(blankToNull(request.visitorId));
        Map<String, Object> safeMetadata = safeMetadata(request.metadata);
        FunnelSession session = findOrCreateSession(request, sessionKey, userId, visitorIdHash,
            sourceChannel, safeMetadata, now);

        FunnelEvent event = new FunnelEvent();
        event.eventNo = nextEventNo(now);
        event.sessionId = session == null ? null : session.id;
        event.sessionKey = sessionKey;
        event.userId = userId;
        event.visitorIdHash = visitorIdHash;
        event.eventName = normalizeEventName(request.eventName);
        event.eventStage = request.eventStage.trim().toUpperCase(Locale.ROOT);
        event.sourceChannel = sourceChannel;
        event.campaignId = blankToNull(request.campaignId);
        event.providerEnv = PROVIDER_ENV;
        event.pagePath = blankToNull(request.pagePath);
        event.referrerPath = blankToNull(request.referrerPath);
        event.keywordNormalized = normalizeKeyword(request.keyword);
        event.keywordHash = keywordHash(request.keyword);
        event.venueId = request.venueId;
        event.spaceId = request.spaceId;
        event.reservationId = request.reservationId;
        event.orderId = request.orderId;
        event.userCouponId = request.userCouponId;
        event.promotionCampaignId = request.promotionCampaignId;
        event.priceRuleId = request.priceRuleId;
        event.payableAmount = request.payableAmount;
        event.resultStatus = defaultString(request.resultStatus, DEFAULT_RESULT_STATUS);
        event.dropoffReasonCode = blankToNull(request.dropoffReasonCode);
        event.reasonText = blankToNull(sanitizeText(request.reasonText));
        event.idempotencyKey = idempotencyKey;
        event.metadataJson = toJson(safeMetadata);
        event.occurredAt = request.occurredAt == null ? now : request.occurredAt;
        event.createTime = now;
        event.deleted = 0;
        insertEvent(event, sourceChannel, idempotencyKey);
        return event;
    }

    private FunnelSession findOrCreateSession(FunnelEventRequest request, String sessionKey, Long userId,
                                              String visitorIdHash, String sourceChannel,
                                              Map<String, Object> safeMetadata, LocalDateTime now) {
        FunnelSession existing = sessionMapper.findBySessionKey(sessionKey);
        if (existing != null) {
            touchSession(existing, sessionKey, now);
            return existing;
        }

        FunnelSession session = new FunnelSession();
        session.sessionKey = sessionKey;
        session.userId = userId;
        session.visitorIdHash = visitorIdHash;
        session.sourceChannel = sourceChannel;
        session.campaignId = blankToNull(request.campaignId);
        session.entryPath = blankToNull(request.pagePath);
        session.landingPage = blankToNull(request.pagePath);
        session.deviceType = blankToNull(request.deviceType);
        session.browserFamily = blankToNull(request.browserFamily);
        session.userAgentHash = sha256(blankToNull(request.userAgent));
        session.providerEnv = PROVIDER_ENV;
        session.firstSeenAt = now;
        session.lastSeenAt = now;
        session.metadataJson = toJson(safeMetadata);
        session.createTime = now;
        session.updateTime = now;
        session.deleted = 0;
        try {
            sessionMapper.insert(session);
            return session;
        } catch (DuplicateKeyException ex) {
            FunnelSession concurrentSession = sessionMapper.findBySessionKey(sessionKey);
            if (concurrentSession != null) {
                touchSession(concurrentSession, sessionKey, now);
                return concurrentSession;
            }
            throw ex;
        }
    }

    private void touchSession(FunnelSession session, String sessionKey, LocalDateTime now) {
        sessionMapper.touchLastSeen(sessionKey, now, now);
        if (session.lastSeenAt == null || session.lastSeenAt.isBefore(now)) {
            session.lastSeenAt = now;
        }
        session.updateTime = now;
    }

    private void insertEvent(FunnelEvent event, String sourceChannel, String idempotencyKey) {
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException ex) {
            // A26-P2-23：RR 隔离级别下当前事务此前已执行过普通读建立快照，普通重查看不到
            // 并发事务已提交的行，需用 REQUIRES_NEW 事务建立新快照重查。生产环境 self 由 Spring
            // 代理注入（@Transactional REQUIRES_NEW 生效）；测试场景 self 为 null 时回退普通查询
            // 保留原行为，便于用 mock 直接验证 catch 块兼容性。
            FunnelEvent existing = idempotencyKey == null
                ? null
                : findByIdempotencyForRecovery(sourceChannel, idempotencyKey);
            if (existing != null) {
                copyEvent(existing, event);
                return;
            }
            throw ex;
        }
    }

    private FunnelEvent findByIdempotencyForRecovery(String sourceChannel, String idempotencyKey) {
        if (self != null) {
            return self.findByIdempotencyInNewTransaction(PROVIDER_ENV, sourceChannel, idempotencyKey);
        }
        return eventMapper.findByIdempotency(PROVIDER_ENV, sourceChannel, idempotencyKey);
    }

    /**
     * A26-P2-23：在 REQUIRES_NEW 事务中重查幂等键对应的已存在事件。RR 隔离级别下当前事务
     * 此前已建立快照，普通 SELECT 看不到并发事务刚提交的行；REQUIRES_NEW 建立新快照可读到。
     * 仅由 insertEvent 的 catch (DuplicateKeyException) 路径调用，不暴露给外部。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FunnelEvent findByIdempotencyInNewTransaction(String providerEnv, String sourceChannel,
                                                         String idempotencyKey) {
        return eventMapper.findByIdempotency(providerEnv, sourceChannel, idempotencyKey);
    }

    private void mirrorBusinessEvent(FunnelEvent event, Long businessUserId, Map<String, Object> metadata) {
        Map<String, Object> mirroredMetadata = new LinkedHashMap<>(safeMetadata(metadata));
        mirroredMetadata.put("eventName", event.eventName);
        mirroredMetadata.put("eventStage", event.eventStage);
        mirroredMetadata.put("venueId", event.venueId);
        mirroredMetadata.put("reservationId", event.reservationId);
        mirroredMetadata.put("orderId", event.orderId);
        mirroredMetadata.put("sourceChannel", event.sourceChannel);

        // A26-P2-21：actor 必须配对一致——(currentUser.id, currentUser.username) 表示触发里程碑
        // 的实际操作者；businessUserId（如订单/预约的顾客 ID）不再作为 actor_user_id，避免"顾客 ID +
        // 管理员 username"错配。BusinessEventService.record 在 actorUserId 为 null 时会兜底用
        // currentUser，但这里显式传入保证配对来源一致且可读。
        CurrentUser currentUser = SecurityContext.currentUser();
        BusinessSourceType sourceType = resolveMirrorBusinessSource(currentUser);
        BusinessEventRecordRequest businessRequest = BusinessEventRecordRequest.of(
                BusinessEventType.FUNNEL_MILESTONE_RECORDED,
                BusinessDomain.FUNNEL,
                "FUNNEL_EVENT",
                event.id,
                sourceType,
                event.resultStatus)
            .provider(ProviderEnv.DEV, PROVIDER_NAME)
            .idempotencyKey(event.idempotencyKey)
            .reasonCode(event.dropoffReasonCode)
            .reasonText(event.reasonText)
            .occurredAt(event.occurredAt);
        businessRequest.metadata = mirroredMetadata;
        if (currentUser != null) {
            // 仅当存在当前登录人时填 actor，且 id/username 必须来自同一来源（currentUser），
            // 避免 businessUserId（业务对象所有者）与 currentUser.username（操作者）错配。
            businessRequest.actor(currentUser.id(), currentUser.username());
        }
        businessEventService.record(businessRequest);
    }

    /**
     * A26-P2-21：根据当前登录人角色推断业务事件来源类型，替代原硬编码 USER：
     * - 系统管理员/场馆管理员触发（如管理员审核通过预约）→ ADMIN
     * - 普通用户触发（如用户提交预约、收藏）→ USER
     * - 无当前登录人（定时任务超时、渠道回调等非用户触发路径）→ SYSTEM
     *   （PROVIDER 与 SYSTEM 在漏斗里程碑镜像场景下区分意义不大，统一按 SYSTEM 记录；
     *    PaymentService.recordPaymentEvent 仍直接用 PROVIDER 记录支付域业务事件，
     *    与本方法互不影响。）
     */
    private BusinessSourceType resolveMirrorBusinessSource(CurrentUser currentUser) {
        if (currentUser == null) {
            return BusinessSourceType.SYSTEM;
        }
        if (currentUser.hasRole(Constants.ROLE_SYSTEM_ADMIN)
            || currentUser.hasRole(Constants.ROLE_VENUE_ADMIN)) {
            return BusinessSourceType.ADMIN;
        }
        return BusinessSourceType.USER;
    }

    public FunnelEventResponse toResponse(FunnelEvent event) {
        if (event == null) {
            return null;
        }
        FunnelEventResponse response = new FunnelEventResponse();
        response.id = event.id;
        response.eventName = event.eventName;
        response.eventStage = event.eventStage;
        response.resultStatus = event.resultStatus;
        response.venueId = event.venueId;
        response.occurredAt = event.occurredAt;
        return response;
    }

    private void validate(FunnelEventRequest request, boolean allowServerAuthoritativeEvent) {
        if (request == null
            || request.eventName == null
            || request.eventName.isBlank()
            || request.eventStage == null
            || request.eventStage.isBlank()) {
            throw BusinessException.badRequest("漏斗事件参数不完整");
        }
        if (!allowServerAuthoritativeEvent) {
            String eventName = normalizeEventName(request.eventName);
            if (SERVER_AUTHORITATIVE_EVENT_NAMES.contains(eventName)) {
                throw BusinessException.badRequest("服务端权威漏斗事件不能由浏览器直接记录");
            }
            if (!BROWSER_RECORDABLE_EVENT_NAMES.contains(eventName)) {
                throw BusinessException.badRequest("漏斗事件不允许由浏览器直接记录");
            }
        }
        // A26-P2-24：各字段长度校验，与 funnel_event/funnel_session schema 列长度对齐，
        // 避免超长字段触发数据库 500 且 batch 整批回滚。reasonText/keyword 不在此校验——
        // 这两个字段会先经 sanitizeText/normalizeKeyword 脱敏（敏感词替换为 *** 可能变短或变长），
        // 再由 sanitizeText 截断到 255、normalizeKeyword 截断到 128，保证最终落库值在列长度内。
        // 若对原始入参校验 255/128，含大量敏感词的合法 reasonText 会在脱敏前被误拒。
        validateFieldLength("sessionKey", request.sessionKey, 96);
        validateFieldLength("visitorId", request.visitorId, 128);
        validateFieldLength("eventName", request.eventName, 64);
        validateFieldLength("eventStage", request.eventStage, 64);
        validateFieldLength("sourceChannel", request.sourceChannel, 64);
        validateFieldLength("campaignId", request.campaignId, 128);
        validateFieldLength("pagePath", request.pagePath, 255);
        validateFieldLength("referrerPath", request.referrerPath, 255);
        validateFieldLength("resultStatus", request.resultStatus, 32);
        validateFieldLength("dropoffReasonCode", request.dropoffReasonCode, 64);
        validateFieldLength("idempotencyKey", request.idempotencyKey, 128);
        validateFieldLength("userAgent", request.userAgent, 512);
        validateFieldLength("deviceType", request.deviceType, 32);
        validateFieldLength("browserFamily", request.browserFamily, 64);
        // A26-P2-24：occurredAt 时间窗校验——不允许未来时间（防伪造投放管理端窗口），
        // 不允许早于 7 天前（防把事件投放进管理端任意历史日期窗口）。Service 端权威里程碑
        // 事件（recordMilestone 路径）由系统填充 occurredAt，同样需校验避免脏数据。
        validateOccurredAtWindow(request.occurredAt);
    }

    private void validateFieldLength(String fieldName, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw BusinessException.badRequest(fieldName + " 长度不能超过 " + maxLength);
        }
    }

    private void validateOccurredAtWindow(LocalDateTime occurredAt) {
        if (occurredAt == null) {
            // null 由 recordInternal 兜底用当前时间，不需要校验
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (occurredAt.isAfter(now)) {
            throw BusinessException.badRequest("occurredAt 不能晚于当前时间");
        }
        LocalDateTime lowerBound = now.minusHours(OCCURRED_AT_PAST_WINDOW_HOURS);
        if (occurredAt.isBefore(lowerBound)) {
            throw BusinessException.badRequest("occurredAt 不能早于 7 天前");
        }
    }

    private String normalizeEventName(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> safeMetadata(Map<String, Object> metadata) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (metadata == null || metadata.isEmpty()) {
            return safe;
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (key == null || isBlockedMetadataKey(key)) {
                continue;
            }
            safe.put(key, sanitizeMetadataValue(entry.getValue()));
        }
        return safe;
    }

    private String normalizeKeyword(String keyword) {
        String trimmed = blankToNull(keyword);
        if (trimmed == null) {
            return null;
        }
        String masked = sanitizeText(trimmed);
        return masked.length() <= 128 ? masked : masked.substring(0, 128);
    }

    private String keywordHash(String keyword) {
        return sha256(blankToNull(keyword));
    }

    private String nextEventNo(LocalDateTime now) {
        return "FE"
            + now.format(EVENT_NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw BusinessException.badRequest("漏斗事件元数据无法序列化");
        }
    }

    private String defaultString(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private String normalizeSourceChannel(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return DEFAULT_SOURCE_CHANNEL;
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private boolean isBlockedMetadataKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return BLOCKED_METADATA_KEYS.contains(normalized)
            || normalized.contains("contactphone")
            || normalized.contains("contactname")
            || normalized.contains("rawpayload")
            || normalized.contains("useragent")
            || normalized.contains("authorization")
            || normalized.contains("token")
            || normalized.contains("password")
            || normalized.contains("credential")
            || normalized.contains("secret")
            || normalized.contains("signature")
            || normalized.contains("privatekey")
            || normalized.contains("apikey")
            || normalized.contains("remark")
            || normalized.equals("ip")
            || normalized.startsWith("ip")
            || normalized.equals("clientip")
            || normalized.equals("remoteip")
            || normalized.endsWith("ipaddress");
    }

    private Object sanitizeMetadataValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                if (!(key instanceof String textKey) || isBlockedMetadataKey(textKey)) {
                    continue;
                }
                nested.put(textKey, sanitizeMetadataValue(entry.getValue()));
            }
            return nested;
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : list) {
                sanitized.add(sanitizeMetadataValue(item));
            }
            return sanitized;
        }
        if (value instanceof String text) {
            return sanitizeText(text);
        }
        return value;
    }

    private String sanitizeText(String value) {
        String text = blankToNull(value);
        if (text == null) {
            return null;
        }
        text = JWT_TOKEN.matcher(text).replaceAll("eyJ***.***.***");
        text = AUTHORIZATION_BEARER.matcher(text).replaceAll("$1***");
        text = BARE_BEARER.matcher(text).replaceAll("$1***");
        text = JSON_SENSITIVE_VALUE.matcher(text).replaceAll("$1***$3");
        text = QUOTED_KEY_VALUE_SENSITIVE_VALUE.matcher(text).replaceAll("$1$2***$4");
        text = AUTHORIZATION_SCHEME_VALUE.matcher(text).replaceAll("$1$2***$4");
        text = AUTHORIZATION_VALUE_SEGMENT.matcher(text).replaceAll("$1***");
        text = KEY_VALUE_SENSITIVE_VALUE.matcher(text).replaceAll("$1***");
        text = USER_AGENT_TOKEN.matcher(text).replaceAll("[user-agent]");
        text = IPV4_ADDRESS.matcher(text).replaceAll("[ip]");
        text = LONG_DIGIT_RUN.matcher(text).replaceAll("[number]");
        return text.length() <= 255 ? text : text.substring(0, 255);
    }

    private String milestoneSourceChannel(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return DEFAULT_SOURCE_CHANNEL;
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (entry.getKey() != null
                && "sourcechannel".equals(entry.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
                && entry.getValue() instanceof String value
                && !value.isBlank()) {
                return normalizeSourceChannel(value);
            }
        }
        return DEFAULT_SOURCE_CHANNEL;
    }

    private Map<String, Object> normalizedMilestoneMetadata(Map<String, Object> metadata, String sourceChannel) {
        if (metadata == null || metadata.isEmpty()) {
            return metadata;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        boolean hasSourceChannel = false;
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (key != null
                && "sourcechannel".equals(key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))) {
                hasSourceChannel = true;
                continue;
            }
            normalized.put(key, entry.getValue());
        }
        if (hasSourceChannel) {
            normalized.put("sourceChannel", sourceChannel);
            return normalized;
        }
        return metadata;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", ex);
        }
    }

    private void copyEvent(FunnelEvent source, FunnelEvent target) {
        target.id = source.id;
        target.eventNo = source.eventNo;
        target.sessionId = source.sessionId;
        target.sessionKey = source.sessionKey;
        target.userId = source.userId;
        target.visitorIdHash = source.visitorIdHash;
        target.eventName = source.eventName;
        target.eventStage = source.eventStage;
        target.sourceChannel = source.sourceChannel;
        target.campaignId = source.campaignId;
        target.providerEnv = source.providerEnv;
        target.pagePath = source.pagePath;
        target.referrerPath = source.referrerPath;
        target.keywordNormalized = source.keywordNormalized;
        target.keywordHash = source.keywordHash;
        target.venueId = source.venueId;
        target.spaceId = source.spaceId;
        target.reservationId = source.reservationId;
        target.orderId = source.orderId;
        target.userCouponId = source.userCouponId;
        target.promotionCampaignId = source.promotionCampaignId;
        target.priceRuleId = source.priceRuleId;
        target.payableAmount = source.payableAmount;
        target.resultStatus = source.resultStatus;
        target.dropoffReasonCode = source.dropoffReasonCode;
        target.reasonText = source.reasonText;
        target.idempotencyKey = source.idempotencyKey;
        target.metadataJson = source.metadataJson;
        target.occurredAt = source.occurredAt;
        target.createTime = source.createTime;
        target.deleted = source.deleted;
    }
}
