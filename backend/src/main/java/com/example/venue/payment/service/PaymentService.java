package com.example.venue.payment.service;

import com.example.venue.checkin.dto.ReservationLifecycleResponse;
import com.example.venue.checkin.service.ReservationLifecycleService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.enums.ProviderEnv;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.MoneyUtils;
import com.example.venue.common.RefundFailureCodes;
import com.example.venue.common.SensitiveDataMasker;
import com.example.venue.common.StateTransitionService;
import com.example.venue.funnel.service.FunnelEventService;
import com.example.venue.funnel.service.FunnelSessionContext;
import com.example.venue.member.service.MemberService;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.order.dto.PaymentCallbackRequest;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.entity.OrderStatusLog;
import com.example.venue.order.entity.PaymentCallbackLog;
import com.example.venue.order.entity.PaymentRecord;
import com.example.venue.order.entity.RefundRecord;
import com.example.venue.order.enums.OrderStatus;
import com.example.venue.order.enums.RefundStatus;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.order.mapper.OrderStatusLogMapper;
import com.example.venue.order.mapper.PaymentRecordMapper;
import com.example.venue.order.mapper.RefundRecordMapper;
import com.example.venue.order.service.OrderNumberGenerator;
import com.example.venue.payment.dto.DevPaymentScenario;
import com.example.venue.payment.dto.PaymentAttemptStatus;
import com.example.venue.payment.dto.PaymentCallbackCommand;
import com.example.venue.payment.dto.PaymentCallbackEnvelope;
import com.example.venue.payment.dto.PaymentCallbackProcessResult;
import com.example.venue.payment.dto.PaymentCallbackResult;
import com.example.venue.payment.dto.PaymentCallbackStatus;
import com.example.venue.payment.dto.PaymentAction;
import com.example.venue.payment.dto.PaymentCreateCommand;
import com.example.venue.payment.dto.PaymentCreateResult;
import com.example.venue.payment.dto.PaymentIntentResponse;
import com.example.venue.payment.dto.PaymentProviderOperationType;
import com.example.venue.payment.dto.VerifiedPaymentCallback;
import com.example.venue.payment.entity.PaymentAttempt;
import com.example.venue.payment.entity.PaymentProviderOperationLog;
import com.example.venue.payment.enums.PaymentSourceType;
import com.example.venue.payment.mapper.PaymentAttemptMapper;
import com.example.venue.payment.provider.PaymentProvider;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import com.example.venue.payment.provider.PaymentProviderEvidenceMirror;
import com.example.venue.payment.provider.PaymentProviderRouter;
import com.example.venue.payment.provider.alipay.AlipaySandboxException;
import com.example.venue.promotion.service.CouponService;
import com.example.venue.promotion.service.PromotionPricingService;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.reservation.mapper.ReservationStatusLogMapper;
import com.example.venue.resource.service.ResourceAllocationService;
import com.example.venue.security.SecurityContext;
import com.example.venue.venue.service.VenueAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final DateTimeFormatter PAYMENT_NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    /**
     * A26-P3-09：对账取数窗口按 Asia/Shanghai 切日，支付记录的 pay_time 必须用同一时区写入，
     * 避免容器 JVM 默认时区（如 UTC）与对账切日时区错位导致同笔支付在两个日期各产生一条假差异。
     */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String ALIPAY_SANDBOX_PROVIDER_KEY = "payment.alipay.sandbox";
    private static final String ALIPAY_PAGE_PAY_CREATE_FAILED = "ALIPAY_PAGE_PAY_CREATE_FAILED";
    private static final String PAYMENT_CREATE_FAILED = "PAYMENT_CREATE_FAILED";
    private static final String PAYMENT_PROVIDER_NOT_FOUND = "PAYMENT_PROVIDER_NOT_FOUND";
    private static final String ALIPAY_SANDBOX_PROVIDER_ENV = "SANDBOX";
    private static final String ALIPAY_SANDBOX_PROVIDER_NAME = "ALIPAY";
    private static final String ALIPAY_SANDBOX_CONFIG_VERSION = "ALIPAY_SANDBOX_V1";
    private static final String RAW_CALLBACK_PROVIDER_NOT_CONFIGURED = "PAYMENT_PROVIDER_NOT_CONFIGURED";
    private static final String RAW_CALLBACK_VERIFICATION_FAILED = "CALLBACK_VERIFICATION_FAILED";
    private static final String RAW_CALLBACK_ATTEMPT_NOT_FOUND = "PAYMENT_ATTEMPT_NOT_FOUND";
    private static final String RAW_CALLBACK_ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    private static final String RAW_CALLBACK_SNAPSHOT_MISMATCH = "PROVIDER_SNAPSHOT_MISMATCH";
    private static final String RAW_CALLBACK_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    private static final String RAW_CALLBACK_ORDER_STATE_INVALID = "ORDER_STATE_INVALID";
    private static final String RAW_CALLBACK_SETTLEMENT_FAILED = "SETTLEMENT_FAILED";
    private static final String RAW_CALLBACK_PROCESS_FAILED = "CALLBACK_PROCESS_FAILED";

    private final BizOrderMapper orderMapper;
    private final OrderStatusLogMapper orderStatusLogMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final PaymentCallbackLogService callbackLogService;
    private final PaymentAttemptMapper paymentAttemptMapper;
    private final PaymentAttemptStateService paymentAttemptStateService;
    private final PaymentIntentPersistenceService paymentIntentPersistenceService;
    private final PaymentAttemptExpirationService paymentAttemptExpirationService;
    private final PaymentLocalTransactionService paymentLocalTransactionService;
    private final OrderLocalSettlementService orderLocalSettlementService;
    private final ReservationMapper reservationMapper;
    private final ReservationOccupancyMapper occupancyMapper;
    private final ReservationStatusLogMapper reservationStatusLogMapper;
    private final NoticeService noticeService;
    private final MemberService memberService;
    private final CouponService couponService;
    private final ReservationLifecycleService lifecycleService;
    private final FunnelEventService funnelEventService;
    private final VenueAccessService venueAccessService;
    private final OrderNumberGenerator numberGenerator;
    private final PaymentProviderRouter paymentProviderRouter;
    private final PaymentProviderEvidenceMirror paymentProviderEvidenceMirror;
    private final StateTransitionService stateTransitionService;
    private final ObjectMapper objectMapper;
    private final String paymentCallbackSecret;
    private BusinessEventService businessEventService;
    private ResourceAllocationService resourceAllocationService;
    private PromotionPricingService promotionPricingService;

    @Autowired
    public void setPromotionPricingService(PromotionPricingService promotionPricingService) {
        this.promotionPricingService = promotionPricingService;
    }

    public PaymentService(BizOrderMapper orderMapper, OrderStatusLogMapper orderStatusLogMapper,
                          PaymentRecordMapper paymentRecordMapper, RefundRecordMapper refundRecordMapper,
                          PaymentCallbackLogService callbackLogService,
                          PaymentAttemptMapper paymentAttemptMapper, PaymentAttemptStateService paymentAttemptStateService,
                          ReservationMapper reservationMapper,
                          ReservationOccupancyMapper occupancyMapper, ReservationStatusLogMapper reservationStatusLogMapper,
                          NoticeService noticeService, MemberService memberService, CouponService couponService,
                          ReservationLifecycleService lifecycleService, FunnelEventService funnelEventService,
                          VenueAccessService venueAccessService, OrderNumberGenerator numberGenerator,
                          PaymentProviderRouter paymentProviderRouter,
                          PaymentProviderEvidenceMirror paymentProviderEvidenceMirror,
                          StateTransitionService stateTransitionService,
                          ObjectMapper objectMapper,
                          @Value("${app.payment.callback-secret:}") String paymentCallbackSecret) {
        this(orderMapper, orderStatusLogMapper, paymentRecordMapper, refundRecordMapper, callbackLogService, paymentAttemptMapper,
            paymentAttemptStateService, reservationMapper, occupancyMapper, reservationStatusLogMapper, noticeService,
            memberService, couponService, lifecycleService, funnelEventService, venueAccessService, numberGenerator,
            paymentProviderRouter, paymentProviderEvidenceMirror, stateTransitionService, objectMapper,
            paymentCallbackSecret, new PaymentIntentPersistenceService(orderMapper, paymentAttemptMapper),
            new PaymentAttemptExpirationService(paymentAttemptMapper, paymentProviderRouter, callbackLogService,
                paymentProviderEvidenceMirror, objectMapper), new PaymentLocalTransactionService(),
            new OrderLocalSettlementService(orderMapper, paymentRecordMapper, paymentAttemptMapper, reservationMapper,
                stateTransitionService, lifecycleService, couponService, memberService, funnelEventService, noticeService));
    }

    @Autowired
    void setBusinessEventService(BusinessEventService businessEventService) {
        this.businessEventService = businessEventService;
    }

    @Autowired
    public void setResourceAllocationService(ResourceAllocationService resourceAllocationService) {
        this.resourceAllocationService = resourceAllocationService;
    }

    public PaymentService(BizOrderMapper orderMapper, OrderStatusLogMapper orderStatusLogMapper,
                          PaymentRecordMapper paymentRecordMapper, RefundRecordMapper refundRecordMapper,
                          PaymentCallbackLogService callbackLogService,
                          PaymentAttemptMapper paymentAttemptMapper, PaymentAttemptStateService paymentAttemptStateService,
                          ReservationMapper reservationMapper,
                          ReservationOccupancyMapper occupancyMapper, ReservationStatusLogMapper reservationStatusLogMapper,
                          NoticeService noticeService, MemberService memberService, CouponService couponService,
                          ReservationLifecycleService lifecycleService, FunnelEventService funnelEventService,
                          VenueAccessService venueAccessService, OrderNumberGenerator numberGenerator,
                          PaymentProviderRouter paymentProviderRouter,
                          PaymentProviderEvidenceMirror paymentProviderEvidenceMirror,
                          StateTransitionService stateTransitionService,
                          ObjectMapper objectMapper,
                          @Value("${app.payment.callback-secret:}") String paymentCallbackSecret,
                          PaymentIntentPersistenceService paymentIntentPersistenceService,
                          PaymentAttemptExpirationService paymentAttemptExpirationService,
                          PaymentLocalTransactionService paymentLocalTransactionService) {
        this(orderMapper, orderStatusLogMapper, paymentRecordMapper, refundRecordMapper, callbackLogService,
            paymentAttemptMapper, paymentAttemptStateService, reservationMapper, occupancyMapper,
            reservationStatusLogMapper, noticeService, memberService, couponService, lifecycleService,
            funnelEventService, venueAccessService, numberGenerator, paymentProviderRouter,
            paymentProviderEvidenceMirror, stateTransitionService, objectMapper, paymentCallbackSecret,
            paymentIntentPersistenceService, paymentAttemptExpirationService, paymentLocalTransactionService,
            new OrderLocalSettlementService(orderMapper, paymentRecordMapper, paymentAttemptMapper, reservationMapper,
                stateTransitionService, lifecycleService, couponService, memberService, funnelEventService, noticeService));
    }

    @Autowired
    public PaymentService(BizOrderMapper orderMapper, OrderStatusLogMapper orderStatusLogMapper,
                          PaymentRecordMapper paymentRecordMapper, RefundRecordMapper refundRecordMapper,
                          PaymentCallbackLogService callbackLogService,
                          PaymentAttemptMapper paymentAttemptMapper, PaymentAttemptStateService paymentAttemptStateService,
                          ReservationMapper reservationMapper,
                          ReservationOccupancyMapper occupancyMapper, ReservationStatusLogMapper reservationStatusLogMapper,
                          NoticeService noticeService, MemberService memberService, CouponService couponService,
                          ReservationLifecycleService lifecycleService, FunnelEventService funnelEventService,
                          VenueAccessService venueAccessService, OrderNumberGenerator numberGenerator,
                          PaymentProviderRouter paymentProviderRouter,
                          PaymentProviderEvidenceMirror paymentProviderEvidenceMirror,
                          StateTransitionService stateTransitionService,
                          ObjectMapper objectMapper,
                          @Value("${app.payment.callback-secret:}") String paymentCallbackSecret,
                          PaymentIntentPersistenceService paymentIntentPersistenceService,
                          PaymentAttemptExpirationService paymentAttemptExpirationService,
                          PaymentLocalTransactionService paymentLocalTransactionService,
                          OrderLocalSettlementService orderLocalSettlementService) {
        this.orderMapper = orderMapper;
        this.orderStatusLogMapper = orderStatusLogMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.refundRecordMapper = refundRecordMapper;
        this.callbackLogService = callbackLogService;
        this.paymentAttemptMapper = paymentAttemptMapper;
        this.paymentAttemptStateService = paymentAttemptStateService;
        this.paymentIntentPersistenceService = paymentIntentPersistenceService;
        this.paymentAttemptExpirationService = paymentAttemptExpirationService;
        this.paymentLocalTransactionService = paymentLocalTransactionService;
        this.orderLocalSettlementService = orderLocalSettlementService;
        this.reservationMapper = reservationMapper;
        this.occupancyMapper = occupancyMapper;
        this.reservationStatusLogMapper = reservationStatusLogMapper;
        this.noticeService = noticeService;
        this.memberService = memberService;
        this.couponService = couponService;
        this.lifecycleService = lifecycleService;
        this.funnelEventService = funnelEventService;
        this.venueAccessService = venueAccessService;
        this.numberGenerator = numberGenerator;
        this.paymentProviderRouter = paymentProviderRouter;
        this.paymentProviderEvidenceMirror = paymentProviderEvidenceMirror;
        this.stateTransitionService = stateTransitionService;
        this.objectMapper = objectMapper;
        this.paymentCallbackSecret = paymentCallbackSecret;
    }

    public PaymentIntentResponse createPaymentIntent(Long orderId) {
        Long userId = SecurityContext.currentUserId();
        String paymentNo = newPaymentNo();
        String idempotencyKey = "payment-intent-" + orderId + "-" + paymentNo;
        PaymentProvider defaultProvider = paymentProviderRouter.requireDefault();
        PaymentIntentPersistenceService.PreparedIntent prepared = paymentIntentPersistenceService.prepare(
            orderId, userId, defaultProvider.descriptor(), paymentNo, idempotencyKey);
        BizOrder order = prepared.order();
        if (prepared.expired()) {
            paymentLocalTransactionService.execute(() -> expireOrderForPayment(order));
            throw BusinessException.conflict("订单已超时，请重新预约");
        }
        PaymentAttempt attempt = prepared.attempt();
        long createStartedAt = System.currentTimeMillis();
        PaymentProviderDescriptor descriptor = prepared.newlyCreated()
            ? defaultProvider.descriptor()
            : historicalAttemptDescriptor(attempt);
        PaymentProvider provider;
        try {
            provider = prepared.newlyCreated()
                ? defaultProvider
                : paymentProviderRouter.require(attempt.providerEnv, attempt.providerName);
        } catch (BusinessException ex) {
            recordProviderOperation(PaymentProviderOperationType.CREATE_PAYMENT, descriptor, attempt, order.id, null,
                attempt.idempotencyKey, createFailureRequestSummary(descriptor, new PaymentCreateCommand(
                    attempt.paymentNo, attempt.orderNo, attempt.payableAmount, attempt.currency, attempt.idempotencyKey)),
                createFailureResponseSummary(descriptor, PAYMENT_PROVIDER_NOT_FOUND), "FAILED",
                PAYMENT_PROVIDER_NOT_FOUND, "支付创建失败", createStartedAt);
            throw ex;
        }
        descriptor = provider.descriptor();
        PaymentCreateCommand command = new PaymentCreateCommand(
            attempt.paymentNo,
            order.orderNo,
            attempt.payableAmount,
            "CNY",
            attempt.idempotencyKey
        );
        PaymentCreateResult createResult;
        try {
            createResult = createPaymentWithFailureEvidence(provider, descriptor, attempt, order, command, createStartedAt);
        } catch (RuntimeException ex) {
            if (prepared.newlyCreated()) {
                String failureCode = createFailureCode(descriptor, ex);
                paymentIntentPersistenceService.markInitialCreateFailed(attempt, failureCode, "支付创建失败",
                    createFailureRequestSummary(descriptor, command), createFailureResponseSummary(descriptor, failureCode));
            }
            throw ex;
        }
        recordProviderOperation(PaymentProviderOperationType.CREATE_PAYMENT, descriptor, attempt, order.id, null,
            attempt.idempotencyKey, createResult.requestSummaryJson(), createResult.responseSummaryJson(),
            "SUCCESS", null, null, createStartedAt);
        PaymentAttempt persistedAttempt = prepared.newlyCreated()
            ? paymentIntentPersistenceService.markCreateSucceeded(attempt, createResult)
            : attempt;

        if (prepared.newlyCreated()) {
            funnelEventService.recordMilestone(
                "PAYMENT_INITIATED",
                "PAYMENT",
                order.userId,
                order.venueId,
                order.reservationId,
                order.id,
                attempt.payableAmount,
                "STARTED",
                null,
                "payment-initiated-" + order.id + "-" + attempt.paymentNo,
                Map.of("channel", createResult.providerName(), "paymentNo", attempt.paymentNo),
                // A26-P1-03：用户在浏览器发起支付，从 X-Funnel-Session header 经 FunnelSessionFilter
                // 绑定的 ThreadLocal 提取 sessionKey，使支付发起里程碑与前序浏览器事件共享同一 sessionKey。
                FunnelSessionContext.current());
        }
        return toIntentResponse(persistedAttempt, createResult.paymentAction());
    }

    public PaymentIntentResponse detail(String paymentNo) {
        PaymentAttempt attempt = requireAttempt(paymentNo);
        ensureAttemptOwner(attempt);
        return toIntentResponse(attempt, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentCallbackProcessResult simulateDevCallback(String paymentNo, DevPaymentScenario scenario) {
        DevPaymentScenario requestedScenario = scenario == null ? DevPaymentScenario.SUCCESS : scenario;
        PaymentAttempt initialAttempt = requireAttempt(paymentNo);
        ensureAttemptOwner(initialAttempt);
        PaymentProvider initialProvider = paymentProviderRouter.require(
            initialAttempt.providerEnv, initialAttempt.providerName);
        if (!"payment.dev".equals(initialProvider.descriptor().providerKey())) {
            throw BusinessException.conflict(
                "DEV_PAYMENT_SIMULATION_NOT_ALLOWED: 当前支付意图不支持 DEV 模拟");
        }
        BizOrder order = orderMapper.selectByIdForUpdate(initialAttempt.orderId);
        if (order == null) {
            throw BusinessException.notFound("订单不存在");
        }
        ensureOrderOwner(order);
        PaymentAttempt attempt = requireAttempt(paymentNo);
        if (!Objects.equals(attempt.orderId, order.id)) {
            throw BusinessException.notFound("支付意图不存在");
        }
        ensureAttemptOwner(attempt);
        PaymentProvider provider = paymentProviderRouter.require(attempt.providerEnv, attempt.providerName);
        if (!"payment.dev".equals(provider.descriptor().providerKey())) {
            throw BusinessException.conflict(
                "DEV_PAYMENT_SIMULATION_NOT_ALLOWED: 当前支付意图不支持 DEV 模拟");
        }
        String externalRef = "dev-" + paymentNo;
        boolean duplicateRecordExists = paymentRecordMapper.findByTransactionId(externalRef) != null;
        if (requestedScenario == DevPaymentScenario.DUPLICATE
            || duplicateRecordExists
            || PaymentAttemptStatus.SUCCEEDED.name().equals(attempt.status)
            || OrderStatus.PAID.name().equals(order.status)) {
            recordDevCallback(attempt, externalRef, DevPaymentScenario.DUPLICATE,
                PaymentCallbackStatus.IGNORED_DUPLICATE.name(), "DUPLICATE", "重复回调已忽略", attempt.payableAmount);
            recordCallbackOperation(attempt, externalRef, failureSummaryJson(attempt, DevPaymentScenario.DUPLICATE,
                attempt.payableAmount), "{\"status\":\"IGNORED_DUPLICATE\"}", "IGNORED", "DUPLICATE",
                "重复回调已忽略", System.currentTimeMillis());
            return PaymentCallbackProcessResult.of(paymentNo, order.id, "IGNORED_DUPLICATE", order.status);
        }
        if (requestedScenario == DevPaymentScenario.SIGNATURE_INVALID) {
            recordDevCallback(attempt, externalRef, requestedScenario, DevPaymentScenario.SIGNATURE_INVALID.name(),
                "SIGNATURE_INVALID", "支付回调签名错误", attempt.payableAmount);
            paymentAttemptStateService.markTerminalFailureInRequiresNew(attempt, PaymentAttemptStatus.FAILED.name(),
                "SIGNATURE_INVALID", "支付回调签名错误",
                externalRef, failureSummaryJson(attempt, DevPaymentScenario.SIGNATURE_INVALID, attempt.payableAmount));
            recordCallbackOperation(attempt, externalRef, failureSummaryJson(attempt, requestedScenario,
                attempt.payableAmount), "{\"status\":\"SIGNATURE_INVALID\"}", "FAILED",
                "SIGNATURE_INVALID", "支付回调签名错误", System.currentTimeMillis());
            throw BusinessException.badRequest("支付回调签名错误");
        }
        if (requestedScenario == DevPaymentScenario.SUCCESS && isTerminalUnsuccessfulAttempt(attempt)) {
            recordDevCallback(attempt, externalRef, requestedScenario, PaymentCallbackStatus.FAILED.name(),
                "TERMINAL_ATTEMPT_REUSE", "支付意图已终态，请重新发起支付", attempt.payableAmount);
            recordCallbackOperation(attempt, externalRef, failureSummaryJson(attempt, requestedScenario,
                attempt.payableAmount), "{\"status\":\"FAILED\",\"failureCode\":\"TERMINAL_ATTEMPT_REUSE\"}",
                "FAILED", "TERMINAL_ATTEMPT_REUSE", "支付意图已终态，请重新发起支付", System.currentTimeMillis());
            throw BusinessException.conflict("支付意图已终态，请重新发起支付");
        }
        if (requestedScenario == DevPaymentScenario.AMOUNT_MISMATCH) {
            BigDecimal callbackAmount = mismatchedAmount(attempt);
            recordDevCallback(attempt, externalRef, requestedScenario, PaymentCallbackStatus.AMOUNT_MISMATCH.name(),
                "AMOUNT_MISMATCH", "支付金额不一致", callbackAmount);
            paymentAttemptStateService.markTerminalFailureInRequiresNew(attempt, PaymentAttemptStatus.FAILED.name(),
                "AMOUNT_MISMATCH", "支付金额不一致",
                externalRef, failureSummaryJson(attempt, DevPaymentScenario.AMOUNT_MISMATCH, callbackAmount));
            recordCallbackOperation(attempt, externalRef, failureSummaryJson(attempt, requestedScenario,
                callbackAmount), "{\"status\":\"AMOUNT_MISMATCH\"}", "FAILED", "AMOUNT_MISMATCH",
                "支付金额不一致", System.currentTimeMillis());
            throw BusinessException.badRequest("支付金额不一致");
        }
        if (requestedScenario == DevPaymentScenario.FAILED) {
            recordDevCallback(attempt, externalRef, requestedScenario, PaymentCallbackStatus.FAILED.name(),
                "PROVIDER_FAILED", "支付渠道返回失败", attempt.payableAmount);
            updateUnsuccessfulAttempt(attempt, PaymentAttemptStatus.FAILED.name(), "PROVIDER_FAILED", "支付渠道返回失败",
                externalRef, failureSummaryJson(attempt, requestedScenario, attempt.payableAmount));
            recordCallbackOperation(attempt, externalRef, failureSummaryJson(attempt, requestedScenario,
                attempt.payableAmount), "{\"status\":\"FAILED\",\"failureCode\":\"PROVIDER_FAILED\"}",
                "FAILED", "PROVIDER_FAILED", "支付渠道返回失败", System.currentTimeMillis());
            return PaymentCallbackProcessResult.of(paymentNo, order.id, "FAILED", order.status);
        }
        if (requestedScenario == DevPaymentScenario.CANCELED) {
            recordDevCallback(attempt, externalRef, requestedScenario, PaymentCallbackStatus.FAILED.name(),
                "USER_CANCELED", "用户取消支付", attempt.payableAmount);
            updateUnsuccessfulAttempt(attempt, PaymentAttemptStatus.CANCELED.name(), "USER_CANCELED", "用户取消支付",
                externalRef, failureSummaryJson(attempt, requestedScenario, attempt.payableAmount));
            recordCallbackOperation(attempt, externalRef, failureSummaryJson(attempt, requestedScenario,
                attempt.payableAmount), "{\"status\":\"FAILED\",\"failureCode\":\"USER_CANCELED\"}",
                "FAILED", "USER_CANCELED", "用户取消支付", System.currentTimeMillis());
            return PaymentCallbackProcessResult.of(paymentNo, order.id, "CANCELED", order.status);
        }
        if (requestedScenario == DevPaymentScenario.TIMEOUT) {
            recordDevCallback(attempt, externalRef, requestedScenario, PaymentCallbackStatus.FAILED.name(),
                "PAYMENT_TIMEOUT", "支付超时", attempt.payableAmount);
            updateUnsuccessfulAttempt(attempt, PaymentAttemptStatus.TIMEOUT.name(), "PAYMENT_TIMEOUT", "支付超时",
                externalRef, failureSummaryJson(attempt, requestedScenario, attempt.payableAmount));
            recordCallbackOperation(attempt, externalRef, failureSummaryJson(attempt, requestedScenario,
                attempt.payableAmount), "{\"status\":\"FAILED\",\"failureCode\":\"PAYMENT_TIMEOUT\"}",
                "FAILED", "PAYMENT_TIMEOUT", "支付超时", System.currentTimeMillis());
            expireOrderForPayment(order);
            return PaymentCallbackProcessResult.of(paymentNo, order.id, "TIMEOUT", order.status);
        }
        ensurePayable(order, false);
        // A26-P2-05：DEV 模拟回调成功结算前校验 attempt.payableAmount == order.payableAmount，
        // 与 HMAC/raw 路径对齐。改期重定价（A26-P0-03）后旧 attempt 已被 closeActiveAttemptForRescheduledOrder
        // 原子置为 CLOSED + SUPERSEDED_BY_RESCHEDULE，此处活跃 attempt 的 payableAmount 必须等于订单应付，
        // 否则会以少付金额把订单置为 PAID（凭证签发、券核销、会员成长值全部按已支付执行）；
        // 反向（改期降价）则多收用户。
        BigDecimal orderPayableAmount = MoneyUtils.amountOrDefault(order.payableAmount, order.totalAmount);
        if (attempt.payableAmount == null || attempt.payableAmount.compareTo(orderPayableAmount) != 0) {
            recordDevCallback(attempt, externalRef, requestedScenario, PaymentCallbackStatus.AMOUNT_MISMATCH.name(),
                "AMOUNT_MISMATCH", "支付金额不一致", attempt.payableAmount);
            paymentAttemptStateService.markTerminalFailureInRequiresNew(attempt, PaymentAttemptStatus.FAILED.name(),
                "AMOUNT_MISMATCH", "支付金额不一致",
                externalRef, failureSummaryJson(attempt, DevPaymentScenario.AMOUNT_MISMATCH, attempt.payableAmount));
            recordCallbackOperation(attempt, externalRef, failureSummaryJson(attempt, requestedScenario,
                attempt.payableAmount), "{\"status\":\"AMOUNT_MISMATCH\"}", "FAILED", "AMOUNT_MISMATCH",
                "支付金额不一致", System.currentTimeMillis());
            throw BusinessException.badRequest("支付金额不一致");
        }
        long callbackStartedAt = System.currentTimeMillis();
        PaymentCallbackResult callback = provider.handleCallback(PaymentCallbackCommand.devScenario(
            paymentNo,
            attempt.orderNo,
            externalRef,
            attempt.payableAmount,
            requestedScenario
        ));
        ReservationLifecycleResponse credentialResponse;
        try {
            credentialResponse = settleSuccessfulPayment(attempt, order, callback, true);
            recordCallbackOperation(attempt, callback.externalRef(), callback.requestSummaryJson(),
                callback.responseSummaryJson(), "SUCCESS", null, null, callbackStartedAt);
        } catch (RuntimeException ex) {
            writeFailedCallbackLog(attempt, callback.externalRef(), callback.requestSummaryJson(),
                callback.responseSummaryJson(), "SETTLEMENT_FAILED", ex.getMessage());
            recordCallbackOperation(attempt, callback.externalRef(), callback.requestSummaryJson(),
                callback.responseSummaryJson(), "FAILED", "SETTLEMENT_FAILED", ex.getMessage(), callbackStartedAt);
            throw ex;
        }
        // A26-P1-01：DEV 模拟支付成功时，把签发响应中的核销短码与二维码载荷随结果一并交付给前端。
        // credentialResponse 可能为 null（如 mock 场景）；checkinCode/qrPayload 在非首次签发时为 null。
        String checkinCode = credentialResponse == null ? null : credentialResponse.checkinCode;
        String qrPayload = credentialResponse == null ? null : credentialResponse.qrPayload;
        return PaymentCallbackProcessResult.of(paymentNo, order.id, callback.status(), order.status,
            checkinCode, qrPayload);
    }

    /**
     * @deprecated 该方法为遗留模拟支付入口，被 OrderController.pay() 调用。
     * 请使用 {@link #createPaymentIntent(Long)} + {@link #simulateDevCallback(String, DevPaymentScenario)} 两步流程替代。
    */
    @Deprecated
    public BizOrder legacySimulatePay(Long orderId) {
        PaymentProvider defaultProvider = paymentProviderRouter.requireDefault();
        if (!"payment.dev".equals(defaultProvider.descriptor().providerKey())) {
            throw BusinessException.conflict(
                "DEV_PAYMENT_SIMULATION_NOT_ALLOWED: 当前支付意图不支持 DEV 模拟");
        }
        PaymentIntentResponse intent = createPaymentIntent(orderId);
        simulateDevCallback(intent.paymentNo, DevPaymentScenario.SUCCESS);
        return orderMapper.selectById(orderId);
    }

    /**
     * 支付渠道回调处理状态机。
     * <p>执行流程：接收回调 → 记录回调日志 → 验证签名 → 匹配支付渠道 → 校验金额 →
     * 幂等检测（防止重复回调）→ 成功则执行结算链路，失败则记录失败原因。</p>
     * <p>关键决策点：签名验证失败直接拒绝；金额不匹配标记 AMOUNT_MISMATCH；
     * 已处理的重复回调直接返回幂等结果。</p>
     * <p>副作用：写入 payment_callback_log、可能触发 settleSuccessfulPayment 全部副作用。</p>
     * <p>A26-P3-05：先写 RECEIVED 证据再解析 Provider，未知/停用 provider 的回调也必须有证据落库。</p>
     * <p>A26-P3-08：Provider 回调处理在无数据库事务、无订单行锁时执行；随后进入短事务重读并锁定
     * 订单与 attempt，重新校验金额、Provider 快照、幂等和状态后结算。</p>
     */
    public void handleProviderCallback(String providerName, PaymentCallbackRequest request) {
        PaymentProviderDescriptor fallbackDescriptor = unconfiguredCallbackDescriptor(providerName);
        PaymentCallbackLog receivedLog = providerCallbackLog(fallbackDescriptor, null, providerName, request);
        receivedLog.status = PaymentCallbackStatus.RECEIVED.name();
        receivedLog.message = "支付回调已接收";
        callbackLogService.record(receivedLog);

        long callbackStartedAt = System.currentTimeMillis();
        ProviderCallbackContext context = new ProviderCallbackContext();
        context.descriptor = fallbackDescriptor;
        context.requestSummaryJson = providerCallbackRequestSummary(fallbackDescriptor, request);
        try {
            PaymentProvider provider = paymentProviderRouter.requireByName(providerName);
            context.descriptor = provider.descriptor();
            context.requestSummaryJson = providerCallbackRequestSummary(context.descriptor, request);
            if (request.rawPayload == null || request.rawPayload.isBlank()) {
                context.failureCode = "RAW_PAYLOAD_MISSING";
                throw BusinessException.badRequest("支付回调原始报文不能为空");
            }
            if (!context.descriptor.providerName().equalsIgnoreCase(request.channel)) {
                context.failureCode = "PROVIDER_MISMATCH";
                throw BusinessException.badRequest("支付渠道不一致");
            }
            try {
                verifyPaymentCallbackSign(request);
            } catch (BusinessException ex) {
                context.failureCode = "SIGNATURE_INVALID";
                throw ex;
            }
            // Provider 调用严格发生在 PaymentLocalTransactionService 开启短事务之前。
            PaymentCallbackResult callback = provider.handleCallback(PaymentCallbackCommand.devScenario(
                null,
                request.orderNo,
                request.transactionId,
                request.amount,
                DevPaymentScenario.SUCCESS
            ));
            context.requestSummaryJson = callback.requestSummaryJson();
            context.responseSummaryJson = callback.responseSummaryJson();
            context.failureCode = null;
            paymentLocalTransactionService.execute(() -> processProviderCallback(
                providerName, request, callback, context, callbackStartedAt));
        } catch (RuntimeException ex) {
            String finalFailureCode = context.failureCode == null && context.responseSummaryJson != null
                ? "SETTLEMENT_FAILED"
                : firstNonBlank(context.failureCode, "CALLBACK_PROCESS_FAILED");
            PaymentCallbackLog failedLog = providerCallbackLog(
                context.descriptor, context.attempt, providerName, request);
            failedLog.status = callbackStatusForFailure(finalFailureCode).name();
            failedLog.message = ex.getMessage();
            failedLog.failureCode = finalFailureCode;
            failedLog.idempotencyKey = context.idempotencyKey;
            failedLog.requestSummaryJson = context.requestSummaryJson;
            failedLog.responseSummaryJson = context.responseSummaryJson;
            callbackLogService.record(failedLog);
            recordProviderOperation(PaymentProviderOperationType.HANDLE_CALLBACK, context.descriptor,
                context.attempt, context.orderId,
                request.transactionId, context.idempotencyKey, context.requestSummaryJson, context.responseSummaryJson,
                "FAILED", finalFailureCode, ex.getMessage(), callbackStartedAt);
            throw ex;
        }
    }

    private void processProviderCallback(String providerName, PaymentCallbackRequest request,
                                         PaymentCallbackResult callback, ProviderCallbackContext context,
                                         long callbackStartedAt) {
        BizOrder order = orderMapper.findByOrderNoForUpdate(request.orderNo);
        if (order == null) {
            context.failureCode = "ORDER_NOT_FOUND";
            throw BusinessException.notFound("订单不存在");
        }
        context.orderId = order.id;
        if (paymentRecordMapper.findByTransactionId(request.transactionId) != null
            || OrderStatus.PAID.name().equals(order.status)) {
            PaymentCallbackLog duplicateLog = providerCallbackLog(
                context.descriptor, null, providerName, request);
            duplicateLog.status = PaymentCallbackStatus.IGNORED_DUPLICATE.name();
            duplicateLog.message = "重复回调已忽略";
            duplicateLog.failureCode = "DUPLICATE";
            callbackLogService.record(duplicateLog);
            recordProviderOperation(PaymentProviderOperationType.HANDLE_CALLBACK, context.descriptor, null, order.id,
                request.transactionId, null, context.requestSummaryJson, "{\"status\":\"IGNORED_DUPLICATE\"}",
                "IGNORED", "DUPLICATE", "重复回调已忽略", callbackStartedAt);
            return;
        }
        if (OrderStatus.TIMEOUT.name().equals(order.status)) {
            PaymentAttempt lateAttempt = paymentAttemptMapper.findLatestByOrderId(order.id);
            if (lateAttempt == null || !isLateExpiredPayment(order, lateAttempt)) {
                context.failureCode = RAW_CALLBACK_ORDER_STATE_INVALID;
                throw BusinessException.conflict("订单当前状态不可支付");
            }
            bindProviderCallbackAttempt(context, lateAttempt);
            paymentProviderRouter.require(lateAttempt.providerEnv, lateAttempt.providerName);
            requireMatchingProviderAndAmount(context, lateAttempt, order, providerName, request);
            context.failureCode = null;
            settleLateSuccessfulPayment(lateAttempt, order, callback, true);
            recordProviderOperation(PaymentProviderOperationType.HANDLE_CALLBACK, context.descriptor,
                lateAttempt, order.id, callback.externalRef(), context.idempotencyKey, context.requestSummaryJson,
                context.responseSummaryJson, "ACKNOWLEDGED", RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING,
                "迟到支付已进入退款审核", callbackStartedAt);
            return;
        }
        if (!OrderStatus.PENDING_PAYMENT.name().equals(order.status)) {
            PaymentCallbackLog invalidOrderLog = providerCallbackLog(
                context.descriptor, null, providerName, request);
            invalidOrderLog.status = PaymentCallbackStatus.ORDER_STATE_INVALID.name();
            invalidOrderLog.message = "订单当前状态不可支付";
            invalidOrderLog.failureCode = "ORDER_STATE_INVALID";
            callbackLogService.record(invalidOrderLog);
            recordProviderOperation(PaymentProviderOperationType.HANDLE_CALLBACK, context.descriptor, null, order.id,
                request.transactionId, null, context.requestSummaryJson, "{\"status\":\"ORDER_STATE_INVALID\"}",
                "FAILED", "ORDER_STATE_INVALID", "订单当前状态不可支付", callbackStartedAt);
            return;
        }

        PaymentAttempt attempt = paymentAttemptMapper.findActiveByOrderId(order.id);
        if (attempt == null) {
            PaymentAttempt latestAttempt = paymentAttemptMapper.findLatestByOrderId(order.id);
            if (latestAttempt != null
                && PaymentAttemptStatus.CLOSED.name().equals(latestAttempt.status)
                && "SUPERSEDED_BY_RESCHEDULE".equals(latestAttempt.failureCode)
                && latestAttempt.payableAmount != null
                && request.amount.compareTo(latestAttempt.payableAmount) == 0) {
                bindProviderCallbackAttempt(context, latestAttempt);
                paymentProviderRouter.require(latestAttempt.providerEnv, latestAttempt.providerName);
                if (!providerMatches(latestAttempt, providerName, request.channel)) {
                    context.failureCode = "PROVIDER_MISMATCH";
                    throw BusinessException.badRequest("支付渠道不一致");
                }
                context.failureCode = null;
                settleRescheduledLateSuccessfulPayment(latestAttempt, order, callback, true);
                recordProviderOperation(PaymentProviderOperationType.HANDLE_CALLBACK, context.descriptor,
                    latestAttempt, order.id, callback.externalRef(), context.idempotencyKey,
                    context.requestSummaryJson, context.responseSummaryJson, "ACKNOWLEDGED",
                    RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING, "改期后旧支付已到账，进入退款审核",
                    callbackStartedAt);
                return;
            }
            context.failureCode = "PAYMENT_ATTEMPT_NOT_FOUND";
            throw BusinessException.notFound("支付意图不存在");
        }

        bindProviderCallbackAttempt(context, attempt);
        paymentProviderRouter.require(attempt.providerEnv, attempt.providerName);
        requireMatchingProviderAndAmount(context, attempt, order, providerName, request);
        context.failureCode = null;
        settleSuccessfulPayment(attempt, order, callback, true);
        recordProviderOperation(PaymentProviderOperationType.HANDLE_CALLBACK, context.descriptor, attempt, order.id,
            callback.externalRef(), context.idempotencyKey, context.requestSummaryJson, context.responseSummaryJson,
            "SUCCESS", null, null, callbackStartedAt);
    }

    private void bindProviderCallbackAttempt(ProviderCallbackContext context, PaymentAttempt attempt) {
        context.attempt = attempt;
        context.idempotencyKey = attempt.idempotencyKey;
        context.descriptor = historicalAttemptDescriptor(attempt);
    }

    private void requireMatchingProviderAndAmount(ProviderCallbackContext context, PaymentAttempt attempt,
                                                  BizOrder order, String providerName,
                                                  PaymentCallbackRequest request) {
        if (!providerMatches(attempt, providerName, request.channel)) {
            context.failureCode = "PROVIDER_MISMATCH";
            throw BusinessException.badRequest("支付渠道不一致");
        }
        BigDecimal orderPayableAmount = MoneyUtils.amountOrDefault(order.payableAmount, order.totalAmount);
        if (attempt.payableAmount == null
            || request.amount.compareTo(attempt.payableAmount) != 0
            || request.amount.compareTo(orderPayableAmount) != 0) {
            context.failureCode = "AMOUNT_MISMATCH";
            throw BusinessException.badRequest("支付金额不一致");
        }
    }

    /**
     * Handles the provider-specific Alipay form callback. The raw envelope remains in request memory only;
     * durable evidence is rebuilt from the allowlisted callback facts below.
     */
    public void handleRawProviderCallback(String providerKey, PaymentCallbackEnvelope envelope) {
        long callbackStartedAt = System.currentTimeMillis();
        PaymentProviderDescriptor descriptor = fixedRawCallbackEvidenceDescriptor();
        RawCallbackContext context = new RawCallbackContext();
        context.failureCode = RAW_CALLBACK_PROCESS_FAILED;

        callbackLogService.record(rawCallbackLog(
            descriptor, null, null, rawReceivedSummary(descriptor, envelope), null,
            PaymentCallbackStatus.RECEIVED.name(), null, "支付宝沙箱支付回调已接收", callbackStartedAt));

        try {
            context.failureCode = RAW_CALLBACK_PROVIDER_NOT_CONFIGURED;
            PaymentProvider provider = paymentProviderRouter.requireByKey(providerKey);
            PaymentProviderDescriptor routedDescriptor = provider.descriptor();

            context.failureCode = RAW_CALLBACK_SNAPSHOT_MISMATCH;
            verifyRawProviderDescriptor(routedDescriptor);
            descriptor = routedDescriptor;

            VerifiedPaymentCallback verified;
            try {
                context.failureCode = RAW_CALLBACK_VERIFICATION_FAILED;
                verified = provider.verifyCallback(envelope);
            } catch (AlipaySandboxException ex) {
                context.failureCode = ex.getCode();
                throw ex;
            }
            if (verified == null || verified.paymentNo() == null || verified.paymentNo().isBlank()
                || verified.externalRef() == null || verified.externalRef().isBlank()) {
                throw AlipaySandboxException.config(RAW_CALLBACK_VERIFICATION_FAILED);
            }
            context.externalRef = verified.externalRef();
            PaymentProviderDescriptor localDescriptor = descriptor;
            paymentLocalTransactionService.execute(() -> processVerifiedRawCallback(
                localDescriptor, verified, context, callbackStartedAt));
        } catch (RuntimeException ex) {
            recordRawFailureInRequiresNew(descriptor, context.attempt, context.orderId, context.externalRef, envelope,
                context.failureCode,
                callbackStartedAt);
            throw ex;
        }
    }

    private void processVerifiedRawCallback(PaymentProviderDescriptor descriptor, VerifiedPaymentCallback verified,
                                            RawCallbackContext context, long callbackStartedAt) {
        context.failureCode = RAW_CALLBACK_ATTEMPT_NOT_FOUND;
        PaymentAttempt initialAttempt = requireAttempt(verified.paymentNo());
        context.failureCode = RAW_CALLBACK_ORDER_NOT_FOUND;
        BizOrder order = orderMapper.selectByIdForUpdate(initialAttempt.orderId);
        if (order == null) {
            throw AlipaySandboxException.config(RAW_CALLBACK_ORDER_NOT_FOUND);
        }
        context.orderId = order.id;

        context.failureCode = RAW_CALLBACK_ATTEMPT_NOT_FOUND;
        PaymentAttempt attempt = requireAttempt(verified.paymentNo());
        context.attempt = attempt;
        try {
            context.failureCode = RAW_CALLBACK_SNAPSHOT_MISMATCH;
            verifyRawAttemptSnapshotAndAmounts(attempt, order, descriptor, verified);
        } catch (AlipaySandboxException ex) {
            context.failureCode = ex.getCode();
            throw ex;
        }

        String requestSummaryJson = rawVerifiedSummary(descriptor, attempt, verified, true);
        if (isRawDuplicateSuccess(verified.externalRef())) {
            recordRawDuplicate(descriptor, attempt, order, verified, requestSummaryJson, callbackStartedAt);
            return;
        }
        if (!verified.settleable()) {
            recordRawNonSettleable(descriptor, attempt, order, verified, requestSummaryJson, callbackStartedAt);
            return;
        }

        if (isLateExpiredPayment(order, attempt)) {
            context.failureCode = RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING;
            PaymentCallbackResult callback = toRawCallbackResult(descriptor, attempt, verified);
            settleLateSuccessfulPayment(attempt, order, callback, true);
            recordRawLateSuccess(descriptor, attempt, order, verified, requestSummaryJson, callbackStartedAt);
            return;
        }

        context.failureCode = RAW_CALLBACK_ORDER_STATE_INVALID;
        ensureRawSettlementState(order, attempt);
        context.failureCode = RAW_CALLBACK_SETTLEMENT_FAILED;
        PaymentCallbackResult callback = toRawCallbackResult(descriptor, attempt, verified);
        settleSuccessfulPayment(attempt, order, callback, true);
        recordProviderOperation(PaymentProviderOperationType.HANDLE_CALLBACK, descriptor, attempt, order.id,
            verified.externalRef(), attempt.idempotencyKey, requestSummaryJson,
            rawSuccessSummary(descriptor, attempt, verified), "SUCCESS", null, null, callbackStartedAt);
    }

    /**
     * Factory failures have no trusted envelope. Keep this path independent from routing and raw form data.
     */
    public void recordRejectedRawProviderCallback(String providerKey, String failureCode) {
        PaymentProviderDescriptor descriptor = fixedRawCallbackEvidenceDescriptor();
        String stableFailureCode = failureCode == null || failureCode.isBlank()
            ? RAW_CALLBACK_VERIFICATION_FAILED
            : failureCode;
        long callbackStartedAt = System.currentTimeMillis();
        callbackLogService.record(rawCallbackLog(
            descriptor, null, null, rawRejectedSummary(descriptor, stableFailureCode), null,
            PaymentCallbackStatus.RECEIVED.name(), stableFailureCode, "支付宝沙箱支付回调已拒绝", callbackStartedAt));
        recordRawFailureInRequiresNew(descriptor, null, null, null, null, stableFailureCode, callbackStartedAt);
    }

    private PaymentProviderDescriptor fixedRawCallbackEvidenceDescriptor() {
        return new PaymentProviderDescriptor(
            ALIPAY_SANDBOX_PROVIDER_KEY,
            ALIPAY_SANDBOX_PROVIDER_ENV,
            ALIPAY_SANDBOX_PROVIDER_NAME,
            "Alipay Sandbox",
            ALIPAY_SANDBOX_CONFIG_VERSION,
            false
        );
    }

    /**
     * A26-P3-05：HMAC 回调入口在解析 Provider 前使用的 fallback descriptor。
     * <p>用于在 {@code requireByName} 之前先写 RECEIVED 证据，确保未知/停用 provider 的回调也有证据落库。
     * providerName 取请求传入的值（保留可观测性），其余字段用占位字符串明确标识"未配置"。</p>
     */
    private PaymentProviderDescriptor unconfiguredCallbackDescriptor(String providerName) {
        return new PaymentProviderDescriptor(
            "payment.unknown",
            "UNKNOWN",
            providerName == null ? "UNKNOWN" : providerName,
            "Unconfigured Payment Provider",
            "UNKNOWN",
            false
        );
    }

    private void verifyRawProviderDescriptor(PaymentProviderDescriptor descriptor) {
        if (descriptor == null
            || !ALIPAY_SANDBOX_PROVIDER_KEY.equals(descriptor.providerKey())
            || !ALIPAY_SANDBOX_PROVIDER_ENV.equals(descriptor.providerEnv())
            || !ALIPAY_SANDBOX_PROVIDER_NAME.equals(descriptor.providerName())
            || !ALIPAY_SANDBOX_CONFIG_VERSION.equals(descriptor.configVersion())) {
            throw AlipaySandboxException.config(RAW_CALLBACK_SNAPSHOT_MISMATCH);
        }
    }

    private void verifyRawAttemptSnapshotAndAmounts(PaymentAttempt attempt, BizOrder order,
                                                    PaymentProviderDescriptor descriptor,
                                                    VerifiedPaymentCallback verified) {
        if (attempt == null || order == null
            || !ALIPAY_SANDBOX_PROVIDER_ENV.equals(attempt.providerEnv)
            || !ALIPAY_SANDBOX_PROVIDER_NAME.equals(attempt.providerName)
            || !ALIPAY_SANDBOX_CONFIG_VERSION.equals(attempt.configVersion)
            || !Objects.equals(attempt.orderId, order.id)
            || !ALIPAY_SANDBOX_PROVIDER_KEY.equals(descriptor.providerKey())
            || !ALIPAY_SANDBOX_PROVIDER_ENV.equals(descriptor.providerEnv())
            || !ALIPAY_SANDBOX_PROVIDER_NAME.equals(descriptor.providerName())
            || !ALIPAY_SANDBOX_CONFIG_VERSION.equals(descriptor.configVersion())
            || !"CNY".equals(attempt.currency)
            || !"CNY".equals(verified.currency())) {
            throw AlipaySandboxException.config(RAW_CALLBACK_SNAPSHOT_MISMATCH);
        }
        BigDecimal orderAmount = MoneyUtils.amountOrDefault(order.payableAmount, order.totalAmount);
        if (attempt.payableAmount == null || verified.paidAmount() == null
            || verified.paidAmount().compareTo(attempt.payableAmount) != 0
            || verified.paidAmount().compareTo(orderAmount) != 0) {
            throw AlipaySandboxException.config(RAW_CALLBACK_AMOUNT_MISMATCH);
        }
    }

    private boolean isRawDuplicateSuccess(String externalRef) {
        return externalRef != null && paymentRecordMapper.findByTransactionId(externalRef) != null;
    }

    private void ensureRawSettlementState(BizOrder order, PaymentAttempt attempt) {
        if (!OrderStatus.PENDING_PAYMENT.name().equals(order.status)
            || !PaymentAttemptStatus.PENDING.name().equals(attempt.status)) {
            throw AlipaySandboxException.config(RAW_CALLBACK_ORDER_STATE_INVALID);
        }
    }

    private PaymentCallbackResult toRawCallbackResult(PaymentProviderDescriptor descriptor, PaymentAttempt attempt,
                                                       VerifiedPaymentCallback verified) {
        BigDecimal paidAmount = verified.paidAmount().setScale(2);
        return new PaymentCallbackResult(
            PaymentCallbackStatus.SUCCESS.name(),
            verified.externalRef(),
            paidAmount,
            MoneyUtils.ZERO_AMOUNT,
            paidAmount,
            rawVerifiedSummary(descriptor, attempt, verified, true),
            rawSuccessSummary(descriptor, attempt, verified)
        );
    }

    private void recordRawDuplicate(PaymentProviderDescriptor descriptor, PaymentAttempt attempt, BizOrder order,
                                    VerifiedPaymentCallback verified, String requestSummaryJson,
                                    long callbackStartedAt) {
        String responseSummaryJson = rawAcknowledgementSummary(descriptor, attempt, verified, "IGNORED_DUPLICATE");
        callbackLogService.record(rawCallbackLog(
            descriptor, attempt, verified.externalRef(), requestSummaryJson, responseSummaryJson,
            PaymentCallbackStatus.IGNORED_DUPLICATE.name(), "DUPLICATE", "重复支付回调已忽略", callbackStartedAt));
        recordProviderOperation(PaymentProviderOperationType.HANDLE_CALLBACK, descriptor, attempt, order.id,
            verified.externalRef(), attempt.idempotencyKey, requestSummaryJson, responseSummaryJson,
            "IGNORED", "DUPLICATE", "重复支付回调已忽略", callbackStartedAt);
    }

    private void recordRawNonSettleable(PaymentProviderDescriptor descriptor, PaymentAttempt attempt, BizOrder order,
                                        VerifiedPaymentCallback verified, String requestSummaryJson,
                                        long callbackStartedAt) {
        boolean tradeClosed = "TRADE_CLOSED".equals(verified.providerTradeStatus());
        if (tradeClosed) {
            updateUnsuccessfulAttempt(attempt, PaymentAttemptStatus.CLOSED.name(), "TRADE_CLOSED", "支付宝交易已关闭",
                verified.externalRef(), rawAcknowledgementSummary(descriptor, attempt, verified, "CLOSED"));
        }
        String responseSummaryJson = rawAcknowledgementSummary(descriptor, attempt, verified,
            tradeClosed ? "CLOSED" : "ACKNOWLEDGED");
        callbackLogService.record(rawCallbackLog(
            descriptor, attempt, verified.externalRef(), requestSummaryJson, responseSummaryJson,
            tradeClosed ? PaymentCallbackStatus.FAILED.name() : PaymentCallbackStatus.SUCCESS.name(),
            tradeClosed ? "TRADE_CLOSED" : null, tradeClosed ? "支付宝交易已关闭" : "支付回调已确认，尚未结算", callbackStartedAt));
        recordProviderOperation(PaymentProviderOperationType.HANDLE_CALLBACK, descriptor, attempt, order.id,
            verified.externalRef(), attempt.idempotencyKey, requestSummaryJson, responseSummaryJson,
            tradeClosed ? "FAILED" : "ACKNOWLEDGED", tradeClosed ? "TRADE_CLOSED" : null,
            tradeClosed ? "支付宝交易已关闭" : null, callbackStartedAt);
    }

    private void recordRawLateSuccess(PaymentProviderDescriptor descriptor, PaymentAttempt attempt, BizOrder order,
                                      VerifiedPaymentCallback verified, String requestSummaryJson,
                                      long callbackStartedAt) {
        String responseSummaryJson = rawAcknowledgementSummary(
            descriptor, attempt, verified, RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING);
        callbackLogService.record(rawCallbackLog(
            descriptor, attempt, verified.externalRef(), requestSummaryJson, responseSummaryJson,
            PaymentCallbackStatus.SUCCESS.name(), RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING,
            "迟到支付已确认，已进入退款审核", callbackStartedAt));
        recordProviderOperation(PaymentProviderOperationType.HANDLE_CALLBACK, descriptor, attempt, order.id,
            verified.externalRef(), attempt.idempotencyKey, requestSummaryJson, responseSummaryJson,
            "ACKNOWLEDGED", RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING, "迟到支付已进入退款审核", callbackStartedAt);
    }

    private void recordRawFailureInRequiresNew(PaymentProviderDescriptor descriptor, PaymentAttempt attempt,
                                               Long orderId, String externalRef, PaymentCallbackEnvelope envelope,
                                               String failureCode, long callbackStartedAt) {
        String stableFailureCode = failureCode == null || failureCode.isBlank()
            ? RAW_CALLBACK_PROCESS_FAILED
            : failureCode;
        String requestSummaryJson = rawFailureSummary(descriptor, attempt, envelope, stableFailureCode);
        String responseSummaryJson = rawFailureResponseSummary(descriptor, stableFailureCode);
        callbackLogService.record(rawCallbackLog(
            descriptor, attempt, externalRef, requestSummaryJson, responseSummaryJson,
            callbackStatusForRawFailure(stableFailureCode).name(), stableFailureCode,
            "支付宝沙箱支付回调处理失败", callbackStartedAt));
        recordProviderOperation(PaymentProviderOperationType.HANDLE_CALLBACK, descriptor, attempt, orderId,
            externalRef, attempt == null ? null : attempt.idempotencyKey, requestSummaryJson, responseSummaryJson,
            "FAILED", stableFailureCode, "支付宝沙箱支付回调处理失败", callbackStartedAt);
    }

    private PaymentCallbackLog rawCallbackLog(PaymentProviderDescriptor descriptor, PaymentAttempt attempt,
                                              String externalRef, String requestSummaryJson,
                                              String responseSummaryJson, String status, String failureCode,
                                              String message, long callbackStartedAt) {
        PaymentCallbackLog log = new PaymentCallbackLog();
        log.channel = descriptor.providerName();
        log.orderNo = attempt == null ? null : attempt.orderNo;
        log.transactionId = maskedTradeSuffix(externalRef);
        log.payload = requestSummaryJson;
        log.status = status;
        log.message = message;
        log.sourceType = PaymentSourceType.PROVIDER.name();
        log.confidence = BigDecimal.ONE;
        log.providerEnv = attempt == null ? descriptor.providerEnv() : attempt.providerEnv;
        log.providerName = attempt == null ? descriptor.providerName() : attempt.providerName;
        log.externalRef = maskedTradeSuffix(externalRef);
        log.idempotencyKey = attempt == null ? null : attempt.idempotencyKey;
        log.requestSummaryJson = requestSummaryJson;
        log.responseSummaryJson = responseSummaryJson;
        log.failureCode = failureCode;
        log.configVersion = attempt == null ? descriptor.configVersion() : attempt.configVersion;
        log.durationMs = Math.max(0, System.currentTimeMillis() - callbackStartedAt);
        log.createTime = LocalDateTime.now();
        return log;
    }

    private PaymentCallbackStatus callbackStatusForRawFailure(String failureCode) {
        if ("ALIPAY_SIGNATURE_INVALID".equals(failureCode)) {
            return PaymentCallbackStatus.SIGNATURE_INVALID;
        }
        return callbackStatusForFailure(failureCode);
    }

    private String rawReceivedSummary(PaymentProviderDescriptor descriptor, PaymentCallbackEnvelope envelope) {
        ObjectNode node = rawCallbackSummary(descriptor, envelope, null, null);
        node.put("validated", false);
        return node.toString();
    }

    private String rawRejectedSummary(PaymentProviderDescriptor descriptor, String failureCode) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("provider", descriptor.providerKey());
        node.put("envelopeAccepted", false);
        node.put("failureCode", failureCode);
        return node.toString();
    }

    private String rawVerifiedSummary(PaymentProviderDescriptor descriptor, PaymentAttempt attempt,
                                      VerifiedPaymentCallback verified, boolean validated) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("provider", descriptor.providerKey());
        node.put("paymentNo", safeIdentifier(verified.paymentNo()));
        node.put("orderNo", attempt == null ? null : safeIdentifier(attempt.orderNo));
        node.put("tradeStatus", safeTradeStatus(verified.providerTradeStatus()));
        node.put("amount", safeAmount(verified.paidAmount()));
        node.put("currency", verified.currency());
        node.put("tradeSuffix", maskedTradeSuffix(verified.externalRef()));
        node.put("validated", validated);
        return node.toString();
    }

    private String rawSuccessSummary(PaymentProviderDescriptor descriptor, PaymentAttempt attempt,
                                     VerifiedPaymentCallback verified) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("provider", descriptor.providerKey());
        node.put("paymentNo", safeIdentifier(verified.paymentNo()));
        node.put("orderNo", attempt == null ? null : safeIdentifier(attempt.orderNo));
        node.put("tradeStatus", safeTradeStatus(verified.providerTradeStatus()));
        node.put("amount", safeAmount(verified.paidAmount()));
        node.put("tradeSuffix", maskedTradeSuffix(verified.externalRef()));
        node.put("settleable", true);
        node.put("status", "SUCCESS");
        return node.toString();
    }

    private String rawAcknowledgementSummary(PaymentProviderDescriptor descriptor, PaymentAttempt attempt,
                                             VerifiedPaymentCallback verified, String status) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("provider", descriptor.providerKey());
        node.put("paymentNo", safeIdentifier(verified.paymentNo()));
        node.put("orderNo", attempt == null ? null : safeIdentifier(attempt.orderNo));
        node.put("tradeStatus", safeTradeStatus(verified.providerTradeStatus()));
        node.put("amount", safeAmount(verified.paidAmount()));
        node.put("tradeSuffix", maskedTradeSuffix(verified.externalRef()));
        node.put("settleable", false);
        node.put("status", status);
        return node.toString();
    }

    private String rawFailureSummary(PaymentProviderDescriptor descriptor, PaymentAttempt attempt,
                                     PaymentCallbackEnvelope envelope, String failureCode) {
        ObjectNode node = rawCallbackSummary(descriptor, envelope, attempt, null);
        node.put("validated", false);
        node.put("failureCode", failureCode);
        return node.toString();
    }

    private String rawFailureResponseSummary(PaymentProviderDescriptor descriptor, String failureCode) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("provider", descriptor.providerKey());
        node.put("status", "FAILED");
        node.put("failureCode", failureCode);
        return node.toString();
    }

    private ObjectNode rawCallbackSummary(PaymentProviderDescriptor descriptor, PaymentCallbackEnvelope envelope,
                                          PaymentAttempt attempt, VerifiedPaymentCallback verified) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("provider", descriptor.providerKey());
        String paymentNo = verified == null ? rawEnvelopeValue(envelope, "out_trade_no") : verified.paymentNo();
        String tradeStatus = verified == null ? rawEnvelopeValue(envelope, "trade_status")
            : verified.providerTradeStatus();
        String tradeNo = verified == null ? rawEnvelopeValue(envelope, "trade_no") : verified.externalRef();
        String amount = verified == null ? rawEnvelopeValue(envelope, "total_amount")
            : safeAmount(verified.paidAmount());
        node.put("paymentNo", safeIdentifier(paymentNo));
        node.put("orderNo", attempt == null ? null : safeIdentifier(attempt.orderNo));
        node.put("tradeStatus", safeTradeStatus(tradeStatus));
        node.put("amount", safeAmount(amount));
        node.put("tradeSuffix", maskedTradeSuffix(tradeNo));
        return node;
    }

    private String rawEnvelopeValue(PaymentCallbackEnvelope envelope, String key) {
        if (envelope == null || envelope.formParameters() == null) {
            return null;
        }
        List<String> values = envelope.formParameters().get(key);
        return values != null && values.size() == 1 ? values.get(0) : null;
    }

    private String safeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}") ? value : null;
    }

    private String safeTradeStatus(String value) {
        return value != null && value.matches("[A-Z_]{1,32}") ? value : null;
    }

    private String safeAmount(String value) {
        return value != null && value.matches("[0-9]{1,12}\\.[0-9]{2}") ? value : null;
    }

    private String safeAmount(BigDecimal value) {
        return value == null ? null : safeAmount(value.setScale(2).toPlainString());
    }

    private String maskedTradeSuffix(String value) {
        String safeValue = safeIdentifier(value);
        if (safeValue == null) {
            return null;
        }
        return "***" + safeValue.substring(Math.max(0, safeValue.length() - 4));
    }

    private void ensurePayable(BizOrder order, boolean expireOnTimeout) {
        if (!OrderStatus.PENDING_PAYMENT.name().equals(order.status)) {
            throw BusinessException.badRequest("订单当前状态不可支付");
        }
        if (order.expireTime != null && order.expireTime.isBefore(LocalDateTime.now())) {
            if (expireOnTimeout) {
                expireOrderForPayment(order);
            }
            throw BusinessException.conflict("订单已超时，请重新预约");
        }
    }

    private PaymentAttempt requireAttempt(String paymentNo) {
        PaymentAttempt attempt = paymentAttemptMapper.findByPaymentNo(paymentNo);
        if (attempt == null) {
            throw BusinessException.notFound("支付意图不存在");
        }
        return attempt;
    }

    private void ensureAttemptOwner(PaymentAttempt attempt) {
        if (!Objects.equals(attempt.userId, SecurityContext.currentUserId())) {
            throw BusinessException.notFound("支付意图不存在");
        }
    }

    private void ensureOrderOwner(BizOrder order) {
        if (!Objects.equals(order.userId, SecurityContext.currentUserId())) {
            throw BusinessException.notFound("订单不存在");
        }
    }

    private void updateSuccessfulAttempt(PaymentAttempt attempt, PaymentCallbackResult callback) {
        attempt.status = PaymentAttemptStatus.SUCCEEDED.name();
        attempt.externalRef = callback.externalRef();
        attempt.paidAmount = callback.paidAmount();
        attempt.feeAmount = callback.feeAmount();
        attempt.netAmount = callback.netAmount();
        attempt.requestSummaryJson = callback.requestSummaryJson();
        attempt.responseSummaryJson = callback.responseSummaryJson();
        attempt.paidTime = LocalDateTime.now();
        attempt.updateTime = attempt.paidTime;
        paymentAttemptMapper.updateById(attempt);
    }

    private boolean isLateExpiredPayment(BizOrder order, PaymentAttempt attempt) {
        return OrderStatus.TIMEOUT.name().equals(order.status)
            && (PaymentAttemptStatus.TIMEOUT.name().equals(attempt.status)
                || PaymentAttemptStatus.PENDING.name().equals(attempt.status)
                || PaymentAttemptStatus.CREATED.name().equals(attempt.status));
    }

    private void settleLateSuccessfulPayment(PaymentAttempt attempt, BizOrder order, PaymentCallbackResult callback,
                                              boolean writeCallbackLog) {
        PaymentRecord payment = createSuccessPaymentRecord(attempt, callback);
        payment.failureCode = RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING;
        payment.metadataJson = "{\"disposition\":\"" + RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING + "\"}";
        paymentRecordMapper.insert(payment);

        updateSuccessfulAttempt(attempt, callback);
        attempt.failureCode = RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING;
        attempt.failureMessage = "迟到支付已确认，预约已释放，待人工退款审核";
        paymentAttemptMapper.updateById(attempt);
        // 迟到支付已确认进入退款审核，把订单从 TIMEOUT 推进到 REFUNDING，使后续 approveRefund 和
        // markRefundSuccess 能走 REFUNDING→REFUNDED 正常路径，不再因状态机无 TIMEOUT 出边而回滚卡住。
        stateTransitionService.transitionOrder(order, OrderStatus.REFUNDING.name(), "迟到支付已确认，进入退款审核");
        // A26-P2-06：迟到支付退款不恢复预约占用（保持 EXPIRED）。订单超时后预约已置 EXPIRED、
        // 占用记录已释放、已通知用户"时段不会恢复"；若把预约推回 REFUNDING（占用态），该时段在
        // 退款走完前重新被视为占用，与迟到支付"不恢复预约"承诺矛盾，且若期间已有人重新预订，
        // 同一时段会并存两条"占用中"预约。预约保持 EXPIRED，markRefundSuccess 时由 EXPIRED→REFUNDED
        // 直接迁移到退款完成终态（见 ReservationStatusTransitions）。
        createLatePaymentRefundDisposition(order, attempt, callback);
        if (writeCallbackLog) {
            writeLateSuccessCallbackLog(attempt, callback);
        }
        noticeService.createBusinessNotification(new NotificationEvent(
            "LATE_PAYMENT_REFUND_PENDING",
            "LATE_PAYMENT_REFUND_PENDING:" + order.id,
            order.userId,
            NotificationRecipientKind.USER,
            "支付已确认，等待退款审核",
            "该订单支付到账时预约已超时释放，已进入退款审核，不会恢复原预约。",
            "REFUND",
            order.id,
            Map.of("orderNo", order.orderNo)
        ));
    }

    /**
     * A26-P0-03：改期重定价后旧 attempt 已失效（CLOSED + SUPERSEDED_BY_RESCHEDULE），用户在旧支付页
     * 按旧金额扣款到达回调时的退款处置。
     *
     * <p>与 {@link #settleLateSuccessfulPayment} 的关键区别：
     * <ul>
     *   <li>不调 {@code stateTransitionService.transitionOrder}：订单保持 PENDING_PAYMENT，
     *       因为 OrderStatusTransitions 中 PENDING_PAYMENT 无 REFUNDING 出边，且用户需用新金额重新支付。</li>
     *   <li>不调 {@code stateTransitionService.transitionReservation}：预约保持 PENDING_PAYMENT
     *       （改期审核通过后的状态），用户改期后仍可用新金额支付该预约。</li>
     *   <li>其余逻辑保留：写成功 payment_record（带 LATE_SUCCESS_REFUND_PENDING 标记）、
     *       更新 attempt 为 SUCCEEDED + LATE_SUCCESS_REFUND_PENDING、创建 refund_record PENDING_REVIEW、
     *       写迟到回调日志、通知用户。</li>
     * </ul>
     *
     * <p>幂等键 "late-payment-refund-" + attempt.id 与 {@link #createLatePaymentRefundDisposition} 一致，
     * 若同一 attempt 多次回调或与迟到支付路径并发，靠 refund_record 唯一约束去重。
     */
    private void settleRescheduledLateSuccessfulPayment(PaymentAttempt attempt, BizOrder order,
                                                        PaymentCallbackResult callback, boolean writeCallbackLog) {
        PaymentRecord payment = createSuccessPaymentRecord(attempt, callback);
        payment.failureCode = RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING;
        payment.metadataJson = "{\"disposition\":\"" + RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING
            + "\",\"reason\":\"SUPERSEDED_BY_RESCHEDULE\"}";
        paymentRecordMapper.insert(payment);

        updateSuccessfulAttempt(attempt, callback);
        attempt.failureCode = RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING;
        attempt.failureMessage = "改期重定价后旧支付意图已失效，支付到账进入退款审核";
        paymentAttemptMapper.updateById(attempt);
        // 订单与预约保持 PENDING_PAYMENT：不改期状态机出边，用户需用新金额重新发起支付。
        createLatePaymentRefundDisposition(order, attempt, callback);
        if (writeCallbackLog) {
            writeLateSuccessCallbackLog(attempt, callback);
        }
        noticeService.createBusinessNotification(new NotificationEvent(
            "RESCHEDULED_LATE_PAYMENT_REFUND_PENDING",
            "RESCHEDULED_LATE_PAYMENT_REFUND_PENDING:" + order.id,
            order.userId,
            NotificationRecipientKind.USER,
            "改期后旧支付已到账，等待退款审核",
            "该订单改期后旧支付意图已失效，支付到账已进入退款审核，请用新金额重新发起支付或申请退款。",
            "REFUND",
            order.id,
            Map.of("orderNo", order.orderNo)
        ));
    }

    private void createLatePaymentRefundDisposition(BizOrder order, PaymentAttempt attempt,
                                                     PaymentCallbackResult callback) {
        long activeRefundCount = refundRecordMapper.selectCount(new QueryWrapper<RefundRecord>()
            .eq("order_id", order.id)
            .ne("status", RefundStatus.REJECTED.name()));
        if (activeRefundCount > 0) {
            return;
        }
        RefundRecord refund = new RefundRecord();
        refund.refundNo = numberGenerator.refundNo();
        refund.orderId = order.id;
        refund.userId = order.userId;
        refund.refundAmount = callback.paidAmount();
        refund.feeAmount = MoneyUtils.ZERO_AMOUNT;
        refund.netAmount = callback.paidAmount();
        refund.refundReason = "迟到支付成功，预约已超时释放";
        refund.auditReason = "渠道支付已确认，需人工退款或对账处置";
        refund.status = RefundStatus.PENDING_REVIEW.name();
        refund.sourceType = PaymentSourceType.PROVIDER.name();
        refund.confidence = BigDecimal.ONE;
        refund.providerEnv = attempt.providerEnv;
        refund.providerName = attempt.providerName;
        refund.externalRef = callback.externalRef();
        refund.idempotencyKey = "late-payment-refund-" + attempt.id;
        refund.requestSummaryJson = callback.requestSummaryJson();
        refund.responseSummaryJson = callback.responseSummaryJson();
        refund.failureCode = RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING;
        refund.configVersion = attempt.configVersion;
        refund.metadataJson = "{\"paymentAttemptId\":" + attempt.id + ",\"reservationReleased\":true}";
        refund.createTime = LocalDateTime.now();
        refund.updateTime = refund.createTime;
        refundRecordMapper.insert(refund);
    }

    private void writeSuccessCallbackLog(PaymentAttempt attempt, PaymentCallbackResult callback) {
        PaymentCallbackLog log = new PaymentCallbackLog();
        log.channel = attempt.channel;
        log.orderNo = attempt.orderNo;
        log.transactionId = callback.externalRef();
        log.payload = callback.requestSummaryJson();
        log.status = PaymentCallbackStatus.SUCCESS.name();
        log.message = "支付回调处理成功";
        log.sourceType = PaymentSourceType.PROVIDER.name();
        log.confidence = BigDecimal.ONE;
        log.providerEnv = attempt.providerEnv;
        log.providerName = attempt.providerName;
        log.externalRef = callback.externalRef();
        log.idempotencyKey = attempt.idempotencyKey;
        log.requestSummaryJson = callback.requestSummaryJson();
        log.responseSummaryJson = callback.responseSummaryJson();
        log.configVersion = attempt.configVersion;
        log.createTime = LocalDateTime.now();
        callbackLogService.recordInCurrentTransaction(log);
    }

    private void writeLateSuccessCallbackLog(PaymentAttempt attempt, PaymentCallbackResult callback) {
        PaymentCallbackLog log = new PaymentCallbackLog();
        log.channel = attempt.channel;
        log.orderNo = attempt.orderNo;
        log.transactionId = callback.externalRef();
        log.payload = callback.requestSummaryJson();
        log.status = PaymentCallbackStatus.SUCCESS.name();
        log.message = "迟到支付已确认，待退款审核";
        log.sourceType = PaymentSourceType.PROVIDER.name();
        log.confidence = BigDecimal.ONE;
        log.providerEnv = attempt.providerEnv;
        log.providerName = attempt.providerName;
        log.externalRef = callback.externalRef();
        log.idempotencyKey = attempt.idempotencyKey;
        log.requestSummaryJson = callback.requestSummaryJson();
        log.responseSummaryJson = callback.responseSummaryJson();
        log.failureCode = RefundFailureCodes.LATE_SUCCESS_REFUND_PENDING;
        log.configVersion = attempt.configVersion;
        log.createTime = LocalDateTime.now();
        callbackLogService.recordInCurrentTransaction(log);
    }

    private void writeFailedCallbackLog(PaymentAttempt attempt, String externalRef, String requestSummaryJson,
                                        String responseSummaryJson, String failureCode, String failureMessage) {
        PaymentCallbackLog log = new PaymentCallbackLog();
        log.channel = attempt.channel;
        log.orderNo = attempt.orderNo;
        log.transactionId = externalRef;
        log.payload = requestSummaryJson;
        log.status = PaymentCallbackStatus.FAILED.name();
        log.message = failureMessage;
        log.sourceType = PaymentSourceType.PROVIDER.name();
        log.confidence = BigDecimal.ONE;
        log.providerEnv = attempt.providerEnv;
        log.providerName = attempt.providerName;
        log.externalRef = externalRef;
        log.idempotencyKey = attempt.idempotencyKey;
        log.requestSummaryJson = requestSummaryJson;
        log.responseSummaryJson = responseSummaryJson;
        log.failureCode = failureCode;
        log.configVersion = attempt.configVersion;
        log.createTime = LocalDateTime.now();
        callbackLogService.record(log);
    }

    private PaymentCallbackLog providerCallbackLog(PaymentProviderDescriptor descriptor, PaymentAttempt attempt,
                                                   String providerName, PaymentCallbackRequest request) {
        PaymentCallbackLog callbackLog = new PaymentCallbackLog();
        callbackLog.channel = providerName;
        callbackLog.orderNo = request.orderNo;
        callbackLog.transactionId = request.transactionId;
        callbackLog.payload = SensitiveDataMasker.mask(request.rawPayload);
        callbackLog.sourceType = PaymentSourceType.PROVIDER.name();
        callbackLog.providerEnv = attempt == null ? descriptor.providerEnv() : attempt.providerEnv;
        callbackLog.providerName = attempt == null ? descriptor.providerName() : attempt.providerName;
        callbackLog.externalRef = request.transactionId;
        callbackLog.confidence = BigDecimal.ONE;
        callbackLog.configVersion = attempt == null ? descriptor.configVersion() : attempt.configVersion;
        callbackLog.createTime = LocalDateTime.now();
        return callbackLog;
    }

    private String providerCallbackRequestSummary(PaymentProviderDescriptor descriptor,
                                                  PaymentCallbackRequest request) {
        String amount = request.amount == null ? "" : request.amount.stripTrailingZeros().toPlainString();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("providerName", descriptor.providerName());
        node.put("orderNo", request.orderNo);
        node.put("externalRef", request.transactionId);
        node.put("amount", amount);
        node.put("channel", request.channel);
        return node.toString();
    }

    private void recordDevCallback(PaymentAttempt attempt, String externalRef, DevPaymentScenario scenario,
                                   String status, String failureCode, String message, BigDecimal callbackAmount) {
        PaymentCallbackLog log = new PaymentCallbackLog();
        log.channel = attempt.channel;
        log.orderNo = attempt.orderNo;
        log.transactionId = externalRef;
        log.payload = failureSummaryJson(attempt, scenario, callbackAmount);
        log.status = status;
        log.message = message;
        log.sourceType = PaymentSourceType.PROVIDER.name();
        log.confidence = BigDecimal.ONE;
        log.providerEnv = attempt.providerEnv;
        log.providerName = attempt.providerName;
        log.externalRef = externalRef;
        log.idempotencyKey = attempt.idempotencyKey;
        log.requestSummaryJson = log.payload;
        ObjectNode responseNode = objectMapper.createObjectNode();
        responseNode.put("providerName", attempt.providerName);
        responseNode.put("status", status);
        responseNode.put("failureCode", failureCode);
        log.responseSummaryJson = responseNode.toString();
        log.failureCode = failureCode;
        log.configVersion = attempt.configVersion;
        log.createTime = LocalDateTime.now();
        callbackLogService.record(log);
    }

    private void recordCallbackOperation(PaymentAttempt attempt, String externalRef, String requestSummaryJson,
                                         String responseSummaryJson, String status, String failureCode,
                                         String failureMessage, long startedAtMillis) {
        PaymentProviderDescriptor descriptor = paymentProviderRouter
            .require(attempt.providerEnv, attempt.providerName).descriptor();
        recordProviderOperation(PaymentProviderOperationType.HANDLE_CALLBACK, descriptor, attempt,
            attempt.orderId, externalRef, attempt.idempotencyKey,
            requestSummaryJson, responseSummaryJson, status, failureCode, failureMessage, startedAtMillis);
    }

    private PaymentCreateResult createPaymentWithFailureEvidence(PaymentProvider provider,
                                                                 PaymentProviderDescriptor descriptor,
                                                                 PaymentAttempt attempt,
                                                                 BizOrder order,
                                                                 PaymentCreateCommand command,
                                                                 long startedAtMillis) {
        try {
            PaymentCreateResult result = provider.createPayment(command);
            if (!hasValidCreateAction(descriptor, result)) {
                throw new IllegalStateException(PAYMENT_CREATE_FAILED);
            }
            return result;
        } catch (RuntimeException ex) {
            String failureCode = createFailureCode(descriptor, ex);
            recordProviderOperation(PaymentProviderOperationType.CREATE_PAYMENT, descriptor, attempt, order.id, null,
                command.idempotencyKey(), createFailureRequestSummary(descriptor, command),
                createFailureResponseSummary(descriptor, failureCode), "FAILED", failureCode, "支付创建失败",
                startedAtMillis);
            throw stableCreateFailure(descriptor, ex, failureCode);
        }
    }

    private String createFailureCode(PaymentProviderDescriptor descriptor, RuntimeException ex) {
        if (isAlipaySandbox(descriptor) && ex instanceof AlipaySandboxException alipayException) {
            return alipayException.getCode();
        }
        return isAlipaySandbox(descriptor) ? ALIPAY_PAGE_PAY_CREATE_FAILED : PAYMENT_CREATE_FAILED;
    }

    private RuntimeException stableCreateFailure(PaymentProviderDescriptor descriptor, RuntimeException ex,
                                                 String failureCode) {
        if (isAlipaySandbox(descriptor) && ex instanceof AlipaySandboxException) {
            return ex;
        }
        if (isAlipaySandbox(descriptor)) {
            return AlipaySandboxException.config(failureCode);
        }
        return new IllegalStateException(failureCode);
    }

    private boolean isAlipaySandbox(PaymentProviderDescriptor descriptor) {
        return descriptor != null && ALIPAY_SANDBOX_PROVIDER_KEY.equals(descriptor.providerKey());
    }

    private boolean hasValidCreateAction(PaymentProviderDescriptor descriptor, PaymentCreateResult result) {
        if (result == null || result.paymentAction() == null) {
            return false;
        }
        if (!isAlipaySandbox(descriptor)) {
            return true;
        }
        PaymentAction action = result.paymentAction();
        return "SUBMIT_FORM".equals(action.type())
            && "POST".equals(action.method())
            && action.url() != null
            && !action.url().isBlank()
            && !action.fields().isEmpty();
    }

    private String createFailureRequestSummary(PaymentProviderDescriptor descriptor, PaymentCreateCommand command) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("providerName", descriptor.providerName());
        node.put("operation", PaymentProviderOperationType.CREATE_PAYMENT.name());
        node.put("paymentNo", command.paymentNo());
        node.put("orderNo", command.orderNo());
        node.put("amount", command.amount() == null ? null : command.amount().toPlainString());
        node.put("currency", command.currency());
        return node.toString();
    }

    private String createFailureResponseSummary(PaymentProviderDescriptor descriptor, String failureCode) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("providerName", descriptor.providerName());
        node.put("status", "FAILED");
        node.put("failureCode", failureCode);
        return node.toString();
    }

    private void recordProviderOperation(PaymentProviderOperationType operationType,
                                         PaymentProviderDescriptor descriptor, PaymentAttempt attempt, Long orderId,
                                         String externalRef, String idempotencyKey, String requestSummaryJson,
                                         String responseSummaryJson, String status, String failureCode,
                                         String failureMessage, long startedAtMillis) {
        PaymentProviderOperationLog log = new PaymentProviderOperationLog();
        log.operationNo = "POP-" + UUID.randomUUID();
        log.operationType = operationType.name();
        log.providerEnv = attempt == null ? descriptor.providerEnv() : attempt.providerEnv;
        log.providerName = attempt == null ? descriptor.providerName() : attempt.providerName;
        log.paymentAttemptId = attempt == null ? null : attempt.id;
        log.orderId = firstNonNull(orderId, attempt == null ? null : attempt.orderId);
        log.externalRef = externalRef;
        log.idempotencyKey = firstNonBlank(idempotencyKey, attempt == null ? null : attempt.idempotencyKey);
        log.status = status;
        log.requestSummaryJson = requestSummaryJson;
        log.responseSummaryJson = responseSummaryJson;
        log.failureCode = failureCode;
        log.failureMessage = failureMessage;
        log.configVersion = attempt == null ? descriptor.configVersion() : attempt.configVersion;
        log.durationMs = Math.max(0, System.currentTimeMillis() - startedAtMillis);
        log.metadataJson = operationMetadataJson(descriptor, attempt);
        log.createTime = LocalDateTime.now();
        if (operationType == PaymentProviderOperationType.HANDLE_CALLBACK && "SUCCESS".equals(status)) {
            callbackLogService.recordOperationInCurrentTransaction(log);
            if (isExactAlipaySandboxDescriptor(descriptor)) {
                paymentProviderEvidenceMirror.mirrorAfterCommit(descriptor, log);
            }
        } else {
            callbackLogService.recordOperation(log);
            if (isExactAlipaySandboxDescriptor(descriptor)) {
                paymentProviderEvidenceMirror.mirrorImmediately(descriptor, log);
            }
        }
    }

    private boolean isExactAlipaySandboxDescriptor(PaymentProviderDescriptor descriptor) {
        return descriptor != null
            && ALIPAY_SANDBOX_PROVIDER_KEY.equals(descriptor.providerKey())
            && ALIPAY_SANDBOX_PROVIDER_ENV.equals(descriptor.providerEnv())
            && ALIPAY_SANDBOX_PROVIDER_NAME.equals(descriptor.providerName())
            && ALIPAY_SANDBOX_CONFIG_VERSION.equals(descriptor.configVersion());
    }

    private String operationMetadataJson(PaymentProviderDescriptor descriptor, PaymentAttempt attempt) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("providerEnv", attempt == null ? descriptor.providerEnv() : attempt.providerEnv);
        node.put("providerName", attempt == null ? descriptor.providerName() : attempt.providerName);
        if (attempt != null) {
            node.put("paymentNo", attempt.paymentNo);
            node.put("orderNo", attempt.orderNo);
        }
        return node.toString();
    }

    private PaymentCallbackStatus callbackStatusForFailure(String failureCode) {
        if ("SIGNATURE_INVALID".equals(failureCode)) {
            return PaymentCallbackStatus.SIGNATURE_INVALID;
        }
        if ("AMOUNT_MISMATCH".equals(failureCode)) {
            return PaymentCallbackStatus.AMOUNT_MISMATCH;
        }
        if ("ORDER_STATE_INVALID".equals(failureCode)) {
            return PaymentCallbackStatus.ORDER_STATE_INVALID;
        }
        return PaymentCallbackStatus.FAILED;
    }

    private boolean isTerminalUnsuccessfulAttempt(PaymentAttempt attempt) {
        return PaymentAttemptStatus.FAILED.name().equals(attempt.status)
            || PaymentAttemptStatus.CANCELED.name().equals(attempt.status)
            || PaymentAttemptStatus.TIMEOUT.name().equals(attempt.status)
            || PaymentAttemptStatus.CLOSED.name().equals(attempt.status);
    }

    private String failureSummaryJson(PaymentAttempt attempt, DevPaymentScenario scenario, BigDecimal callbackAmount) {
        String amount = callbackAmount == null ? "" : callbackAmount.stripTrailingZeros().toPlainString();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("providerName", attempt.providerName);
        node.put("paymentNo", attempt.paymentNo);
        node.put("orderNo", attempt.orderNo);
        node.put("scenario", scenario.name());
        node.put("amount", amount);
        return node.toString();
    }

    private BigDecimal mismatchedAmount(PaymentAttempt attempt) {
        BigDecimal base = attempt.payableAmount == null ? MoneyUtils.ZERO_AMOUNT : attempt.payableAmount;
        return base.add(new BigDecimal("0.01"));
    }

    private void updateUnsuccessfulAttempt(PaymentAttempt attempt, String status, String failureCode,
                                           String failureMessage, String externalRef, String summaryJson) {
        attempt.status = status;
        attempt.externalRef = externalRef;
        attempt.failureCode = failureCode;
        attempt.failureMessage = failureMessage;
        attempt.paidAmount = MoneyUtils.ZERO_AMOUNT;
        attempt.feeAmount = MoneyUtils.ZERO_AMOUNT;
        attempt.netAmount = MoneyUtils.ZERO_AMOUNT;
        attempt.requestSummaryJson = summaryJson;
        ObjectNode attemptResponseNode = objectMapper.createObjectNode();
        attemptResponseNode.put("providerName", attempt.providerName);
        attemptResponseNode.put("status", status);
        attemptResponseNode.put("failureCode", failureCode);
        attempt.responseSummaryJson = attemptResponseNode.toString();
        attempt.updateTime = LocalDateTime.now();
        paymentAttemptMapper.updateById(attempt);
        recordPaymentEvent(BusinessEventType.PAYMENT_FAILED, attempt, failureCode, failureMessage);
    }

    /**
     * 支付成功后的结算链路。
     * <p>执行顺序：创建支付记录 → 更新支付尝试状态 → 订单流转至 PAID → 预约流转至 PAID →
     * 签发凭证 → 核销优惠券 → 记录会员成长值 → 发送通知。</p>
     * <p>关键决策点：此方法在回调事务内执行，任何步骤失败将导致整个结算回滚。</p>
     * <p>副作用：写入 payment_record、更新 payment_attempt、更新 biz_order、更新 reservation、
     * 写入 voucher、更新 coupon 状态、写入 member_growth_log、发送通知。</p>
     */
    private ReservationLifecycleResponse settleSuccessfulPayment(PaymentAttempt attempt, BizOrder order, PaymentCallbackResult callback,
                                         boolean writeCallbackLog) {
        PaymentRecord payment = createSuccessPaymentRecord(attempt, callback);
        ReservationLifecycleResponse credentialResponse = orderLocalSettlementService.settle(
            new PaidOrderFact(order, attempt, payment, OrderLocalSettlementService.EXTERNAL_PROVIDER_TOOL,
                callback.externalRef(), attempt.channel, attempt.providerName, PaymentSourceType.PROVIDER.name(),
                callback.paidAmount(), callback.feeAmount(), callback.netAmount(), LocalDateTime.now()));
        if (writeCallbackLog) {
            writeSuccessCallbackLog(attempt, callback);
        }
        return credentialResponse;
    }

    private void recordPaymentEvent(BusinessEventType type, PaymentAttempt attempt, String reasonCode, String reasonText) {
        if (businessEventService == null) {
            return;
        }
        ProviderEnv providerEnv;
        try {
            providerEnv = attempt.providerEnv == null ? null : ProviderEnv.valueOf(attempt.providerEnv);
        } catch (IllegalArgumentException ignored) {
            providerEnv = null;
        }
        BusinessEventRecordRequest event = BusinessEventRecordRequest.of(type, BusinessDomain.PAYMENT,
                "PAYMENT_ATTEMPT", attempt.id, BusinessSourceType.PROVIDER,
                type == BusinessEventType.PAYMENT_SUCCEEDED ? "SUCCESS" : "FAILED")
            .relatedEntity("ORDER", attempt.orderId)
            .provider(providerEnv, attempt.providerName)
            .externalRef(attempt.externalRef)
            .idempotencyKey(type.name() + ":" + attempt.id)
            .reasonCode(reasonCode)
            .reasonText(reasonText)
            .metadata("paymentNo", attempt.paymentNo)
            .metadata("payableAmount", attempt.payableAmount);
        businessEventService.record(event);
    }

    private PaymentRecord createSuccessPaymentRecord(PaymentAttempt attempt, PaymentCallbackResult result) {
        PaymentRecord payment = new PaymentRecord();
        payment.paymentAttemptId = attempt.id;
        payment.orderId = attempt.orderId;
        payment.orderNo = attempt.orderNo;
        payment.channel = attempt.channel;
        payment.transactionId = result.externalRef();
        payment.payAmount = result.paidAmount();
        payment.feeAmount = result.feeAmount();
        payment.netAmount = result.netAmount();
        payment.status = "SUCCESS";
        // A26-P3-09：pay_time 显式使用 Asia/Shanghai 时区，与 FinanceReconciliationPersistenceService 切日窗口一致，
        // 避免容器 JVM 默认时区与对账切日时区错位导致同笔支付在两个日期各产生一条假差异。
        payment.payTime = LocalDateTime.now(SHANGHAI_ZONE);
        payment.sourceType = PaymentSourceType.PROVIDER.name();
        payment.providerEnv = attempt.providerEnv;
        payment.providerName = attempt.providerName;
        payment.externalRef = result.externalRef();
        payment.idempotencyKey = attempt.idempotencyKey;
        payment.requestSummaryJson = result.requestSummaryJson();
        payment.responseSummaryJson = result.responseSummaryJson();
        payment.configVersion = attempt.configVersion;
        payment.confidence = BigDecimal.ONE;
        payment.createTime = payment.payTime;
        return payment;
    }

    private void expireOrderForPayment(BizOrder order) {
        if (!OrderStatus.PENDING_PAYMENT.name().equals(order.status)) {
            return;
        }
        stateTransitionService.transitionOrder(order, OrderStatus.TIMEOUT.name(), "支付超时");
        paymentAttemptExpirationService.expireActiveAttemptAfterOrderCommit(order);
        Reservation reservation = reservationMapper.selectById(order.reservationId);
        if (reservation == null) {
            log.warn("订单 {} 关联预约 {} 不存在，跳过过期处理", order.id, order.reservationId);
            return;
        }
        stateTransitionService.transitionReservation(reservation, ReservationStatus.EXPIRED.name(), "支付超时释放占用", "SYSTEM");
        stateTransitionService.freezeCredentialIfPresent(reservation.id, "支付超时");
        releaseOccupancy(reservation.id, "PAYMENT_TIMEOUT");
        couponService.releaseForReservation(order.reservationId, "支付超时");
        // Match scheduled expiry: release the original campaign allocation through the shared CAS guard.
        if (reservation.promotionCampaignId != null) {
            promotionPricingService.releaseCampaignBudgetForReservation(reservation);
        }
        funnelEventService.recordMilestone(
            "PAYMENT_RESULT_RECORDED",
            "PAYMENT",
            order.userId,
            order.venueId,
            order.reservationId,
            order.id,
            MoneyUtils.amountOrDefault(order.payableAmount, order.totalAmount),
            "TIMEOUT",
            "PAYMENT_TIMEOUT",
            "payment-result-timeout-" + order.id,
            Map.of(),
            // AC-07 补修：定时任务没有浏览器 header，仍按预约/订单事实继承原始 session。
            FunnelSessionContext.current());
        try {
            noticeService.createBusinessNotification(new NotificationEvent(
                "PAYMENT_TIMEOUT",
                "PAYMENT_TIMEOUT:" + order.id,
                order.userId,
                NotificationRecipientKind.USER,
                "支付已超时",
                "订单 " + order.orderNo + " 已超时，预约时段已释放。",
                "PAYMENT",
                order.id,
                Map.of("orderNo", order.orderNo)
            ));
        } catch (Exception ex) {
            log.warn("支付超时通知发送失败，订单={}, eventId=PAYMENT_TIMEOUT:{}", order.id, order.id, ex);
        }
    }

    private void releaseOccupancy(Long reservationId, String reason) {
        if (resourceAllocationService == null) {
            occupancyMapper.releaseByReservationId(reservationId);
        } else {
            resourceAllocationService.release(reservationId, reason);
        }
    }

    private boolean providerMatches(PaymentAttempt attempt, String providerName, String channel) {
        return equalsIgnoreCase(providerName, attempt.providerName)
            && equalsIgnoreCase(channel, attempt.channel);
    }

    private boolean equalsIgnoreCase(String expected, String actual) {
        return expected != null && actual != null && expected.equalsIgnoreCase(actual);
    }

    private PaymentProviderDescriptor historicalAttemptDescriptor(PaymentAttempt attempt) {
        return paymentProviderRouter.catalog().stream()
            .filter(descriptor -> equalsIgnoreCase(descriptor.providerEnv(), attempt.providerEnv))
            .filter(descriptor -> equalsIgnoreCase(descriptor.providerName(), attempt.providerName))
            .findFirst()
            .orElseGet(() -> new PaymentProviderDescriptor(null, attempt.providerEnv, attempt.providerName,
                attempt.providerName, attempt.configVersion, false));
    }

    private void verifyPaymentCallbackSign(PaymentCallbackRequest request) {
        if (paymentCallbackSecret == null || paymentCallbackSecret.isBlank()) {
            throw BusinessException.badRequest("支付回调未配置签名密钥");
        }
        String expected = hmacSha256(callbackSignPayload(request), paymentCallbackSecret);
        boolean matched = MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            request.sign.getBytes(StandardCharsets.UTF_8)
        );
        if (!matched) {
            throw BusinessException.badRequest("支付回调签名错误");
        }
    }

    private String callbackSignPayload(PaymentCallbackRequest request) {
        return request.orderNo + "\n"
            + request.transactionId + "\n"
            + request.amount.stripTrailingZeros().toPlainString() + "\n"
            + request.channel;
    }

    private String hmacSha256(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw BusinessException.badRequest("支付回调签名错误");
        }
    }

    private PaymentIntentResponse toIntentResponse(PaymentAttempt attempt, PaymentAction paymentAction) {
        PaymentIntentResponse response = new PaymentIntentResponse();
        response.paymentNo = attempt.paymentNo;
        response.orderId = attempt.orderId;
        response.orderNo = attempt.orderNo;
        response.providerEnv = attempt.providerEnv;
        response.providerName = attempt.providerName;
        response.configVersion = attempt.configVersion;
        response.status = attempt.status;
        response.payableAmount = attempt.payableAmount;
        response.paidAmount = attempt.paidAmount;
        response.feeAmount = attempt.feeAmount;
        response.netAmount = attempt.netAmount;
        response.currency = attempt.currency;
        response.externalRef = attempt.externalRef;
        response.expireTime = attempt.expireTime;
        response.requestSummaryJson = attempt.requestSummaryJson;
        response.responseSummaryJson = attempt.responseSummaryJson;
        response.providerKey = paymentProviderRouter.catalog().stream()
            .filter(descriptor -> Objects.equals(descriptor.providerEnv(), attempt.providerEnv))
            .filter(descriptor -> Objects.equals(descriptor.providerName(), attempt.providerName))
            .map(PaymentProviderDescriptor::providerKey)
            .findFirst()
            .orElse(null);
        response.paymentAction = paymentAction;
        return response;
    }

    private String newPaymentNo() {
        return "PAY" + PAYMENT_NO_FORMATTER.format(LocalDateTime.now())
            + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    private Long firstNonNull(Long first, Long second) {
        return first == null ? second : first;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static final class RawCallbackContext {
        private PaymentAttempt attempt;
        private Long orderId;
        private String externalRef;
        private String failureCode;
    }

    private static final class ProviderCallbackContext {
        private PaymentProviderDescriptor descriptor;
        private PaymentAttempt attempt;
        private Long orderId;
        private String idempotencyKey;
        private String failureCode;
        private String requestSummaryJson;
        private String responseSummaryJson;
    }
}
