package com.example.venue.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.BusinessException;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.notice.dto.NoticeRequest;
import com.example.venue.notice.dto.PublicNoticeResponse;
import com.example.venue.notice.entity.Notice;
import com.example.venue.notice.entity.NoticeDeliveryLog;
import com.example.venue.notice.entity.NoticeMessage;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.email.EmailIdempotencyKeyFactory;
import com.example.venue.notice.email.EmailRecipientEligibility;
import com.example.venue.notice.email.EmailRecipientResolver;
import com.example.venue.notice.email.EmailSnapshotService;
import com.example.venue.notice.email.EmailTemplate;
import com.example.venue.notice.email.EmailTemplateRegistry;
import com.example.venue.notice.email.EmailTemplateRenderer;
import com.example.venue.notice.email.RenderedEmail;
import com.example.venue.notice.mapper.NoticeMapper;
import com.example.venue.notice.mapper.NoticeDeliveryLogMapper;
import com.example.venue.notice.mapper.NoticeMessageMapper;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRoute;
import com.example.venue.notice.routing.NotificationRoutingDecision;
import com.example.venue.notice.routing.NotificationRoutingPolicy;
import com.example.venue.security.SecurityContext;
import com.example.venue.venue.service.VenueAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class NoticeService {
    private static final DateTimeFormatter OUTBOX_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String IN_APP_PROVIDER_KEY = "notice.in_app.dev";
    private static final String IN_APP_PROVIDER_ENV = "DEV";
    private static final String SMTP_SANDBOX_PROVIDER_KEY = "notice.smtp.sandbox";
    private static final String SMTP_SANDBOX_PROVIDER_ENV = "SANDBOX";
    private static final String SMTP_SANDBOX_PROVIDER_NAME = "SMTP_EMAIL";
    private static final String SMTP_SANDBOX_CONFIG_VERSION = "SMTP_SANDBOX_V1";
    private static final String NO_ELIGIBLE_VENUE_ADMIN = "NO_ELIGIBLE_VENUE_ADMIN";

    private final NoticeMapper noticeMapper;
    private final NoticeMessageMapper messageMapper;
    private final NoticeOutboxMapper outboxMapper;
    private final VenueAccessService venueAccessService;
    private final NoticeDeliveryService deliveryService;
    private final NoticeReceiptService receiptService;
    private final EmailRecipientResolver recipientResolver;
    private final NotificationRoutingPolicy routingPolicy;
    private final EmailTemplateRegistry templateRegistry;
    private final EmailTemplateRenderer templateRenderer;
    private final EmailIdempotencyKeyFactory emailIdempotencyKeyFactory;
    private final EmailSnapshotService emailSnapshotService;
    private final ObjectMapper objectMapper;
    private final NoticeDeliveryLogMapper deliveryLogMapper;

    public NoticeService(NoticeMapper noticeMapper, NoticeMessageMapper messageMapper,
                         NoticeOutboxMapper outboxMapper, VenueAccessService venueAccessService,
                         NoticeDeliveryService deliveryService, NoticeReceiptService receiptService) {
        this(noticeMapper, messageMapper, outboxMapper, venueAccessService, deliveryService,
            receiptService, null);
    }

    public NoticeService(NoticeMapper noticeMapper, NoticeMessageMapper messageMapper,
                         NoticeOutboxMapper outboxMapper, VenueAccessService venueAccessService,
                         NoticeDeliveryService deliveryService, NoticeReceiptService receiptService,
                         NoticeDeliveryLogMapper deliveryLogMapper) {
        this.noticeMapper = noticeMapper;
        this.messageMapper = messageMapper;
        this.outboxMapper = outboxMapper;
        this.venueAccessService = venueAccessService;
        this.deliveryService = deliveryService;
        this.receiptService = receiptService;
        this.recipientResolver = null;
        this.routingPolicy = null;
        this.templateRegistry = null;
        this.templateRenderer = null;
        this.emailIdempotencyKeyFactory = null;
        this.emailSnapshotService = null;
        this.objectMapper = new ObjectMapper();
        this.deliveryLogMapper = deliveryLogMapper;
    }

    @Autowired
    public NoticeService(
        NoticeMapper noticeMapper,
        NoticeMessageMapper messageMapper,
        NoticeOutboxMapper outboxMapper,
        VenueAccessService venueAccessService,
        NoticeDeliveryService deliveryService,
        NoticeReceiptService receiptService,
        EmailRecipientResolver recipientResolver,
        NotificationRoutingPolicy routingPolicy,
        EmailTemplateRegistry templateRegistry,
        EmailTemplateRenderer templateRenderer,
        EmailSnapshotService emailSnapshotService,
        ObjectMapper objectMapper,
        NoticeDeliveryLogMapper deliveryLogMapper
    ) {
        this(noticeMapper, messageMapper, outboxMapper, venueAccessService, deliveryService,
            receiptService, recipientResolver, routingPolicy, templateRegistry, templateRenderer,
            new EmailIdempotencyKeyFactory(), emailSnapshotService, objectMapper, deliveryLogMapper);
    }

    public NoticeService(
        NoticeMapper noticeMapper,
        NoticeMessageMapper messageMapper,
        NoticeOutboxMapper outboxMapper,
        VenueAccessService venueAccessService,
        NoticeDeliveryService deliveryService,
        NoticeReceiptService receiptService,
        EmailRecipientResolver recipientResolver,
        NotificationRoutingPolicy routingPolicy,
        EmailTemplateRegistry templateRegistry,
        EmailTemplateRenderer templateRenderer,
        EmailIdempotencyKeyFactory emailIdempotencyKeyFactory,
        EmailSnapshotService emailSnapshotService
    ) {
        this(noticeMapper, messageMapper, outboxMapper, venueAccessService, deliveryService,
            receiptService, recipientResolver, routingPolicy, templateRegistry, templateRenderer,
            emailIdempotencyKeyFactory, emailSnapshotService, new ObjectMapper());
    }

    public NoticeService(
        NoticeMapper noticeMapper,
        NoticeMessageMapper messageMapper,
        NoticeOutboxMapper outboxMapper,
        VenueAccessService venueAccessService,
        NoticeDeliveryService deliveryService,
        NoticeReceiptService receiptService,
        EmailRecipientResolver recipientResolver,
        NotificationRoutingPolicy routingPolicy,
        EmailTemplateRegistry templateRegistry,
        EmailTemplateRenderer templateRenderer,
        EmailIdempotencyKeyFactory emailIdempotencyKeyFactory,
        EmailSnapshotService emailSnapshotService,
        ObjectMapper objectMapper
    ) {
        this(noticeMapper, messageMapper, outboxMapper, venueAccessService, deliveryService,
            receiptService, recipientResolver, routingPolicy, templateRegistry, templateRenderer,
            emailIdempotencyKeyFactory, emailSnapshotService, objectMapper, null);
    }

    public NoticeService(
        NoticeMapper noticeMapper,
        NoticeMessageMapper messageMapper,
        NoticeOutboxMapper outboxMapper,
        VenueAccessService venueAccessService,
        NoticeDeliveryService deliveryService,
        NoticeReceiptService receiptService,
        EmailRecipientResolver recipientResolver,
        NotificationRoutingPolicy routingPolicy,
        EmailTemplateRegistry templateRegistry,
        EmailTemplateRenderer templateRenderer,
        EmailIdempotencyKeyFactory emailIdempotencyKeyFactory,
        EmailSnapshotService emailSnapshotService,
        ObjectMapper objectMapper,
        NoticeDeliveryLogMapper deliveryLogMapper
    ) {
        this.noticeMapper = noticeMapper;
        this.messageMapper = messageMapper;
        this.outboxMapper = outboxMapper;
        this.venueAccessService = venueAccessService;
        this.deliveryService = deliveryService;
        this.receiptService = receiptService;
        this.recipientResolver = recipientResolver;
        this.routingPolicy = routingPolicy;
        this.templateRegistry = templateRegistry;
        this.templateRenderer = templateRenderer;
        this.emailIdempotencyKeyFactory = emailIdempotencyKeyFactory;
        this.emailSnapshotService = emailSnapshotService;
        this.objectMapper = objectMapper;
        this.deliveryLogMapper = deliveryLogMapper;
    }

    public PageResult<PublicNoticeResponse> publicNotices(PageQuery query) {
        Page<Notice> page = noticeMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()),
            new QueryWrapper<Notice>()
                .eq("deleted", 0)
                .eq("status", "PUBLISHED")
                .eq("type", "GLOBAL")
                .isNull("target_venue_id")
                .apply("JSON_UNQUOTE(JSON_EXTRACT(target_rule_json, '$.targetScope')) = {0}", "ALL_USERS")
                .orderByDesc("publish_time"));
        List<PublicNoticeResponse> records = (page.getRecords() == null ? List.<Notice>of() : page.getRecords())
            .stream()
            .filter(this::isAnonymousPublicNotice)
            .map(PublicNoticeResponse::from)
            .toList();
        return PageResult.of(records, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    private boolean isAnonymousPublicNotice(Notice notice) {
        if (notice == null
            || !"PUBLISHED".equals(notice.status)
            || !"GLOBAL".equals(notice.type)
            || notice.targetVenueId != null
            || notice.targetRuleJson == null) {
            return false;
        }
        try {
            JsonNode targetRule = objectMapper.readTree(notice.targetRuleJson);
            return "ALL_USERS".equals(targetRule.path("targetScope").asText());
        } catch (Exception exception) {
            return false;
        }
    }

    public PageResult<Notice> recentAdminNotices(PageQuery query) {
        Page<Notice> page = noticeMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()),
            new QueryWrapper<Notice>().eq("deleted", 0).orderByDesc("create_time", "id"));
        return PageResult.of(page.getRecords(), page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    public PageResult<NoticeMessage> myMessages(PageQuery query, Boolean unreadOnly) {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        QueryWrapper<NoticeMessage> wrapper = new QueryWrapper<NoticeMessage>().eq("user_id", userId);
        if (Boolean.TRUE.equals(unreadOnly)) {
            wrapper.eq("read_flag", 0);
        }
        Page<NoticeMessage> page = messageMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    @Transactional
    public Notice publish(NoticeRequest request) {
        return deliveryService.publish(request);
    }

    @Transactional
    public NoticeMessage createMessage(Long userId, String title, String content, String type, Long businessId) {
        if (userId == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        NoticeMessage message = new NoticeMessage();
        message.userId = userId;
        message.title = title;
        message.content = Jsoup.clean(content, Safelist.none());
        message.type = type;
        message.businessId = businessId;
        message.providerEnv = IN_APP_PROVIDER_ENV;
        message.providerName = NoticeDeliveryService.PROVIDER_NAME;
        message.deliveryStatus = "PENDING";
        message.readFlag = 0;
        message.createTime = now;
        messageMapper.insert(message);

        NoticeOutbox outbox = createBusinessOutbox(message, now);
        outboxMapper.insert(outbox);
        return message;
    }

    @Transactional
    public NoticeMessage createBusinessNotification(NotificationEvent event) {
        requireBusinessEvent(event);
        EmailRecipientEligibility recipient = recipientResolver.resolve(
            event.recipientUserId(), event.recipientKind());
        NotificationRoutingDecision decision = routingPolicy.decide(event, recipient);
        validateRoutes(decision);

        LocalDateTime now = LocalDateTime.now();
        String inAppIdempotencyKey = inAppIdempotencyKey(event);
        NoticeOutbox existingInApp = outboxMapper.findByIdempotency(
            IN_APP_PROVIDER_ENV, NoticeDeliveryService.PROVIDER_NAME, inAppIdempotencyKey);
        NoticeMessage message;
        if (existingInApp == null) {
            NoticeMessage candidate = createBusinessMessage(event, now);
            NotificationRoute inAppRoute = decision.routes().stream()
                .filter(route -> "IN_APP".equals(route.channel()))
                .findFirst()
                .orElseThrow(this::invalidRoute);
            message = createOrReuseBusinessInAppOutbox(
                candidate, event, inAppRoute, inAppIdempotencyKey, now);
        } else {
            message = requireExistingBusinessMessage(
                existingInApp, event, inAppIdempotencyKey);
        }
        for (NotificationRoute route : decision.routes()) {
            if ("IN_APP".equals(route.channel())) {
                continue;
            } else if ("EMAIL".equals(route.channel())) {
                EmailTemplate template = templateRegistry.require(
                    event.eventType(), event.recipientKind());
                RenderedEmail rendered = templateRenderer.render(template, event.templateModel());
                createOrReuseEmailOutboxAndSnapshot(
                    event, recipient, route, template, rendered, now);
            } else {
                throw invalidRoute();
            }
        }
        return message;
    }

    @Transactional
    public void recordNoEligibleVenueAdminExclusion(
        String eventType, String eventKey, String businessType, Long businessId
    ) {
        requireNoEligibleVenueAdminTuple(eventType, eventKey, businessType, businessId);
        if (deliveryLogMapper == null) {
            throw new IllegalStateException("Notice delivery log dependency is unavailable");
        }
        LocalDateTime now = LocalDateTime.now();
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("eventType", eventType);
        summary.put("eventKey", eventKey);
        summary.put("businessType", businessType);
        summary.put("businessId", businessId);
        summary.put("recipientKind", "VENUE_ADMIN");

        NoticeDeliveryLog log = new NoticeDeliveryLog();
        log.channel = "EMAIL";
        log.status = "EXCLUDED";
        log.sourceType = "BUSINESS_NOTIFICATION";
        log.providerEnv = SMTP_SANDBOX_PROVIDER_ENV;
        log.providerName = SMTP_SANDBOX_PROVIDER_NAME;
        log.idempotencyKey = "EMAIL_EXCLUDED:" + sha256(
            eventType + "\n" + eventKey + "\n" + businessType + "\n" + businessId
                + "\n" + SMTP_SANDBOX_PROVIDER_KEY + "\n" + SMTP_SANDBOX_CONFIG_VERSION);
        log.requestSummaryJson = summary.toString();
        log.responseSummaryJson = "{}";
        log.failureCode = NO_ELIGIBLE_VENUE_ADMIN;
        log.configVersion = SMTP_SANDBOX_CONFIG_VERSION;
        log.durationMs = 0L;
        log.metadataJson = "{\"providerKey\":\"" + SMTP_SANDBOX_PROVIDER_KEY
            + "\",\"source\":\"BUSINESS_NOTIFICATION\"}";
        log.createTime = now;
        if (deliveryLogMapper.insert(log) != 1) {
            throw BusinessException.badRequest(
                "NOTICE_NO_RECIPIENT_EVIDENCE_CREATE_FAILED: exclusion evidence was not created");
        }
    }

    @Transactional
    public void markAllRead() {
        markAllRead("MESSAGE_CENTER");
    }

    @Transactional
    public void markAllRead(String entry) {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        // A26-P2-18：RR 下普通 SELECT 读事务快照，而 markAllRead 的 UPDATE 按最新已提交数据匹配，
        // 两者之间并发送达的消息会被 UPDATE 置 read_flag=1 却不在 unread 列表中，recordReads
        // 不为它写回执，受众快照永久停留 UNREAD。改用 FOR UPDATE 触发当前读 + Next-Key Lock，
        // 锁住 (user_id, read_flag=0) 索引范围，阻止其他事务在该范围内 INSERT 新消息，
        // 保证 SELECT 与 UPDATE 看到同一组未读消息。
        List<NoticeMessage> unread = messageMapper.selectList(new QueryWrapper<NoticeMessage>()
            .eq("user_id", userId)
            .eq("read_flag", 0)
            .last("for update"));
        messageMapper.markAllRead(userId);
        receiptService.recordReads(unread, userId, entry);
    }

    @Transactional
    public void markRead(Long id) {
        markRead(id, "MESSAGE_CENTER");
    }

    @Transactional
    public void markRead(Long id, String entry) {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        if (messageMapper.markRead(id, userId) == 0) {
            throw BusinessException.notFound("消息不存在");
        }
        receiptService.recordRead(id, userId, entry);
    }

    public long unreadCount() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        return messageMapper.unreadCount(userId);
    }

    private NoticeOutbox createBusinessOutbox(NoticeMessage message, LocalDateTime now) {
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.outboxNo = nextOutboxNo(now);
        outbox.batchId = null;
        outbox.audienceId = null;
        outbox.noticeId = null;
        outbox.userId = message.userId;
        outbox.channel = NoticeDeliveryService.CHANNEL;
        outbox.providerEnv = IN_APP_PROVIDER_ENV;
        outbox.providerName = NoticeDeliveryService.PROVIDER_NAME;
        outbox.configVersion = NoticeDeliveryService.CONFIG_VERSION;
        outbox.status = "PENDING";
        outbox.attemptCount = 0;
        outbox.idempotencyKey = "NOTICE_MESSAGE:" + message.id;
        outbox.requestSummaryJson = businessOutboxSummary(message);
        outbox.metadataJson = "{\"providerKey\":\"" + IN_APP_PROVIDER_KEY + "\"}";
        outbox.createTime = now;
        outbox.updateTime = now;
        outbox.deleted = 0;
        return outbox;
    }

    private NoticeMessage createBusinessMessage(NotificationEvent event, LocalDateTime now) {
        NoticeMessage message = new NoticeMessage();
        message.userId = event.recipientUserId();
        message.title = Jsoup.clean(event.inAppTitle(), Safelist.none());
        message.content = Jsoup.clean(event.inAppContent(), Safelist.none());
        message.type = event.businessType();
        message.businessId = event.businessId();
        message.providerEnv = IN_APP_PROVIDER_ENV;
        message.providerName = NoticeDeliveryService.PROVIDER_NAME;
        message.deliveryStatus = "PENDING";
        message.readFlag = 0;
        message.createTime = now;
        if (messageMapper.insert(message) != 1 || message.id == null) {
            throw BusinessException.badRequest(
                "NOTICE_MESSAGE_CREATE_FAILED: business notification message was not created");
        }
        return message;
    }

    private NoticeMessage createOrReuseBusinessInAppOutbox(
        NoticeMessage message,
        NotificationEvent event,
        NotificationRoute route,
        String idempotencyKey,
        LocalDateTime now
    ) {
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.outboxNo = nextOutboxNo(now);
        outbox.userId = event.recipientUserId();
        outbox.eventType = event.eventType();
        outbox.eventKey = event.eventKey();
        outbox.recipientKind = event.recipientKind().name();
        outbox.channel = route.channel();
        outbox.providerEnv = route.providerEnv();
        outbox.providerName = route.providerName();
        outbox.providerKey = route.providerKey();
        outbox.configVersion = route.configVersion();
        outbox.status = "PENDING";
        outbox.attemptCount = 0;
        outbox.idempotencyKey = idempotencyKey;
        outbox.requestSummaryJson = businessOutboxSummary(message);
        outbox.metadataJson = "{\"providerKey\":\"" + IN_APP_PROVIDER_KEY
            + "\",\"source\":\"BUSINESS_NOTIFICATION\"}";
        outbox.createTime = now;
        outbox.updateTime = now;
        outbox.deleted = 0;
        try {
            if (outboxMapper.insert(outbox) != 1) {
                throw BusinessException.badRequest(
                    "NOTICE_OUTBOX_CREATE_FAILED: IN_APP route was not created");
            }
            return message;
        } catch (DuplicateKeyException duplicate) {
            NoticeOutbox winner = outboxMapper.findByIdempotencyForUpdate(
                IN_APP_PROVIDER_ENV, NoticeDeliveryService.PROVIDER_NAME, idempotencyKey);
            if (winner == null) {
                throw duplicate;
            }
            NoticeMessage winningMessage = requireExistingBusinessMessage(
                winner, event, idempotencyKey, true);
            if (message.id == null || messageMapper.deleteById(message.id) != 1) {
                throw duplicate;
            }
            return winningMessage;
        }
    }

    private NoticeMessage requireExistingBusinessMessage(
        NoticeOutbox outbox,
        NotificationEvent event,
        String idempotencyKey
    ) {
        return requireExistingBusinessMessage(outbox, event, idempotencyKey, false);
    }

    private NoticeMessage requireExistingBusinessMessage(
        NoticeOutbox outbox,
        NotificationEvent event,
        String idempotencyKey,
        boolean lockMessage
    ) {
        if (!"IN_APP".equals(outbox.channel)
            || !IN_APP_PROVIDER_ENV.equals(outbox.providerEnv)
            || !NoticeDeliveryService.PROVIDER_NAME.equals(outbox.providerName)
            || !IN_APP_PROVIDER_KEY.equals(outbox.providerKey)
            || !NoticeDeliveryService.CONFIG_VERSION.equals(outbox.configVersion)
            || !event.recipientUserId().equals(outbox.userId)
            || !event.eventType().equals(outbox.eventType)
            || !event.eventKey().equals(outbox.eventKey)
            || !event.recipientKind().name().equals(outbox.recipientKind)
            || !idempotencyKey.equals(outbox.idempotencyKey)) {
            throw invalidRoute();
        }
        Long messageId = summaryMessageId(outbox.requestSummaryJson);
        NoticeMessage message = messageId == null
            ? null
            : lockMessage
                ? messageMapper.findByIdForUpdate(messageId)
                : messageMapper.selectById(messageId);
        if (message == null || !event.recipientUserId().equals(message.userId)
            || !Objects.equals(event.businessId(), message.businessId)
            || !Objects.equals(event.businessType(), message.type)) {
            throw invalidRoute();
        }
        return message;
    }

    private Long summaryMessageId(String summaryJson) {
        try {
            JsonNode value = objectMapper.readTree(summaryJson).get("messageId");
            return value != null && value.canConvertToLong() ? value.longValue() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private String inAppIdempotencyKey(NotificationEvent event) {
        return "IN_APP:" + sha256(
            event.eventType() + "\n" + event.eventKey() + "\n" + event.recipientUserId());
    }

    private NoticeOutbox createOrReuseEmailOutboxAndSnapshot(
        NotificationEvent event,
        EmailRecipientEligibility recipient,
        NotificationRoute route,
        EmailTemplate template,
        RenderedEmail rendered,
        LocalDateTime now
    ) {
        String idempotencyKey = emailIdempotencyKeyFactory.forRoute(
            event.eventType(), event.eventKey(), event.recipientUserId(),
            route.providerKey(), route.configVersion());
        NoticeOutbox existing = outboxMapper.findEmailRoute(
            event.eventType(), event.eventKey(), event.recipientUserId(),
            route.providerKey(), route.configVersion());
        if (existing != null) {
            return requireExistingEmailRoute(existing, event, route, idempotencyKey);
        }

        NoticeOutbox outbox = new NoticeOutbox();
        outbox.outboxNo = nextOutboxNo(now);
        outbox.userId = event.recipientUserId();
        outbox.eventType = event.eventType();
        outbox.eventKey = event.eventKey();
        outbox.recipientKind = event.recipientKind().name();
        outbox.channel = route.channel();
        outbox.providerEnv = route.providerEnv();
        outbox.providerName = route.providerName();
        outbox.providerKey = route.providerKey();
        outbox.configVersion = route.configVersion();
        outbox.templateKey = template.templateKey();
        outbox.templateVersion = template.templateVersion();
        outbox.status = "PENDING";
        outbox.attemptCount = 0;
        outbox.idempotencyKey = idempotencyKey;
        outbox.requestSummaryJson = "{}";
        outbox.metadataJson = "{\"providerKey\":\"" + route.providerKey()
            + "\",\"source\":\"BUSINESS_NOTIFICATION\"}";
        outbox.createTime = now;
        outbox.updateTime = now;
        outbox.deleted = 0;
        try {
            if (outboxMapper.insert(outbox) != 1 || outbox.id == null) {
                throw BusinessException.badRequest(
                    "NOTICE_OUTBOX_CREATE_FAILED: EMAIL route was not created");
            }
        } catch (DuplicateKeyException duplicate) {
            NoticeOutbox winner = outboxMapper.findEmailRouteForUpdate(
                event.eventType(), event.eventKey(), event.recipientUserId(),
                route.providerKey(), route.configVersion());
            if (winner == null) {
                throw duplicate;
            }
            return requireExistingEmailRoute(winner, event, route, idempotencyKey);
        }
        String messageIdHeader = "<" + sha256(route.providerKey() + "\n" + idempotencyKey)
            + "@venue.local>";
        emailSnapshotService.create(
            outbox.id, event.recipientUserId(), recipient, template, rendered, messageIdHeader);
        return outbox;
    }

    private NoticeOutbox requireExistingEmailRoute(
        NoticeOutbox outbox,
        NotificationEvent event,
        NotificationRoute route,
        String idempotencyKey
    ) {
        if (!"EMAIL".equals(outbox.channel)
            || !event.recipientUserId().equals(outbox.userId)
            || !event.eventType().equals(outbox.eventType)
            || !event.eventKey().equals(outbox.eventKey)
            || !event.recipientKind().name().equals(outbox.recipientKind)
            || !route.providerKey().equals(outbox.providerKey)
            || !route.providerEnv().equals(outbox.providerEnv)
            || !route.providerName().equals(outbox.providerName)
            || !route.configVersion().equals(outbox.configVersion)
            || !idempotencyKey.equals(outbox.idempotencyKey)
            || outbox.templateKey == null || outbox.templateKey.isBlank()
            || outbox.templateVersion == null || outbox.templateVersion.isBlank()) {
            throw invalidRoute();
        }
        return outbox;
    }

    private void requireBusinessEvent(NotificationEvent event) {
        if (event == null || event.recipientUserId() == null || event.recipientUserId() <= 0
            || event.recipientKind() == null || event.eventType() == null
            || event.eventKey() == null || event.inAppTitle() == null
            || event.inAppContent() == null) {
            throw invalidRoute();
        }
        if (recipientResolver == null || routingPolicy == null || templateRegistry == null
            || templateRenderer == null || emailIdempotencyKeyFactory == null
            || emailSnapshotService == null) {
            throw new IllegalStateException("Business notification dependencies are unavailable");
        }
    }

    private void validateRoutes(NotificationRoutingDecision decision) {
        if (decision == null || decision.routes() == null) {
            throw invalidRoute();
        }
        long inAppCount = decision.routes().stream()
            .filter(route -> route != null && "IN_APP".equals(route.channel()))
            .count();
        long emailCount = decision.routes().stream()
            .filter(route -> route != null && "EMAIL".equals(route.channel()))
            .count();
        if (inAppCount != 1 || emailCount > 1 || decision.routes().stream().anyMatch(route -> route == null)) {
            throw invalidRoute();
        }
    }

    private BusinessException invalidRoute() {
        return BusinessException.badRequest("NOTICE_ROUTE_INVALID: unsupported channel");
    }

    private void requireNoEligibleVenueAdminTuple(
        String eventType, String eventKey, String businessType, Long businessId
    ) {
        String expectedBusinessType;
        if ("RESERVATION_REVIEW_REQUIRED".equals(eventType)) {
            expectedBusinessType = "RESERVATION";
        } else if ("REFUND_REVIEW_REQUIRED".equals(eventType)) {
            expectedBusinessType = "REFUND";
        } else {
            throw invalidNoRecipientEvidence();
        }
        if (!expectedBusinessType.equals(businessType)
            || businessId == null || businessId <= 0
            || !(eventType + ":" + businessId).equals(eventKey)) {
            throw invalidNoRecipientEvidence();
        }
    }

    private BusinessException invalidNoRecipientEvidence() {
        return BusinessException.badRequest(
            "NOTICE_NO_RECIPIENT_EVIDENCE_INVALID: unsupported exclusion evidence tuple");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String nextOutboxNo(LocalDateTime now) {
        return "NO" + now.format(OUTBOX_NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String businessOutboxSummary(NoticeMessage message) {
        return "{\"messageId\":" + message.id
            + ",\"userId\":" + message.userId
            + ",\"channel\":\"" + NoticeDeliveryService.CHANNEL + "\""
            + ",\"type\":\"" + message.type + "\""
            + ",\"businessId\":" + message.businessId
            + ",\"source\":\"BUSINESS_MESSAGE\"}";
    }
}
