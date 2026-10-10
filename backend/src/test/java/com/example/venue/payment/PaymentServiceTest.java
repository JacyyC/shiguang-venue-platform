package com.example.venue.payment;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.checkin.dto.ReservationLifecycleResponse;
import com.example.venue.checkin.service.ReservationLifecycleService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.ResultCode;
import com.example.venue.common.StateTransitionService;
import com.example.venue.funnel.service.FunnelEventService;
import com.example.venue.member.service.MemberService;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.order.dto.PaymentCallbackRequest;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.entity.PaymentCallbackLog;
import com.example.venue.order.entity.PaymentRecord;
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
import com.example.venue.payment.dto.ProviderStatementItem;
import com.example.venue.payment.dto.ProviderStatementQuery;
import com.example.venue.payment.dto.VerifiedPaymentCallback;
import com.example.venue.payment.entity.PaymentAttempt;
import com.example.venue.payment.entity.PaymentProviderOperationLog;
import com.example.venue.payment.mapper.PaymentAttemptMapper;
import com.example.venue.payment.provider.PaymentProvider;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import com.example.venue.payment.provider.PaymentProviderEvidenceMirror;
import com.example.venue.payment.provider.PaymentProviderProperties;
import com.example.venue.payment.provider.PaymentProviderRouter;
import com.example.venue.payment.provider.alipay.AlipaySandboxException;
import com.example.venue.payment.service.DevPaymentProvider;
import com.example.venue.payment.service.PaymentAttemptStateService;
import com.example.venue.payment.service.PaymentCallbackLogService;
import com.example.venue.payment.service.PaymentService;
import com.example.venue.promotion.service.CouponService;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.reservation.mapper.ReservationStatusLogMapper;
import com.example.venue.resource.service.ResourceAllocationService;
import com.example.venue.venue.service.VenueAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {
    @Mock
    private BizOrderMapper orderMapper;
    @Mock
    private OrderStatusLogMapper orderStatusLogMapper;
    @Mock
    private PaymentRecordMapper paymentRecordMapper;
    @Mock
    private RefundRecordMapper refundRecordMapper;
    @Mock
    private PaymentCallbackLogService callbackLogService;
    @Mock
    private PaymentAttemptMapper paymentAttemptMapper;
    @Mock
    private PaymentAttemptStateService paymentAttemptStateService;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private ReservationOccupancyMapper occupancyMapper;
    @Mock
    private ReservationStatusLogMapper reservationStatusLogMapper;
    @Mock
    private NoticeService noticeService;
    @Mock
    private MemberService memberService;
    @Mock
    private CouponService couponService;
    @Mock
    private ReservationLifecycleService lifecycleService;
    @Mock
    private FunnelEventService funnelEventService;
    @Mock
    private VenueAccessService venueAccessService;
    @Mock
    private OrderNumberGenerator numberGenerator;
    @Mock
    private PaymentProviderEvidenceMirror paymentProviderEvidenceMirror;
    @Mock
    private ResourceAllocationService resourceAllocationService;

    @Mock
    private com.example.venue.promotion.service.PromotionPricingService promotionPricingService;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = newPaymentService("callback-secret");
        paymentService.setPromotionPricingService(promotionPricingService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(7L, "tester", Set.of("USER"), Set.of()), null, List.of()));
        // A26-P3-06：markCreateSucceeded 改用原子条件更新 markCreateSucceededIfActive；
        // 默认返回 1（更新成功），让未显式 stub 的 createPaymentIntent 测试走正常路径。
        lenient().when(paymentAttemptMapper.markCreateSucceededIfActive(any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createPaymentIntentCreatesPendingAttemptAndPaymentInitiatedMilestone() {
        BizOrder order = pendingOrder();
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(11L)).thenReturn(null);

        PaymentIntentResponse response = paymentService.createPaymentIntent(11L);

        verify(orderMapper).selectByIdForUpdate(11L);
        verify(orderMapper, never()).selectById(11L);
        assertThat(response.providerName).isEqualTo("DEV_PAYMENT");
        assertThat(response.status).isEqualTo("PENDING");
        verify(paymentAttemptMapper).insert(argThat((PaymentAttempt attempt) ->
            "DEV".equals(attempt.providerEnv)
                && "DEV_PAYMENT".equals(attempt.providerName)
                && attempt.payableAmount.compareTo(new BigDecimal("100.00")) == 0
                && attempt.paidAmount.compareTo(new BigDecimal("0.00")) == 0
                && attempt.feeAmount.compareTo(new BigDecimal("0.00")) == 0
                && attempt.netAmount.compareTo(new BigDecimal("0.00")) == 0));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.CREATE_PAYMENT.name().equals(log.operationType)
                && "SUCCESS".equals(log.status)
                && "DEV".equals(log.providerEnv)
                && "DEV_PAYMENT".equals(log.providerName)
                && "DEV_PAYMENT_V1".equals(log.configVersion)
                && order.id.equals(log.orderId)
                && log.requestSummaryJson.contains("ORD001")
                && log.responseSummaryJson.contains("PENDING")));
        verify(funnelEventService).recordMilestone(eq("PAYMENT_INITIATED"), eq("PAYMENT"),
            eq(order.userId), eq(order.venueId), eq(order.reservationId), eq(order.id),
            eq(order.payableAmount), eq("STARTED"), isNull(), startsWith("payment-initiated-"), any(), any());
        verifyNoInteractions(paymentProviderEvidenceMirror);
    }

    @Test
    void createPaymentIntentUsesSelectedProviderIdentityForAttemptOperationAndFunnel() {
        BizOrder order = pendingOrder();
        PaymentProviderDescriptor descriptor = new PaymentProviderDescriptor(
            "payment.test", "SANDBOX", "TEST_PAYMENT", "Test Payment", "TEST_PAYMENT_V2", true);
        PaymentService routedService = newPaymentService("callback-secret", router(testPaymentProvider(descriptor)));
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(11L)).thenReturn(null);

        PaymentIntentResponse response = routedService.createPaymentIntent(11L);

        assertThat(response.providerEnv).isEqualTo("SANDBOX");
        assertThat(response.providerName).isEqualTo("TEST_PAYMENT");
        assertThat(response.configVersion).isEqualTo("TEST_PAYMENT_V2");
        verify(paymentAttemptMapper).insert(argThat((PaymentAttempt attempt) ->
            "SANDBOX".equals(attempt.providerEnv)
                && "TEST_PAYMENT".equals(attempt.providerName)
                && "TEST_PAYMENT".equals(attempt.channel)
                && "TEST_PAYMENT_V2".equals(attempt.configVersion)));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            "SANDBOX".equals(log.providerEnv)
                && "TEST_PAYMENT".equals(log.providerName)
                && "TEST_PAYMENT_V2".equals(log.configVersion)));
        verify(funnelEventService).recordMilestone(eq("PAYMENT_INITIATED"), eq("PAYMENT"),
            eq(order.userId), eq(order.venueId), eq(order.reservationId), eq(order.id),
            eq(order.payableAmount), eq("STARTED"), isNull(), startsWith("payment-initiated-"),
            argThat((Map<String, Object> metadata) -> "TEST_PAYMENT".equals(metadata.get("channel"))), any());
    }

    @Test
    void createPaymentIntentReturnsOneTimeActionWithoutPersistingItsContents() {
        BizOrder order = pendingOrder();
        PaymentAction action = sandboxAction();
        PaymentProvider provider = mock(PaymentProvider.class);
        PaymentProviderDescriptor descriptor = sandboxDescriptor(true);
        when(provider.descriptor()).thenReturn(descriptor);
        when(provider.createPayment(any(PaymentCreateCommand.class))).thenReturn(sandboxCreateResult(action));
        PaymentService routedService = newPaymentService("callback-secret", router(provider));
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(null);

        PaymentIntentResponse response = routedService.createPaymentIntent(order.id);

        assertThat(response.providerKey).isEqualTo("payment.alipay.sandbox");
        assertThat(response.paymentAction).isEqualTo(action);
        verify(paymentAttemptMapper).insert(argThat((PaymentAttempt attempt) ->
            !attempt.requestSummaryJson.contains("action-token")
                && !attempt.requestSummaryJson.contains("sensitive-hidden-value")
                && !attempt.responseSummaryJson.contains("action-token")
                && !attempt.responseSummaryJson.contains("sensitive-hidden-value")));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.CREATE_PAYMENT.name().equals(log.operationType)
                && "SUCCESS".equals(log.status)
                && !log.requestSummaryJson.contains("action-token")
                && !log.responseSummaryJson.contains("sensitive-hidden-value")));
    }

    @Test
    void activeSandboxPendingAttemptRegeneratesOneTimeActionWithoutPersistenceChanges() {
        BizOrder order = pendingOrder();
        PaymentAttempt active = sandboxPendingAttempt(order);
        PaymentAction action = sandboxAction();
        PaymentProvider provider = mock(PaymentProvider.class);
        PaymentProviderDescriptor descriptor = sandboxDescriptor(true);
        when(provider.descriptor()).thenReturn(descriptor);
        when(provider.createPayment(any(PaymentCreateCommand.class))).thenReturn(sandboxCreateResult(action));
        PaymentService routedService = newPaymentService("callback-secret", router(provider));
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(active);

        PaymentIntentResponse response = routedService.createPaymentIntent(order.id);

        ArgumentCaptor<PaymentCreateCommand> commandCaptor = ArgumentCaptor.forClass(PaymentCreateCommand.class);
        verify(provider).createPayment(commandCaptor.capture());
        PaymentCreateCommand command = commandCaptor.getValue();
        assertThat(command.paymentNo()).isEqualTo(active.paymentNo);
        assertThat(command.orderNo()).isEqualTo(active.orderNo);
        assertThat(command.amount()).isEqualByComparingTo(active.payableAmount);
        assertThat(command.currency()).isEqualTo(active.currency);
        assertThat(command.idempotencyKey()).isEqualTo(active.idempotencyKey);
        assertThat(response.paymentNo).isEqualTo(active.paymentNo);
        assertThat(response.paymentAction).isEqualTo(action);
        assertThat(response.providerKey).isEqualTo("payment.alipay.sandbox");
        verify(paymentAttemptMapper, never()).insert(any(PaymentAttempt.class));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.CREATE_PAYMENT.name().equals(log.operationType)
                && "SUCCESS".equals(log.status)
                && active.id.equals(log.paymentAttemptId)
                && active.idempotencyKey.equals(log.idempotencyKey)
                && !log.requestSummaryJson.contains("action-token")
                && !log.responseSummaryJson.contains("sensitive-hidden-value")));
    }

    @Test
    void activeSandboxActionFailureRecordsStableEvidenceAndLeavesAttemptPending() {
        BizOrder order = pendingOrder();
        PaymentAttempt active = sandboxPendingAttempt(order);
        PaymentProvider provider = mock(PaymentProvider.class);
        PaymentProviderDescriptor descriptor = sandboxDescriptor(true);
        when(provider.descriptor()).thenReturn(descriptor);
        when(provider.createPayment(any(PaymentCreateCommand.class)))
            .thenThrow(AlipaySandboxException.config("ALIPAY_PAGE_PAY_CREATE_FAILED"));
        PaymentService routedService = newPaymentService("callback-secret", router(provider));
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(active);

        assertThatThrownBy(() -> routedService.createPaymentIntent(order.id))
            .isInstanceOf(AlipaySandboxException.class)
            .hasMessage("ALIPAY_PAGE_PAY_CREATE_FAILED");

        assertThat(active.status).isEqualTo(PaymentAttemptStatus.PENDING.name());
        verify(paymentAttemptMapper, never()).insert(any(PaymentAttempt.class));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.CREATE_PAYMENT.name().equals(log.operationType)
                && "FAILED".equals(log.status)
                && "ALIPAY_PAGE_PAY_CREATE_FAILED".equals(log.failureCode)
                && "支付创建失败".equals(log.failureMessage)
                && active.id.equals(log.paymentAttemptId)
                && !log.requestSummaryJson.contains("action-token")
                && !log.responseSummaryJson.contains("action-token")));
    }

    @Test
    void activeSandboxAttemptWithDisabledSnapshotProviderWritesFailedCreateEvidenceWithoutPersistenceChanges() {
        BizOrder order = pendingOrder();
        PaymentAttempt active = sandboxPendingAttempt(order);
        PaymentProvider defaultDevProvider = mock(PaymentProvider.class);
        when(defaultDevProvider.descriptor()).thenReturn(new PaymentProviderDescriptor(
            "payment.dev", "DEV", "DEV_PAYMENT", "Dev Payment", "DEV_PAYMENT_V1", true));
        PaymentProvider disabledSandboxProvider = mock(PaymentProvider.class);
        when(disabledSandboxProvider.descriptor()).thenReturn(sandboxDescriptor(false));
        PaymentService routedService = newPaymentService("callback-secret",
            router("payment.dev", defaultDevProvider, disabledSandboxProvider));
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(active);

        assertThatThrownBy(() -> routedService.createPaymentIntent(order.id))
            .isInstanceOf(BusinessException.class)
            .hasMessage("PAYMENT_PROVIDER_NOT_FOUND: 支付 Provider 不存在或未启用");

        assertThat(active.status).isEqualTo(PaymentAttemptStatus.PENDING.name());
        verify(disabledSandboxProvider, never()).createPayment(any(PaymentCreateCommand.class));
        verify(defaultDevProvider, never()).createPayment(any(PaymentCreateCommand.class));
        verify(paymentAttemptMapper, never()).insert(any(PaymentAttempt.class));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        ArgumentCaptor<PaymentProviderOperationLog> operationCaptor =
            ArgumentCaptor.forClass(PaymentProviderOperationLog.class);
        verify(callbackLogService, times(1)).recordOperation(operationCaptor.capture());
        assertUnavailableActiveAttemptOperation(operationCaptor.getValue(), active, order);
    }

    @Test
    void activeSandboxAttemptWithAbsentSnapshotProviderWritesFailedCreateEvidenceWithoutPersistenceChanges() {
        BizOrder order = pendingOrder();
        PaymentAttempt active = sandboxPendingAttempt(order);
        PaymentProvider defaultDevProvider = mock(PaymentProvider.class);
        when(defaultDevProvider.descriptor()).thenReturn(new PaymentProviderDescriptor(
            "payment.dev", "DEV", "DEV_PAYMENT", "Dev Payment", "DEV_PAYMENT_V1", true));
        PaymentService routedService = newPaymentService("callback-secret", router("payment.dev", defaultDevProvider));
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(active);

        assertThatThrownBy(() -> routedService.createPaymentIntent(order.id))
            .isInstanceOf(BusinessException.class)
            .hasMessage("PAYMENT_PROVIDER_NOT_FOUND: 支付 Provider 不存在或未启用");

        assertThat(active.status).isEqualTo(PaymentAttemptStatus.PENDING.name());
        verify(defaultDevProvider, never()).createPayment(any(PaymentCreateCommand.class));
        verify(paymentAttemptMapper, never()).insert(any(PaymentAttempt.class));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        ArgumentCaptor<PaymentProviderOperationLog> operationCaptor =
            ArgumentCaptor.forClass(PaymentProviderOperationLog.class);
        verify(callbackLogService, times(1)).recordOperation(operationCaptor.capture());
        assertUnavailableActiveAttemptOperation(operationCaptor.getValue(), active, order);
    }

    @Test
    void legacySimulatePayRejectsSandboxDefaultBeforeCreatingPaymentArtifacts() {
        BizOrder order = pendingOrder();
        PaymentProvider sandboxProvider = mock(PaymentProvider.class);
        when(sandboxProvider.descriptor()).thenReturn(sandboxDescriptor(true));
        PaymentService routedService = newPaymentService("callback-secret", router(sandboxProvider));

        assertThatThrownBy(() -> routedService.legacySimulatePay(order.id))
            .isInstanceOf(BusinessException.class)
            .hasMessage("DEV_PAYMENT_SIMULATION_NOT_ALLOWED: 当前支付意图不支持 DEV 模拟");

        verify(sandboxProvider, never()).createPayment(any(PaymentCreateCommand.class));
        verify(paymentAttemptMapper, never()).insert(any(PaymentAttempt.class));
        verify(callbackLogService, never()).recordOperation(any(PaymentProviderOperationLog.class));
    }

    @Test
    void newSandboxActionFailureTerminatesThePreparedAttemptWithStableEvidence() {
        BizOrder order = pendingOrder();
        PaymentProvider provider = mock(PaymentProvider.class);
        PaymentProviderDescriptor descriptor = sandboxDescriptor(true);
        when(provider.descriptor()).thenReturn(descriptor);
        when(provider.createPayment(any(PaymentCreateCommand.class)))
            .thenThrow(new IllegalStateException("raw-sdk-form-action-token"));
        PaymentService routedService = newPaymentService("callback-secret", router(provider));
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(null);

        assertThatThrownBy(() -> routedService.createPaymentIntent(order.id))
            .isInstanceOf(AlipaySandboxException.class)
            .hasMessage("ALIPAY_PAGE_PAY_CREATE_FAILED")
            .hasMessageNotContaining("raw-sdk-form-action-token");

        verify(paymentAttemptMapper).insert(any(PaymentAttempt.class));
        verify(paymentAttemptMapper).updateById(argThat((PaymentAttempt attempt) ->
            PaymentAttemptStatus.FAILED.name().equals(attempt.status)
                && "ALIPAY_PAGE_PAY_CREATE_FAILED".equals(attempt.failureCode)));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.CREATE_PAYMENT.name().equals(log.operationType)
                && "FAILED".equals(log.status)
                && "ALIPAY_PAGE_PAY_CREATE_FAILED".equals(log.failureCode)
                && "支付创建失败".equals(log.failureMessage)
                && log.paymentAttemptId == null
                && order.id.equals(log.orderId)
                && log.idempotencyKey.startsWith("payment-intent-11-")
                && !log.requestSummaryJson.contains("raw-sdk-form-action-token")
                && !log.responseSummaryJson.contains("raw-sdk-form-action-token")));
    }

    @Test
    void missingPaymentActionTerminatesThePreparedAttempt() {
        BizOrder order = pendingOrder();
        PaymentProvider provider = mock(PaymentProvider.class);
        PaymentProviderDescriptor descriptor = sandboxDescriptor(true);
        when(provider.descriptor()).thenReturn(descriptor);
        when(provider.createPayment(any(PaymentCreateCommand.class))).thenReturn(sandboxCreateResult(null));
        PaymentService routedService = newPaymentService("callback-secret", router(provider));
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(null);

        assertThatThrownBy(() -> routedService.createPaymentIntent(order.id))
            .isInstanceOf(AlipaySandboxException.class)
            .hasMessage("ALIPAY_PAGE_PAY_CREATE_FAILED");

        verify(paymentAttemptMapper).insert(any(PaymentAttempt.class));
        verify(paymentAttemptMapper).updateById(argThat((PaymentAttempt attempt) ->
            PaymentAttemptStatus.FAILED.name().equals(attempt.status)
                && "ALIPAY_PAGE_PAY_CREATE_FAILED".equals(attempt.failureCode)));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.CREATE_PAYMENT.name().equals(log.operationType)
                && "FAILED".equals(log.status)
                && "ALIPAY_PAGE_PAY_CREATE_FAILED".equals(log.failureCode)
                && log.paymentAttemptId == null));
    }

    @Test
    void missingPaymentActionForActiveAttemptRecordsFailureWithoutChangingAttempt() {
        BizOrder order = pendingOrder();
        PaymentAttempt active = sandboxPendingAttempt(order);
        PaymentProvider provider = mock(PaymentProvider.class);
        PaymentProviderDescriptor descriptor = sandboxDescriptor(true);
        when(provider.descriptor()).thenReturn(descriptor);
        when(provider.createPayment(any(PaymentCreateCommand.class))).thenReturn(sandboxCreateResult(null));
        PaymentService routedService = newPaymentService("callback-secret", router(provider));
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(active);

        assertThatThrownBy(() -> routedService.createPaymentIntent(order.id))
            .isInstanceOf(AlipaySandboxException.class)
            .hasMessage("ALIPAY_PAGE_PAY_CREATE_FAILED");

        assertThat(active.status).isEqualTo(PaymentAttemptStatus.PENDING.name());
        verify(paymentAttemptMapper, never()).insert(any(PaymentAttempt.class));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.CREATE_PAYMENT.name().equals(log.operationType)
                && "FAILED".equals(log.status)
                && "ALIPAY_PAGE_PAY_CREATE_FAILED".equals(log.failureCode)
                && active.id.equals(log.paymentAttemptId)));
    }

    @Test
    void sandboxNonFormActionTerminatesThePreparedAttempt() {
        BizOrder order = pendingOrder();
        PaymentProvider provider = mock(PaymentProvider.class);
        PaymentProviderDescriptor descriptor = sandboxDescriptor(true);
        when(provider.descriptor()).thenReturn(descriptor);
        when(provider.createPayment(any(PaymentCreateCommand.class))).thenReturn(sandboxCreateResult(
            new PaymentAction("DEV_SIMULATION", null, null, Map.of())));
        PaymentService routedService = newPaymentService("callback-secret", router(provider));
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(null);

        assertThatThrownBy(() -> routedService.createPaymentIntent(order.id))
            .isInstanceOf(AlipaySandboxException.class)
            .hasMessage("ALIPAY_PAGE_PAY_CREATE_FAILED");

        verify(paymentAttemptMapper).insert(any(PaymentAttempt.class));
        verify(paymentAttemptMapper).updateById(argThat((PaymentAttempt attempt) ->
            PaymentAttemptStatus.FAILED.name().equals(attempt.status)
                && "ALIPAY_PAGE_PAY_CREATE_FAILED".equals(attempt.failureCode)));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.CREATE_PAYMENT.name().equals(log.operationType)
                && "FAILED".equals(log.status)
                && "ALIPAY_PAGE_PAY_CREATE_FAILED".equals(log.failureCode)
                && log.paymentAttemptId == null));
    }

    @Test
    void nonAlipayProviderFailureUsesProviderNeutralStableCode() {
        BizOrder order = pendingOrder();
        PaymentProvider provider = mock(PaymentProvider.class);
        PaymentProviderDescriptor descriptor = new PaymentProviderDescriptor(
            "payment.test", "SANDBOX", "TEST_PAYMENT", "Test Payment", "TEST_PAYMENT_V2", true);
        when(provider.descriptor()).thenReturn(descriptor);
        when(provider.createPayment(any(PaymentCreateCommand.class)))
            .thenThrow(new IllegalStateException("raw-test-provider-error"));
        PaymentService routedService = newPaymentService("callback-secret", router(provider));
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(null);

        assertThatThrownBy(() -> routedService.createPaymentIntent(order.id))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("PAYMENT_CREATE_FAILED")
            .hasMessageNotContaining("raw-test-provider-error");

        verify(paymentAttemptMapper).insert(any(PaymentAttempt.class));
        verify(paymentAttemptMapper).updateById(argThat((PaymentAttempt attempt) ->
            PaymentAttemptStatus.FAILED.name().equals(attempt.status)
                && "PAYMENT_CREATE_FAILED".equals(attempt.failureCode)));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.CREATE_PAYMENT.name().equals(log.operationType)
                && "FAILED".equals(log.status)
                && "PAYMENT_CREATE_FAILED".equals(log.failureCode)
                && !log.requestSummaryJson.contains("raw-test-provider-error")
                && !log.responseSummaryJson.contains("raw-test-provider-error")));
    }

    @Test
    void terminalCreateFailureGetsANewPaymentNumberAndIdempotencyKeyOnRetry() {
        BizOrder order = pendingOrder();
        PaymentProvider provider = mock(PaymentProvider.class);
        PaymentProviderDescriptor descriptor = new PaymentProviderDescriptor(
            "payment.test", "SANDBOX", "TEST_PAYMENT", "Test Payment", "TEST_PAYMENT_V2", true);
        when(provider.descriptor()).thenReturn(descriptor);
        when(provider.createPayment(any(PaymentCreateCommand.class)))
            .thenThrow(new IllegalStateException("provider unavailable"))
            .thenReturn(new PaymentCreateResult(
                "SANDBOX", "TEST_PAYMENT", "TEST_PAYMENT_V2", "PENDING", new BigDecimal("100.00"),
                BigDecimal.ZERO, new BigDecimal("100.00"), "CNY", "{\"status\":\"PENDING\"}",
                "{\"status\":\"PENDING\"}", new PaymentAction("DEV_SIMULATION", null, null, Map.of())));
        PaymentService routedService = newPaymentService("callback-secret", router(provider));
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(null, null);

        assertThatThrownBy(() -> routedService.createPaymentIntent(order.id))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("PAYMENT_CREATE_FAILED");
        routedService.createPaymentIntent(order.id);

        ArgumentCaptor<PaymentAttempt> attempts = ArgumentCaptor.forClass(PaymentAttempt.class);
        verify(paymentAttemptMapper, times(2)).insert(attempts.capture());
        PaymentAttempt first = attempts.getAllValues().get(0);
        PaymentAttempt second = attempts.getAllValues().get(1);
        assertThat(first.paymentNo).isNotEqualTo(second.paymentNo);
        assertThat(first.idempotencyKey).isNotEqualTo(second.idempotencyKey);
        assertThat(first.idempotencyKey).contains(first.paymentNo);
        assertThat(second.idempotencyKey).contains(second.paymentNo);
        verify(paymentAttemptMapper).updateById(argThat((PaymentAttempt attempt) ->
            PaymentAttemptStatus.FAILED.name().equals(attempt.status)));
    }

    @Test
    void providerSuccessEvidenceIsRecordedBeforeLocalIntentCompletion() {
        BizOrder order = pendingOrder();
        PaymentProvider provider = mock(PaymentProvider.class);
        PaymentProviderDescriptor descriptor = new PaymentProviderDescriptor(
            "payment.test", "SANDBOX", "TEST_PAYMENT", "Test Payment", "TEST_PAYMENT_V2", true);
        when(provider.descriptor()).thenReturn(descriptor);
        when(provider.createPayment(any(PaymentCreateCommand.class))).thenReturn(new PaymentCreateResult(
            "SANDBOX", "TEST_PAYMENT", "TEST_PAYMENT_V2", "PENDING", new BigDecimal("100.00"),
            BigDecimal.ZERO, new BigDecimal("100.00"), "CNY", "{\"status\":\"PENDING\"}",
            "{\"status\":\"PENDING\"}", new PaymentAction("DEV_SIMULATION", null, null, Map.of())));
        PaymentService routedService = newPaymentService("callback-secret", router(provider));
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(null);
        // A26-P3-06：markCreateSucceeded 改用 markCreateSucceededIfActive 原子条件更新（不再调 updateById）
        doThrow(new IllegalStateException("local persistence failed"))
            .when(paymentAttemptMapper).markCreateSucceededIfActive(any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> routedService.createPaymentIntent(order.id))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("local persistence failed");

        InOrder orderOfEvidence = inOrder(callbackLogService, paymentAttemptMapper);
        orderOfEvidence.verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.CREATE_PAYMENT.name().equals(log.operationType)
                && "SUCCESS".equals(log.status)));
        orderOfEvidence.verify(paymentAttemptMapper).markCreateSucceededIfActive(any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any());
    }

    @Test
    void detailNeverReturnsPaymentActionAndUsesCatalogProviderKeyForHistoricalAttempt() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = sandboxPendingAttempt(order);
        PaymentProvider provider = mock(PaymentProvider.class);
        when(provider.descriptor()).thenReturn(sandboxDescriptor(false));
        PaymentService routedService = newPaymentService("callback-secret", router("payment.dev", provider));
        when(paymentAttemptMapper.findByPaymentNo(attempt.paymentNo)).thenReturn(attempt);

        PaymentIntentResponse response = routedService.detail(attempt.paymentNo);

        assertThat(response.providerKey).isEqualTo("payment.alipay.sandbox");
        assertThat(response.paymentAction).isNull();
    }

    @Test
    void simulateDevCallbackRejectsAttemptOwnedByNonDevProvider() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        attempt.providerEnv = "SANDBOX";
        attempt.providerName = "TEST_PAYMENT";
        attempt.channel = "TEST_PAYMENT";
        attempt.configVersion = "TEST_PAYMENT_V2";
        PaymentProviderDescriptor descriptor = new PaymentProviderDescriptor(
            "payment.test", "SANDBOX", "TEST_PAYMENT", "Test Payment", "TEST_PAYMENT_V2", true);
        PaymentService routedService = newPaymentService("callback-secret", router(testPaymentProvider(descriptor)));
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);

        assertThatThrownBy(() -> routedService.simulateDevCallback("PAY001", DevPaymentScenario.SUCCESS))
            .isInstanceOf(BusinessException.class)
            .hasMessage("DEV_PAYMENT_SIMULATION_NOT_ALLOWED: 当前支付意图不支持 DEV 模拟");

        verify(orderMapper, never()).selectById(any());
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
    }

    @Test
    void successScenarioMarksOrderReservationPaidAndWritesPaymentRecord() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        Reservation reservation = pendingPaymentReservation();
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);
        when(paymentRecordMapper.findByTransactionId("dev-PAY001")).thenReturn(null);

        paymentService.simulateDevCallback("PAY001", DevPaymentScenario.SUCCESS);

        verify(paymentRecordMapper).insert(argThat((PaymentRecord record) ->
            record.paymentAttemptId.equals(attempt.id)
                && "DEV_PAYMENT".equals(record.providerName)
                && record.payAmount.compareTo(new BigDecimal("100.00")) == 0
                && record.feeAmount.compareTo(new BigDecimal("0.60")) == 0));
        verify(orderMapper).updateById(argThat((BizOrder updated) -> OrderStatus.PAID.name().equals(updated.status)));
        verify(orderMapper).updateById(argThat((BizOrder updated) -> DevPaymentProvider.PROVIDER_NAME.equals(updated.payType)));
        verify(reservationMapper).updateById(argThat((Reservation updated) -> ReservationStatus.PAID.name().equals(updated.status)));
        verify(couponService).markUsedForOrder(eq(order.id), any(), any());
        verify(memberService).recordPaidOrder(any(BizOrder.class), any(Reservation.class));
        verify(lifecycleService).issueCredentialForPaidReservation(any(BizOrder.class), any(Reservation.class));
        verify(funnelEventService).recordMilestone(eq("PAYMENT_RESULT_RECORDED"), eq("PAYMENT"),
            eq(order.userId), eq(order.venueId), eq(order.reservationId), eq(order.id),
            eq(order.payableAmount), eq("SUCCESS"), isNull(), eq("payment-result-success-11-dev-PAY001"),
            argThat((Map<String, Object> metadata) ->
                "DEV_PAYMENT".equals(metadata.get("channel")) && "PAY001".equals(metadata.get("paymentNo"))), any());
        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "PAYMENT_SUCCEEDED".equals(event.eventType())
                && "PAYMENT_SUCCEEDED:11".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && "支付成功".equals(event.inAppTitle())
                && "订单 ORD001 已支付成功。".equals(event.inAppContent())
                && "PAYMENT".equals(event.businessType())
                && Long.valueOf(11L).equals(event.businessId())
                && Map.of("orderNo", "ORD001").equals(event.templateModel())));
        verify(callbackLogService).recordOperationInCurrentTransaction(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.HANDLE_CALLBACK.name().equals(log.operationType)
                && "SUCCESS".equals(log.status)
                && attempt.id.equals(log.paymentAttemptId)
                && order.id.equals(log.orderId)
                && "dev-PAY001".equals(log.externalRef)
                && log.responseSummaryJson.contains("SUCCESS")));
    }

    @Test
    void failedAttemptRetrySettlesOnlyTheNewAttemptAndKeepsDuplicateHandlingScopedToIt() {
        BizOrder order = pendingOrder();
        PaymentAttempt failedAttempt = pendingAttempt(order);
        failedAttempt.id = 51L;
        failedAttempt.paymentNo = "PAY001";
        failedAttempt.idempotencyKey = "payment-intent-11-PAY001";
        PaymentAttempt retryAttempt = pendingAttempt(order);
        retryAttempt.id = 52L;
        retryAttempt.paymentNo = "PAY002";
        retryAttempt.idempotencyKey = "payment-intent-11-PAY002";
        Reservation reservation = pendingPaymentReservation();
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(failedAttempt);
        when(paymentAttemptMapper.findByPaymentNo("PAY002")).thenReturn(retryAttempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);
        when(paymentRecordMapper.findByTransactionId("dev-PAY001")).thenReturn(null);
        when(paymentRecordMapper.findByTransactionId("dev-PAY002")).thenReturn(null);

        PaymentCallbackProcessResult failed = paymentService.simulateDevCallback(
            "PAY001", DevPaymentScenario.FAILED);
        PaymentCallbackProcessResult succeeded = paymentService.simulateDevCallback(
            "PAY002", DevPaymentScenario.SUCCESS);
        PaymentCallbackProcessResult duplicate = paymentService.simulateDevCallback(
            "PAY002", DevPaymentScenario.DUPLICATE);

        assertThat(failed.status).isEqualTo("FAILED");
        assertThat(failedAttempt.status).isEqualTo(PaymentAttemptStatus.FAILED.name());
        assertThat(failedAttempt.externalRef).isEqualTo("dev-PAY001");
        assertThat(succeeded.status).isEqualTo("SUCCESS");
        assertThat(duplicate.status).isEqualTo("IGNORED_DUPLICATE");
        assertThat(order.status).isEqualTo(OrderStatus.PAID.name());
        verify(paymentRecordMapper).insert(argThat((PaymentRecord record) ->
            Long.valueOf(52L).equals(record.paymentAttemptId)
                && "dev-PAY002".equals(record.transactionId)
                && "payment-intent-11-PAY002".equals(record.idempotencyKey)));
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            "FAILED".equals(log.status)
                && "PROVIDER_FAILED".equals(log.failureCode)
                && "dev-PAY001".equals(log.externalRef)));
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            "IGNORED_DUPLICATE".equals(log.status)
                && "DUPLICATE".equals(log.failureCode)
                && "payment-intent-11-PAY002".equals(log.idempotencyKey)
                && "dev-PAY002".equals(log.externalRef)));
    }

    @Test
    void simulateDevCallbackSuccessPropagatesCheckinCodeToProcessResult() {
        // A26-P1-01：DEV 模拟支付成功时，签发响应中的核销短码与 qrPayload 随 PaymentCallbackProcessResult 交付给前端
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        Reservation reservation = pendingPaymentReservation();
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);
        when(paymentRecordMapper.findByTransactionId("dev-PAY001")).thenReturn(null);
        ReservationLifecycleResponse credentialResponse = new ReservationLifecycleResponse();
        credentialResponse.reservationId = 99L;
        credentialResponse.checkinCode = "654321";
        credentialResponse.qrPayload = "CHECKIN:99:654321";
        when(lifecycleService.issueCredentialForPaidReservation(any(BizOrder.class), any(Reservation.class)))
            .thenReturn(credentialResponse);

        PaymentCallbackProcessResult result = paymentService.simulateDevCallback("PAY001", DevPaymentScenario.SUCCESS);

        assertThat(result.status).isEqualTo("SUCCESS");
        assertThat(result.checkinCode).isEqualTo("654321");
        assertThat(result.qrPayload).isEqualTo("CHECKIN:99:654321");
    }

    @Test
    void settleSuccessfulPaymentDoesNotMarkUsedWhenCouponDiscountAmountIsZero() {
        // A26-P0-02：order.userCouponId 残留但 couponDiscountAmount <= 0 时不核销券（双保险守卫）。
        // 主修复在 PromotionPricingService 弃券路径已清空 context.coupon.userCouponId，
        // 使 reservation/order.userCouponId 不写入；此处守卫防止边缘场景下 userCouponId 残留
        // 但折扣为 0 时仍把券置为 USED，导致用户券被无偿消耗。
        BizOrder order = pendingOrder();
        order.couponDiscountAmount = new BigDecimal("0.00");
        // userCouponId 保留非空，模拟边缘场景（如旧数据残留或并发写入竞态）
        order.userCouponId = 70L;
        PaymentAttempt attempt = pendingAttempt(order);
        Reservation reservation = pendingPaymentReservation();
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);
        when(paymentRecordMapper.findByTransactionId("dev-PAY001")).thenReturn(null);

        paymentService.simulateDevCallback("PAY001", DevPaymentScenario.SUCCESS);

        // 关键断言：couponDiscountAmount <= 0 时不调 markUsedForOrder，券不被核销
        verify(couponService, never()).markUsedForOrder(any(), any(), any());
        // 其余结算流程正常进行
        verify(orderMapper).updateById(argThat((BizOrder updated) -> OrderStatus.PAID.name().equals(updated.status)));
        verify(reservationMapper).updateById(argThat((Reservation updated) -> ReservationStatus.PAID.name().equals(updated.status)));
        verify(memberService).recordPaidOrder(any(BizOrder.class), any(Reservation.class));
        verify(lifecycleService).issueCredentialForPaidReservation(any(BizOrder.class), any(Reservation.class));
    }

    @Test
    void downstreamSettlementBusinessExceptionDoesNotWriteSuccessCallbackOrMarkPaid() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentRecordMapper.findByTransactionId("dev-PAY001")).thenReturn(null);
        when(paymentRecordMapper.insert(any(PaymentRecord.class)))
            .thenThrow(BusinessException.badRequest("支付记录写入失败"));

        assertThatThrownBy(() -> paymentService.simulateDevCallback("PAY001", DevPaymentScenario.SUCCESS))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("支付记录写入失败");

        verify(callbackLogService, never()).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.SUCCESS.name().equals(log.status)));
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "SETTLEMENT_FAILED".equals(log.failureCode)));
        verify(paymentAttemptMapper, never()).updateById(argThat((PaymentAttempt updated) ->
            PaymentAttemptStatus.SUCCEEDED.name().equals(updated.status)));
        verify(orderMapper, never()).updateById(argThat((BizOrder updated) -> OrderStatus.PAID.name().equals(updated.status)));
        verify(reservationMapper, never()).updateById(argThat((Reservation updated) -> ReservationStatus.PAID.name().equals(updated.status)));
        verify(couponService, never()).markUsedForOrder(any(), any(), any());
        verify(lifecycleService, never()).issueCredentialForPaidReservation(any(), any());
        verify(noticeService, never()).createBusinessNotification(argThat((NotificationEvent event) ->
            "PAYMENT_SUCCEEDED".equals(event.eventType())));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.HANDLE_CALLBACK.name().equals(log.operationType)
                && "FAILED".equals(log.status)
                && "SETTLEMENT_FAILED".equals(log.failureCode)));
    }

    @Test
    void signatureInvalidLocksOrderAndWritesCallbackLog() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);

        assertThatThrownBy(() -> paymentService.simulateDevCallback("PAY001", DevPaymentScenario.SIGNATURE_INVALID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("签名");

        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            "SIGNATURE_INVALID".equals(log.status)
                && "SIGNATURE_INVALID".equals(log.failureCode)
                && "DEV".equals(log.providerEnv)
                && "DEV_PAYMENT".equals(log.providerName)));
        verify(paymentAttemptStateService).markTerminalFailureInRequiresNew(eq(attempt),
            eq(PaymentAttemptStatus.FAILED.name()), eq("SIGNATURE_INVALID"), eq("支付回调签名错误"),
            eq("dev-PAY001"), argThat((String summary) -> summary.contains("\"scenario\":\"SIGNATURE_INVALID\"")));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        verify(orderMapper).selectByIdForUpdate(order.id);
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
    }

    @Test
    void amountMismatchKeepsOrderPendingAndLogsFailure() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);

        assertThatThrownBy(() -> paymentService.simulateDevCallback("PAY001", DevPaymentScenario.AMOUNT_MISMATCH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("金额");

        InOrder ordered = inOrder(callbackLogService, paymentAttemptStateService);
        ordered.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.AMOUNT_MISMATCH.name().equals(log.status)
                && "AMOUNT_MISMATCH".equals(log.failureCode)));
        ordered.verify(paymentAttemptStateService).markTerminalFailureInRequiresNew(eq(attempt),
            eq(PaymentAttemptStatus.FAILED.name()), eq("AMOUNT_MISMATCH"), eq("支付金额不一致"),
            eq("dev-PAY001"), argThat((String summary) -> summary.contains("\"scenario\":\"AMOUNT_MISMATCH\"")));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(orderMapper, never()).updateById(argThat((BizOrder updated) -> OrderStatus.PAID.name().equals(updated.status)));
    }

    @Test
    void failedProviderScenarioKeepsOrderPendingWithoutSuccessSideEffects() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);

        PaymentCallbackProcessResult result = paymentService.simulateDevCallback("PAY001", DevPaymentScenario.FAILED);

        assertThat(result.status).isEqualTo("FAILED");
        assertThat(result.orderStatus).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            "FAILED".equals(log.status) && "PROVIDER_FAILED".equals(log.failureCode)));
        verify(paymentAttemptMapper).updateById(argThat((PaymentAttempt updated) ->
            "FAILED".equals(updated.status)
                && "PROVIDER_FAILED".equals(updated.failureCode)
                && updated.paidAmount.compareTo(new BigDecimal("0.00")) == 0
                && updated.feeAmount.compareTo(new BigDecimal("0.00")) == 0
                && updated.netAmount.compareTo(new BigDecimal("0.00")) == 0));
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(orderMapper, never()).updateById(argThat((BizOrder updated) -> OrderStatus.PAID.name().equals(updated.status)));
        verify(couponService, never()).markUsedForOrder(any(), any(), any());
        verify(lifecycleService, never()).issueCredentialForPaidReservation(any(), any());
        verify(noticeService, never()).createBusinessNotification(argThat((NotificationEvent event) ->
            "PAYMENT_SUCCEEDED".equals(event.eventType())));
    }

    @Test
    void canceledScenarioKeepsOrderPendingWithoutSuccessSideEffects() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);

        PaymentCallbackProcessResult result = paymentService.simulateDevCallback("PAY001", DevPaymentScenario.CANCELED);

        assertThat(result.status).isEqualTo("CANCELED");
        assertThat(result.orderStatus).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status) && "USER_CANCELED".equals(log.failureCode)));
        verify(paymentAttemptMapper).updateById(argThat((PaymentAttempt updated) ->
            "CANCELED".equals(updated.status)
                && "USER_CANCELED".equals(updated.failureCode)
                && updated.paidAmount.compareTo(new BigDecimal("0.00")) == 0
                && updated.feeAmount.compareTo(new BigDecimal("0.00")) == 0
                && updated.netAmount.compareTo(new BigDecimal("0.00")) == 0));
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(orderMapper, never()).updateById(argThat((BizOrder updated) -> OrderStatus.PAID.name().equals(updated.status)));
        verify(couponService, never()).markUsedForOrder(any(), any(), any());
        verify(lifecycleService, never()).issueCredentialForPaidReservation(any(), any());
        verify(noticeService, never()).createBusinessNotification(argThat((NotificationEvent event) ->
            "PAYMENT_SUCCEEDED".equals(event.eventType())));
    }

    @Test
    void timeoutScenarioUsesExistingReleaseSemanticsAndLogsFailure() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        Reservation reservation = pendingPaymentReservation();
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);

        PaymentCallbackProcessResult result = paymentService.simulateDevCallback("PAY001", DevPaymentScenario.TIMEOUT);

        assertThat(result.status).isEqualTo("TIMEOUT");
        assertThat(result.orderStatus).isEqualTo(OrderStatus.TIMEOUT.name());
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status) && "PAYMENT_TIMEOUT".equals(log.failureCode)));
        verify(paymentAttemptMapper).updateById(argThat((PaymentAttempt updated) ->
            "TIMEOUT".equals(updated.status)
                && "PAYMENT_TIMEOUT".equals(updated.failureCode)
                && updated.paidAmount.compareTo(new BigDecimal("0.00")) == 0
                && updated.feeAmount.compareTo(new BigDecimal("0.00")) == 0
                && updated.netAmount.compareTo(new BigDecimal("0.00")) == 0));
        verify(orderMapper).updateById(argThat((BizOrder updated) -> OrderStatus.TIMEOUT.name().equals(updated.status)));
        verify(reservationMapper).updateById(argThat((Reservation updated) -> ReservationStatus.EXPIRED.name().equals(updated.status)));
        verify(lifecycleService).freezeCredentialForReservation(order.reservationId, "支付超时");
        verify(occupancyMapper).releaseByReservationId(order.reservationId);
        verify(couponService).releaseForReservation(order.reservationId, "支付超时");
        verify(funnelEventService).recordMilestone(eq("PAYMENT_RESULT_RECORDED"), eq("PAYMENT"),
            eq(order.userId), eq(order.venueId), eq(order.reservationId), eq(order.id),
            eq(order.payableAmount), eq("TIMEOUT"), eq("PAYMENT_TIMEOUT"),
            eq("payment-result-timeout-" + order.id), any(), any());
        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "PAYMENT_TIMEOUT".equals(event.eventType())
                && "PAYMENT_TIMEOUT:11".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && "支付已超时".equals(event.inAppTitle())
                && "订单 ORD001 已超时，预约时段已释放。".equals(event.inAppContent())
                && "PAYMENT".equals(event.businessType())
                && Long.valueOf(11L).equals(event.businessId())
                && Map.of("orderNo", "ORD001").equals(event.templateModel())));
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
    }

    @Test
    void timeoutScenarioReleasesThroughUnifiedResourceServiceWhenAvailable() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        Reservation reservation = pendingPaymentReservation();
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);
        paymentService.setResourceAllocationService(resourceAllocationService);

        paymentService.simulateDevCallback("PAY001", DevPaymentScenario.TIMEOUT);

        verify(resourceAllocationService).release(order.reservationId, "PAYMENT_TIMEOUT");
        verify(occupancyMapper, never()).releaseByReservationId(any());
    }

    @Test
    void timeoutScenarioOnExpiredPendingOrderStillLogsAttemptAndReleases() {
        BizOrder order = pendingOrder();
        order.expireTime = LocalDateTime.now().minusMinutes(1);
        PaymentAttempt attempt = pendingAttempt(order);
        Reservation reservation = pendingPaymentReservation();
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        lenient().when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);

        PaymentCallbackProcessResult[] result = new PaymentCallbackProcessResult[1];
        Throwable thrown = catchThrowable(() ->
            result[0] = paymentService.simulateDevCallback("PAY001", DevPaymentScenario.TIMEOUT));

        assertThat(thrown).isNull();
        assertThat(result[0].status).isEqualTo("TIMEOUT");
        assertThat(result[0].orderStatus).isEqualTo(OrderStatus.TIMEOUT.name());
        InOrder ordered = inOrder(callbackLogService, paymentAttemptMapper, orderMapper);
        ordered.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status) && "PAYMENT_TIMEOUT".equals(log.failureCode)));
        ordered.verify(paymentAttemptMapper).updateById(argThat((PaymentAttempt updated) ->
            "TIMEOUT".equals(updated.status) && "PAYMENT_TIMEOUT".equals(updated.failureCode)));
        ordered.verify(orderMapper).updateById(argThat((BizOrder updated) -> OrderStatus.TIMEOUT.name().equals(updated.status)));
        verify(reservationMapper).updateById(argThat((Reservation updated) -> ReservationStatus.EXPIRED.name().equals(updated.status)));
        verify(lifecycleService).freezeCredentialForReservation(order.reservationId, "支付超时");
        verify(occupancyMapper).releaseByReservationId(order.reservationId);
        verify(couponService).releaseForReservation(order.reservationId, "支付超时");
        verify(funnelEventService).recordMilestone(eq("PAYMENT_RESULT_RECORDED"), eq("PAYMENT"),
            eq(order.userId), eq(order.venueId), eq(order.reservationId), eq(order.id),
            eq(order.payableAmount), eq("TIMEOUT"), eq("PAYMENT_TIMEOUT"),
            eq("payment-result-timeout-" + order.id), any(), any());
        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "PAYMENT_TIMEOUT".equals(event.eventType())
                && "PAYMENT_TIMEOUT:11".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && Map.of("orderNo", "ORD001").equals(event.templateModel())));
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
    }

    @Test
    void failedProviderScenarioOnExpiredPendingOrderStillLogsAttemptAndKeepsOrderPending() {
        BizOrder order = pendingOrder();
        order.expireTime = LocalDateTime.now().minusMinutes(1);
        PaymentAttempt attempt = pendingAttempt(order);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);

        PaymentCallbackProcessResult[] result = new PaymentCallbackProcessResult[1];
        Throwable thrown = catchThrowable(() ->
            result[0] = paymentService.simulateDevCallback("PAY001", DevPaymentScenario.FAILED));

        assertThat(thrown).isNull();
        assertThat(result[0].status).isEqualTo("FAILED");
        assertThat(result[0].orderStatus).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
        InOrder ordered = inOrder(callbackLogService, paymentAttemptMapper);
        ordered.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            "FAILED".equals(log.status) && "PROVIDER_FAILED".equals(log.failureCode)));
        ordered.verify(paymentAttemptMapper).updateById(argThat((PaymentAttempt updated) ->
            "FAILED".equals(updated.status) && "PROVIDER_FAILED".equals(updated.failureCode)));
        verify(orderMapper, never()).updateById(any(BizOrder.class));
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        verify(occupancyMapper, never()).releaseByReservationId(any());
        verify(couponService, never()).releaseForReservation(any(), any());
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
    }

    @Test
    void duplicateSuccessCallbackIsIgnoredWithoutDuplicateSideEffects() {
        BizOrder order = paidOrder();
        PaymentAttempt attempt = succeededAttempt(order);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentRecordMapper.findByTransactionId("dev-PAY001")).thenReturn(new PaymentRecord());

        PaymentCallbackProcessResult result = paymentService.simulateDevCallback("PAY001", DevPaymentScenario.DUPLICATE);

        assertThat(result.status).isEqualTo("IGNORED_DUPLICATE");
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            "IGNORED_DUPLICATE".equals(log.status) && "DUPLICATE".equals(log.failureCode)));
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(memberService, never()).recordPaidOrder(any(), any());
        verify(lifecycleService, never()).issueCredentialForPaidReservation(any(), any());
    }

    @Test
    void devTimeoutReloadsAttemptAfterOrderLockAndCannotOverwritePublicSuccess() {
        BizOrder pendingOrder = pendingOrder();
        PaymentAttempt staleAttempt = pendingAttempt(pendingOrder);
        BizOrder paidOrder = paidOrder();
        PaymentAttempt settledAttempt = succeededAttempt(paidOrder);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(staleAttempt, settledAttempt);
        when(orderMapper.selectByIdForUpdate(pendingOrder.id)).thenReturn(paidOrder);

        PaymentCallbackProcessResult result = paymentService.simulateDevCallback("PAY001", DevPaymentScenario.TIMEOUT);

        assertThat(result.status).isEqualTo("IGNORED_DUPLICATE");
        assertThat(result.orderStatus).isEqualTo(OrderStatus.PAID.name());
        verify(orderMapper).selectByIdForUpdate(pendingOrder.id);
        verify(paymentAttemptMapper, times(2)).findByPaymentNo("PAY001");
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.IGNORED_DUPLICATE.name().equals(log.status)
                && "DUPLICATE".equals(log.failureCode)));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        verify(orderMapper, never()).updateById(any(BizOrder.class));
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        verify(occupancyMapper, never()).releaseByReservationId(any());
        verify(couponService, never()).releaseForReservation(any(), any());
    }

    @Test
    void devSignatureFailureCannotOverwritePublicSuccessAfterOrderLock() {
        BizOrder pendingOrder = pendingOrder();
        PaymentAttempt staleAttempt = pendingAttempt(pendingOrder);
        BizOrder paidOrder = paidOrder();
        PaymentAttempt settledAttempt = succeededAttempt(paidOrder);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(staleAttempt, settledAttempt);
        when(orderMapper.selectByIdForUpdate(pendingOrder.id)).thenReturn(paidOrder);

        PaymentCallbackProcessResult result = paymentService.simulateDevCallback(
            "PAY001", DevPaymentScenario.SIGNATURE_INVALID);

        assertThat(result.status).isEqualTo("IGNORED_DUPLICATE");
        assertThat(result.orderStatus).isEqualTo(OrderStatus.PAID.name());
        verify(paymentAttemptStateService, never()).markTerminalFailureInRequiresNew(
            any(), any(), any(), any(), any(), any());
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.IGNORED_DUPLICATE.name().equals(log.status)
                && "DUPLICATE".equals(log.failureCode)));
    }

    @Test
    void terminalFailedAttemptCannotBeSettledLaterWithSamePaymentNo() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = failedAttempt(order);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentRecordMapper.findByTransactionId("dev-PAY001")).thenReturn(null);

        assertThatThrownBy(() -> paymentService.simulateDevCallback("PAY001", DevPaymentScenario.SUCCESS))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("支付意图已终态");

        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "TERMINAL_ATTEMPT_REUSE".equals(log.failureCode)));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.HANDLE_CALLBACK.name().equals(log.operationType)
                && "FAILED".equals(log.status)
                && "TERMINAL_ATTEMPT_REUSE".equals(log.failureCode)));
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(paymentAttemptMapper, never()).updateById(argThat((PaymentAttempt updated) ->
            PaymentAttemptStatus.SUCCEEDED.name().equals(updated.status)));
        verify(orderMapper, never()).updateById(argThat((BizOrder updated) -> OrderStatus.PAID.name().equals(updated.status)));
    }

    @Test
    void detailRejectsAnotherUsersPaymentAttempt() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        attempt.userId = 99L;
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);

        assertThatThrownBy(() -> paymentService.detail("PAY001"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("支付意图不存在");
    }

    @Test
    void simulateDevCallbackRejectsAnotherUsersPaymentAttemptAndDoesNotSettle() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        attempt.userId = 99L;
        Reservation reservation = pendingPaymentReservation();
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt);
        lenient().when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        lenient().when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);

        Throwable thrown = catchThrowable(() -> paymentService.simulateDevCallback("PAY001", DevPaymentScenario.SUCCESS));

        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        verify(orderMapper, never()).updateById(any(BizOrder.class));
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        verify(funnelEventService, never()).recordMilestone(eq("PAYMENT_RESULT_RECORDED"), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any());
        assertThat(thrown)
            .isInstanceOf(BusinessException.class)
            .hasMessage("支付意图不存在");
    }

    @Test
    void providerCallbackRejectsMissingCallbackSecretAndDoesNotSettle() {
        PaymentService serviceWithoutSecret = newPaymentService("");
        PaymentCallbackRequest request = callbackRequest("TXN-PROVIDER-1", "100.00", "DEV_PAYMENT");
        request.sign = "anything";

        assertThatThrownBy(() -> serviceWithoutSecret.handleProviderCallback("DEV_PAYMENT", request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("支付回调未配置签名密钥");

        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(orderMapper, never()).updateById(any(BizOrder.class));
    }

    @Test
    void providerCallbackRejectsMissingRawPayloadAndDoesNotSettle() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        Reservation reservation = pendingPaymentReservation();
        PaymentCallbackRequest request = callbackRequest("TXN-PROVIDER-RAW-MISSING", "100.00", "DEV_PAYMENT");
        request.rawPayload = null;
        request.sign = sign(request, "callback-secret");
        lenient().when(orderMapper.findByOrderNoForUpdate(order.orderNo)).thenReturn(order);
        lenient().when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(attempt);
        lenient().when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);

        Throwable thrown = catchThrowable(() -> paymentService.handleProviderCallback("DEV_PAYMENT", request));

        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        verify(orderMapper, never()).updateById(any(BizOrder.class));
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        assertThat(thrown)
            .isInstanceOf(BusinessException.class)
            .hasMessage("支付回调原始报文不能为空");
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.RECEIVED.name().equals(log.status)
                && "TXN-PROVIDER-RAW-MISSING".equals(log.transactionId)));
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "TXN-PROVIDER-RAW-MISSING".equals(log.transactionId)));
    }

    @Test
    void providerCallbackRejectsBlankRawPayloadAndDoesNotSettle() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        Reservation reservation = pendingPaymentReservation();
        PaymentCallbackRequest request = callbackRequest("TXN-PROVIDER-RAW-BLANK", "100.00", "DEV_PAYMENT");
        request.rawPayload = "   ";
        request.sign = sign(request, "callback-secret");
        lenient().when(orderMapper.findByOrderNoForUpdate(order.orderNo)).thenReturn(order);
        lenient().when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(attempt);
        lenient().when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);

        Throwable thrown = catchThrowable(() -> paymentService.handleProviderCallback("DEV_PAYMENT", request));

        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        verify(orderMapper, never()).updateById(any(BizOrder.class));
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        assertThat(thrown)
            .isInstanceOf(BusinessException.class)
            .hasMessage("支付回调原始报文不能为空");
    }

    @Test
    void providerCallbackRejectsInvalidSignatureAndDoesNotSettle() {
        PaymentCallbackRequest request = callbackRequest("TXN-PROVIDER-1", "100.00", "DEV_PAYMENT");
        request.sign = "not-a-valid-signature";

        assertThatThrownBy(() -> paymentService.handleProviderCallback("DEV_PAYMENT", request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("支付回调签名错误");

        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(orderMapper, never()).updateById(any(BizOrder.class));
    }

    @Test
    void providerCallbackRejectsRequestChannelMismatchAndDoesNotSettle() {
        PaymentCallbackRequest request = callbackRequest("TXN-PROVIDER-1", "100.00", "OTHER_CHANNEL");
        request.sign = sign(request, "callback-secret");

        assertThatThrownBy(() -> paymentService.handleProviderCallback("DEV_PAYMENT", request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("支付渠道不一致");

        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(orderMapper, never()).updateById(any(BizOrder.class));
    }

    @Test
    void providerCallbackRejectsAmountMismatchAndDoesNotSettle() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        PaymentCallbackRequest request = callbackRequest("TXN-PROVIDER-1", "99.99", "DEV_PAYMENT");
        request.sign = sign(request, "callback-secret");
        when(orderMapper.findByOrderNoForUpdate(order.orderNo)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(attempt);

        assertThatThrownBy(() -> paymentService.handleProviderCallback("DEV_PAYMENT", request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("支付金额不一致");

        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(orderMapper, never()).updateById(argThat((BizOrder updated) -> OrderStatus.PAID.name().equals(updated.status)));
    }

    @Test
    void providerCallbackRejectsAmountThatDiffersFromPaymentAttemptSnapshotEvenWhenOrderAmountMatches() {
        BizOrder order = pendingOrder();
        order.payableAmount = new BigDecimal("100.00");
        PaymentAttempt attempt = pendingAttempt(order);
        attempt.payableAmount = new BigDecimal("90.00");
        Reservation reservation = pendingPaymentReservation();
        PaymentCallbackRequest request = callbackRequest("TXN-PROVIDER-SNAPSHOT-MISMATCH", "100.00", "DEV_PAYMENT");
        request.sign = sign(request, "callback-secret");
        when(orderMapper.findByOrderNoForUpdate(order.orderNo)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(attempt);
        lenient().when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);

        Throwable thrown = catchThrowable(() -> paymentService.handleProviderCallback("DEV_PAYMENT", request));

        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        verify(orderMapper, never()).updateById(argThat((BizOrder updated) -> OrderStatus.PAID.name().equals(updated.status)));
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        assertThat(thrown)
            .isInstanceOf(BusinessException.class)
            .hasMessage("支付金额不一致");
    }

    @Test
    void providerCallbackRejectsMissingPaymentAttemptSnapshotAmountEvenWhenOrderAmountMatches() {
        BizOrder order = pendingOrder();
        order.payableAmount = new BigDecimal("100.00");
        PaymentAttempt attempt = pendingAttempt(order);
        attempt.payableAmount = null;
        Reservation reservation = pendingPaymentReservation();
        PaymentCallbackRequest request = callbackRequest("TXN-PROVIDER-MISSING-SNAPSHOT", "100.00", "DEV_PAYMENT");
        request.sign = sign(request, "callback-secret");
        when(orderMapper.findByOrderNoForUpdate(order.orderNo)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(attempt);
        lenient().when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);

        Throwable thrown = catchThrowable(() -> paymentService.handleProviderCallback("DEV_PAYMENT", request));

        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        verify(orderMapper, never()).updateById(argThat((BizOrder updated) -> OrderStatus.PAID.name().equals(updated.status)));
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        assertThat(thrown)
            .isInstanceOf(BusinessException.class)
            .hasMessage("支付金额不一致");
    }

    @Test
    void providerCallbackRecordsReceivedAndFailedEvidenceForUnknownProvider() {
        // A26-P3-05：未知/停用 provider 的回调也必须先落 RECEIVED 证据，再落 FAILED 证据，
        // 不能因 requireByName 抛 404/409 而零证据落库。
        PaymentCallbackRequest request = callbackRequest("TXN-PROVIDER-BAD-PATH", "100.00", "DEV_PAYMENT");
        request.sign = sign(request, "callback-secret");

        Throwable thrown = catchThrowable(() -> paymentService.handleProviderCallback("ALI_PAY", request));

        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.RECEIVED.name().equals(log.status)
                && "ALI_PAY".equals(log.channel)
                && "TXN-PROVIDER-BAD-PATH".equals(log.transactionId)
                && "UNKNOWN".equals(log.providerEnv)
                && "ALI_PAY".equals(log.providerName)));
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "CALLBACK_PROCESS_FAILED".equals(log.failureCode)
                && "ALI_PAY".equals(log.channel)));
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        verify(orderMapper, never()).updateById(any(BizOrder.class));
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        assertThat(thrown)
            .isInstanceOf(BusinessException.class)
            .hasMessage("PAYMENT_PROVIDER_NOT_FOUND: 支付 Provider 不存在或未启用");
    }

    @Test
    void providerCallbackUsesAttemptProviderSnapshotForSettlementEvidence() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        attempt.providerEnv = "SANDBOX";
        attempt.providerName = "TEST_PAYMENT";
        attempt.channel = "TEST_PAYMENT";
        attempt.configVersion = "TEST_PAYMENT_V2";
        Reservation reservation = pendingPaymentReservation();
        PaymentProviderDescriptor descriptor = new PaymentProviderDescriptor(
            "payment.test", "SANDBOX", "TEST_PAYMENT", "Test Payment", "TEST_PAYMENT_V2", true);
        PaymentService routedService = newPaymentService("callback-secret", router(
            DevPaymentProvider.PROVIDER_KEY, new DevPaymentProvider(), testPaymentProvider(descriptor)));
        PaymentCallbackRequest request = callbackRequest("TXN-TEST-1", "100.00", "TEST_PAYMENT");
        request.sign = sign(request, "callback-secret");
        when(orderMapper.findByOrderNoForUpdate(order.orderNo)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(attempt);
        when(paymentRecordMapper.findByTransactionId("TXN-TEST-1")).thenReturn(null);
        when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);

        routedService.handleProviderCallback("TEST_PAYMENT", request);

        verify(paymentRecordMapper).insert(argThat((PaymentRecord record) ->
            "SANDBOX".equals(record.providerEnv)
                && "TEST_PAYMENT".equals(record.providerName)
                && "TEST_PAYMENT".equals(record.channel)
                && "TEST_PAYMENT_V2".equals(record.configVersion)));
        verify(orderMapper).updateById(argThat((BizOrder updated) -> "TEST_PAYMENT".equals(updated.payType)));
        // A26-P3-05：RECEIVED 证据在 requireByName 之前用 fallback descriptor 写入，
        // providerEnv/configVersion 为占位值（UNKNOWN），providerName 取请求传入的渠道名。
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.RECEIVED.name().equals(log.status)
                && "UNKNOWN".equals(log.providerEnv)
                && "TEST_PAYMENT".equals(log.providerName)
                && "UNKNOWN".equals(log.configVersion)));
        verify(callbackLogService).recordOperationInCurrentTransaction(argThat((PaymentProviderOperationLog log) ->
            "SANDBOX".equals(log.providerEnv)
                && "TEST_PAYMENT".equals(log.providerName)
                && "TEST_PAYMENT_V2".equals(log.configVersion)));
        verify(funnelEventService).recordMilestone(eq("PAYMENT_RESULT_RECORDED"), eq("PAYMENT"),
            eq(order.userId), eq(order.venueId), eq(order.reservationId), eq(order.id),
            eq(order.payableAmount), eq("SUCCESS"), isNull(), eq("payment-result-success-11-TXN-TEST-1"),
            argThat((Map<String, Object> metadata) -> "TEST_PAYMENT".equals(metadata.get("channel"))), any());
    }

    @Test
    void providerCallbackSuccessUsesCallbackTransactionAndRecordsSuccessSignals() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        Reservation reservation = pendingPaymentReservation();
        PaymentCallbackRequest request = callbackRequest("TXN-PROVIDER-1", "100.00", "DEV_PAYMENT");
        request.sign = sign(request, "callback-secret");
        when(orderMapper.findByOrderNoForUpdate(order.orderNo)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(attempt);
        when(paymentRecordMapper.findByTransactionId("TXN-PROVIDER-1")).thenReturn(null);
        when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);

        paymentService.handleProviderCallback("DEV_PAYMENT", request);

        verify(orderMapper).findByOrderNoForUpdate(order.orderNo);
        verify(orderMapper, never()).findByOrderNo(order.orderNo);
        verify(paymentRecordMapper).insert(argThat((PaymentRecord record) ->
            "TXN-PROVIDER-1".equals(record.transactionId)
                && "TXN-PROVIDER-1".equals(record.externalRef)
                && record.payAmount.compareTo(new BigDecimal("100.00")) == 0));
        verify(paymentAttemptMapper).updateById(argThat((PaymentAttempt updated) ->
            "TXN-PROVIDER-1".equals(updated.externalRef)
                && "SUCCEEDED".equals(updated.status)));
        verify(funnelEventService).recordMilestone(eq("PAYMENT_RESULT_RECORDED"), eq("PAYMENT"),
            eq(order.userId), eq(order.venueId), eq(order.reservationId), eq(order.id),
            eq(order.payableAmount), eq("SUCCESS"), isNull(), eq("payment-result-success-11-TXN-PROVIDER-1"),
            argThat((Map<String, Object> metadata) ->
                "DEV_PAYMENT".equals(metadata.get("channel")) && "PAY001".equals(metadata.get("paymentNo"))), any());
        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "PAYMENT_SUCCEEDED".equals(event.eventType())
                && "PAYMENT_SUCCEEDED:11".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && Map.of("orderNo", "ORD001").equals(event.templateModel())));
        InOrder orderedCallbacks = inOrder(callbackLogService);
        orderedCallbacks.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.RECEIVED.name().equals(log.status)
                && "TXN-PROVIDER-1".equals(log.transactionId)));
        orderedCallbacks.verify(callbackLogService).recordInCurrentTransaction(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.SUCCESS.name().equals(log.status)
                && "TXN-PROVIDER-1".equals(log.transactionId)));
        verify(callbackLogService).recordOperationInCurrentTransaction(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.HANDLE_CALLBACK.name().equals(log.operationType)
                && "SUCCESS".equals(log.status)
                && attempt.id.equals(log.paymentAttemptId)
                && order.id.equals(log.orderId)
                && "TXN-PROVIDER-1".equals(log.externalRef)));
    }

    @Test
    void providerCallbackInvokesProviderBeforeLockingOrder() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        Reservation reservation = pendingPaymentReservation();
        PaymentProvider provider = spy(new DevPaymentProvider());
        PaymentService routedService = newPaymentService("callback-secret", router(provider));
        PaymentCallbackRequest request = callbackRequest("TXN-PROVIDER-BOUNDARY", "100.00", "DEV_PAYMENT");
        request.sign = sign(request, "callback-secret");
        when(orderMapper.findByOrderNoForUpdate(order.orderNo)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(attempt);
        when(paymentRecordMapper.findByTransactionId("TXN-PROVIDER-BOUNDARY")).thenReturn(null);
        when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);

        routedService.handleProviderCallback("DEV_PAYMENT", request);

        InOrder callbackBoundary = inOrder(provider, orderMapper);
        callbackBoundary.verify(provider).handleCallback(any(PaymentCallbackCommand.class));
        callbackBoundary.verify(orderMapper).findByOrderNoForUpdate(order.orderNo);
    }

    @Test
    void providerCallbackWritesPayTimeInAsiaShanghaiWindowAlignedWithReconciliationCut() {
        // A26-P3-09：pay_time 必须用 Asia/Shanghai 时区写入，与 FinanceReconciliationPersistenceService
        // 按 Asia/Shanghai 切日的取数窗口对齐。无论 JVM 默认时区如何，payTime 都应落在 Asia/Shanghai
        // 当前时刻的合理窗口内（此处放宽到 ±120 秒以容纳测试执行耗时）。
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        Reservation reservation = pendingPaymentReservation();
        PaymentCallbackRequest request = callbackRequest("TXN-PROVIDER-TZ", "100.00", "DEV_PAYMENT");
        request.sign = sign(request, "callback-secret");
        when(orderMapper.findByOrderNoForUpdate(order.orderNo)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(attempt);
        when(paymentRecordMapper.findByTransactionId("TXN-PROVIDER-TZ")).thenReturn(null);
        when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);

        java.time.ZoneId shanghai = java.time.ZoneId.of("Asia/Shanghai");
        LocalDateTime before = LocalDateTime.now(shanghai).minusSeconds(120);
        paymentService.handleProviderCallback("DEV_PAYMENT", request);
        LocalDateTime after = LocalDateTime.now(shanghai).plusSeconds(120);

        ArgumentCaptor<PaymentRecord> recordCaptor = ArgumentCaptor.forClass(PaymentRecord.class);
        verify(paymentRecordMapper).insert(recordCaptor.capture());
        PaymentRecord payment = recordCaptor.getValue();
        assertThat(payment.payTime).isNotNull();
        assertThat(payment.payTime).isBetween(before, after);
        // createTime 与 payTime 一致（同为 Asia/Shanghai 写入）
        assertThat(payment.createTime).isEqualTo(payment.payTime);
    }

    @Test
    void providerCallbackSuccessOperationFailureDoesNotDurablyRecordSuccessCallbackEvidence() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        Reservation reservation = pendingPaymentReservation();
        PaymentCallbackRequest request = callbackRequest("TXN-PROVIDER-OP-FAIL", "100.00", "DEV_PAYMENT");
        request.sign = sign(request, "callback-secret");
        when(orderMapper.findByOrderNoForUpdate(order.orderNo)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(attempt);
        when(paymentRecordMapper.findByTransactionId("TXN-PROVIDER-OP-FAIL")).thenReturn(null);
        when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);
        doAnswer(invocation -> {
            PaymentProviderOperationLog log = invocation.getArgument(0);
            if (PaymentProviderOperationType.HANDLE_CALLBACK.name().equals(log.operationType)
                && "SUCCESS".equals(log.status)) {
                throw BusinessException.badRequest("成功操作日志写入失败");
            }
            return null;
        }).when(callbackLogService).recordOperationInCurrentTransaction(any(PaymentProviderOperationLog.class));

        assertThatThrownBy(() -> paymentService.handleProviderCallback("DEV_PAYMENT", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("成功操作日志写入失败");

        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.RECEIVED.name().equals(log.status)
                && "TXN-PROVIDER-OP-FAIL".equals(log.transactionId)));
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "SETTLEMENT_FAILED".equals(log.failureCode)
                && "TXN-PROVIDER-OP-FAIL".equals(log.transactionId)));
        verify(callbackLogService, never()).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.SUCCESS.name().equals(log.status)
                && "TXN-PROVIDER-OP-FAIL".equals(log.transactionId)));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.HANDLE_CALLBACK.name().equals(log.operationType)
                && "FAILED".equals(log.status)
                && "SETTLEMENT_FAILED".equals(log.failureCode)
                && "TXN-PROVIDER-OP-FAIL".equals(log.externalRef)));
    }

    @Test
    void providerCallbackSettlementFailureLeavesReceivedAndFailedEvidenceWithoutSuccessCallback() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = pendingAttempt(order);
        PaymentCallbackRequest request = callbackRequest("TXN-PROVIDER-FAIL", "100.00", "DEV_PAYMENT");
        request.sign = sign(request, "callback-secret");
        when(orderMapper.findByOrderNoForUpdate(order.orderNo)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(attempt);
        when(paymentRecordMapper.findByTransactionId("TXN-PROVIDER-FAIL")).thenReturn(null);
        when(paymentRecordMapper.insert(any(PaymentRecord.class)))
            .thenThrow(BusinessException.badRequest("支付记录写入失败"));

        assertThatThrownBy(() -> paymentService.handleProviderCallback("DEV_PAYMENT", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("支付记录写入失败");

        InOrder orderedCallbacks = inOrder(callbackLogService);
        orderedCallbacks.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.RECEIVED.name().equals(log.status)
                && "TXN-PROVIDER-FAIL".equals(log.transactionId)));
        orderedCallbacks.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "SETTLEMENT_FAILED".equals(log.failureCode)
                && "TXN-PROVIDER-FAIL".equals(log.transactionId)));
        verify(callbackLogService, never()).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.SUCCESS.name().equals(log.status)
                && "TXN-PROVIDER-FAIL".equals(log.transactionId)));
        verify(paymentAttemptMapper, never()).updateById(argThat((PaymentAttempt updated) ->
            PaymentAttemptStatus.SUCCEEDED.name().equals(updated.status)));
        verify(orderMapper, never()).updateById(argThat((BizOrder updated) -> OrderStatus.PAID.name().equals(updated.status)));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.HANDLE_CALLBACK.name().equals(log.operationType)
                && "FAILED".equals(log.status)
                && "SETTLEMENT_FAILED".equals(log.failureCode)
                && "TXN-PROVIDER-FAIL".equals(log.externalRef)));
    }

    @Test
    void paymentEntryExpiryReleasesOriginalCampaignBudget() {
        BizOrder order = pendingOrder();
        order.expireTime = LocalDateTime.now().minusMinutes(1);
        Reservation reservation = pendingPaymentReservation();
        reservation.promotionCampaignId = 91L;
        reservation.activityDiscountAmount = new BigDecimal("20.00");
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);

        assertThatThrownBy(() -> paymentService.createPaymentIntent(order.id))
            .isInstanceOf(BusinessException.class).hasMessage("订单已超时，请重新预约");

        verify(promotionPricingService).releaseCampaignBudgetForReservation(reservation);
        verify(couponService).releaseForReservation(order.reservationId, "支付超时");
        verify(paymentAttemptMapper, never()).insert(any(PaymentAttempt.class));
    }

    @Test
    void legacySimulatePayExpiresExpiredOrderBeforeThrowingConflict() {
        BizOrder order = pendingOrder();
        order.expireTime = LocalDateTime.now().minusMinutes(1);
        Reservation reservation = pendingPaymentReservation();
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);

        assertThatThrownBy(() -> paymentService.legacySimulatePay(order.id))
            .isInstanceOf(BusinessException.class)
            .hasMessage("订单已超时，请重新预约");

        verify(orderMapper).updateById(argThat((BizOrder updated) -> OrderStatus.TIMEOUT.name().equals(updated.status)));
        verify(reservationMapper).updateById(argThat((Reservation updated) -> ReservationStatus.EXPIRED.name().equals(updated.status)));
        verify(lifecycleService).freezeCredentialForReservation(order.reservationId, "支付超时");
        verify(occupancyMapper).releaseByReservationId(order.reservationId);
        verify(couponService).releaseForReservation(order.reservationId, "支付超时");
        verify(funnelEventService).recordMilestone(eq("PAYMENT_RESULT_RECORDED"), eq("PAYMENT"),
            eq(order.userId), eq(order.venueId), eq(order.reservationId), eq(order.id),
            eq(order.payableAmount), eq("TIMEOUT"), eq("PAYMENT_TIMEOUT"),
            eq("payment-result-timeout-" + order.id), any(), any());
        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "PAYMENT_TIMEOUT".equals(event.eventType())
                && "PAYMENT_TIMEOUT:11".equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && Map.of("orderNo", "ORD001").equals(event.templateModel())));
        verify(paymentAttemptMapper, never()).insert(any(PaymentAttempt.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRADE_SUCCESS", "TRADE_FINISHED"})
    void rawCallbackLocksOrderThenReloadsAttempt(String tradeStatus) {
        BizOrder order = pendingOrder();
        PaymentAttempt initialAttempt = sandboxPendingAttempt(order);
        PaymentAttempt lockedAttempt = sandboxPendingAttempt(order);
        Reservation reservation = pendingPaymentReservation();
        PaymentCallbackEnvelope envelope = rawCallbackEnvelope();
        PaymentProvider sandbox = verifiedSandboxProvider(verifiedCallback(tradeStatus, true, "100.00"));
        PaymentService rawService = rawCallbackService(sandbox);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(initialAttempt, lockedAttempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentRecordMapper.findByTransactionId("ALI-TRADE-001")).thenReturn(null);
        when(reservationMapper.selectById(order.reservationId)).thenReturn(reservation);

        rawService.handleRawProviderCallback("payment.alipay.sandbox", envelope);

        verify(orderMapper).selectByIdForUpdate(initialAttempt.orderId);
        verify(orderMapper, never()).selectById(initialAttempt.orderId);
        verify(paymentAttemptMapper, times(2)).findByPaymentNo("PAY001");
        verify(paymentRecordMapper).insert(argThat((PaymentRecord record) ->
            "ALI-TRADE-001".equals(record.transactionId)
                && "SANDBOX".equals(record.providerEnv)
                && "ALIPAY".equals(record.providerName)
                && new BigDecimal("100.00").compareTo(record.payAmount) == 0));
        verify(lifecycleService).issueCredentialForPaidReservation(any(), any());
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.RECEIVED.name().equals(log.status)
                && "SANDBOX".equals(log.providerEnv)
                && "ALIPAY".equals(log.providerName)
                && "ALIPAY_SANDBOX_V1".equals(log.configVersion)
                && !log.payload.contains("sign")
                && !log.payload.contains("app_id")));
        verify(callbackLogService).recordInCurrentTransaction(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.SUCCESS.name().equals(log.status)
                && "ALI-TRADE-001".equals(log.transactionId)));
        verify(callbackLogService).recordOperationInCurrentTransaction(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.HANDLE_CALLBACK.name().equals(log.operationType)
                && "SUCCESS".equals(log.status)
                && "ALI-TRADE-001".equals(log.externalRef)));
        InOrder orderedEvidence = inOrder(callbackLogService, paymentProviderEvidenceMirror);
        orderedEvidence.verify(callbackLogService).recordOperationInCurrentTransaction(any(PaymentProviderOperationLog.class));
        orderedEvidence.verify(paymentProviderEvidenceMirror).mirrorAfterCommit(eq(sandboxDescriptor(true)),
            argThat((PaymentProviderOperationLog log) -> "SUCCESS".equals(log.status)));
        assertThat(lockedAttempt.status).isEqualTo(PaymentAttemptStatus.SUCCEEDED.name());
        assertThat(order.status).isEqualTo(OrderStatus.PAID.name());
    }

    @Test
    void rawCallbackRejectsAttemptProviderSnapshotMismatch() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = sandboxPendingAttempt(order);
        attempt.configVersion = "ALIPAY_SANDBOX_V0";
        PaymentProvider sandbox = verifiedSandboxProvider(verifiedCallback("TRADE_SUCCESS", true, "100.00"));
        PaymentService rawService = rawCallbackService(sandbox);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt, attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);

        assertThatThrownBy(() -> rawService.handleRawProviderCallback("payment.alipay.sandbox", rawCallbackEnvelope()))
            .isInstanceOf(RuntimeException.class);

        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "PROVIDER_SNAPSHOT_MISMATCH".equals(log.failureCode)
                && noRawCallbackSecrets(log)));
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(lifecycleService, never()).issueCredentialForPaidReservation(any(), any());
    }

    @Test
    void rawCallbackRequiresAttemptAndOrderAmountsToMatch() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = sandboxPendingAttempt(order);
        attempt.payableAmount = new BigDecimal("99.99");
        PaymentProvider sandbox = verifiedSandboxProvider(verifiedCallback("TRADE_SUCCESS", true, "100.00"));
        PaymentService rawService = rawCallbackService(sandbox);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt, attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);

        assertThatThrownBy(() -> rawService.handleRawProviderCallback("payment.alipay.sandbox", rawCallbackEnvelope()))
            .isInstanceOf(RuntimeException.class);

        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.AMOUNT_MISMATCH.name().equals(log.status)
                && "AMOUNT_MISMATCH".equals(log.failureCode)
                && noRawCallbackSecrets(log)));
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(lifecycleService, never()).issueCredentialForPaidReservation(any(), any());
    }

    @Test
    void rawCallbackAcknowledgesVerifiedWaitBuyerPayWithoutSettlement() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = sandboxPendingAttempt(order);
        PaymentProvider sandbox = verifiedSandboxProvider(verifiedCallback("WAIT_BUYER_PAY", false, "100.00"));
        PaymentService rawService = rawCallbackService(sandbox);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt, attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);

        rawService.handleRawProviderCallback("payment.alipay.sandbox", rawCallbackEnvelope());

        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(lifecycleService, never()).issueCredentialForPaidReservation(any(), any());
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.SUCCESS.name().equals(log.status)
                && log.failureCode == null
                && log.responseSummaryJson.contains("WAIT_BUYER_PAY")
                && log.responseSummaryJson.contains("settleable")));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.HANDLE_CALLBACK.name().equals(log.operationType)
                && "ACKNOWLEDGED".equals(log.status)
                && log.failureCode == null));
        InOrder orderedEvidence = inOrder(callbackLogService, paymentProviderEvidenceMirror);
        orderedEvidence.verify(callbackLogService).recordOperation(any(PaymentProviderOperationLog.class));
        orderedEvidence.verify(paymentProviderEvidenceMirror).mirrorImmediately(eq(sandboxDescriptor(true)),
            argThat((PaymentProviderOperationLog log) -> "ACKNOWLEDGED".equals(log.status)));
        assertThat(attempt.status).isEqualTo(PaymentAttemptStatus.PENDING.name());
        assertThat(order.status).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
    }

    @Test
    void rawCallbackClosesVerifiedTradeClosedAttemptSoPaymentCanBeRetried() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = sandboxPendingAttempt(order);
        PaymentProvider sandbox = verifiedSandboxProvider(verifiedCallback("TRADE_CLOSED", false, "100.00"));
        PaymentService rawService = rawCallbackService(sandbox);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt, attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);

        rawService.handleRawProviderCallback("payment.alipay.sandbox", rawCallbackEnvelope());

        assertThat(attempt.status).isEqualTo(PaymentAttemptStatus.CLOSED.name());
        assertThat(attempt.failureCode).isEqualTo("TRADE_CLOSED");
        verify(paymentAttemptMapper).updateById(attempt);
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status) && "TRADE_CLOSED".equals(log.failureCode)));
    }

    @Test
    void rawCallbackAcknowledgesDuplicateTradeWithoutSecondSideEffects() {
        BizOrder order = paidOrder();
        PaymentAttempt attempt = sandboxPendingAttempt(order);
        attempt.status = PaymentAttemptStatus.SUCCEEDED.name();
        attempt.externalRef = "ALI-TRADE-001";
        PaymentProvider sandbox = verifiedSandboxProvider(verifiedCallback("TRADE_SUCCESS", true, "100.00"));
        PaymentService rawService = rawCallbackService(sandbox);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt, attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentRecordMapper.findByTransactionId("ALI-TRADE-001")).thenReturn(new PaymentRecord());

        rawService.handleRawProviderCallback("payment.alipay.sandbox", rawCallbackEnvelope());

        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.IGNORED_DUPLICATE.name().equals(log.status)
                && "DUPLICATE".equals(log.failureCode)));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.HANDLE_CALLBACK.name().equals(log.operationType)
                && "IGNORED".equals(log.status)
                && "DUPLICATE".equals(log.failureCode)));
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(memberService, never()).recordPaidOrder(any(), any());
        verify(lifecycleService, never()).issueCredentialForPaidReservation(any(), any());
    }

    @Test
    void verifiedLateRawSuccessIsAcknowledgedAndCreatesRefundDispositionWithoutReactivatingReservation() {
        BizOrder order = pendingOrder();
        order.status = OrderStatus.TIMEOUT.name();
        PaymentAttempt attempt = sandboxPendingAttempt(order);
        attempt.status = PaymentAttemptStatus.TIMEOUT.name();
        PaymentProvider sandbox = verifiedSandboxProvider(verifiedCallback("TRADE_SUCCESS", true, "100.00"));
        PaymentService rawService = rawCallbackService(sandbox);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt, attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentRecordMapper.findByTransactionId("ALI-TRADE-001")).thenReturn(null);
        when(refundRecordMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(numberGenerator.refundNo()).thenReturn("REF-LATE-001");

        rawService.handleRawProviderCallback("payment.alipay.sandbox", rawCallbackEnvelope());

        assertThat(order.status).isEqualTo(OrderStatus.REFUNDING.name());
        assertThat(attempt.status).isEqualTo(PaymentAttemptStatus.SUCCEEDED.name());
        verify(paymentRecordMapper).insert(argThat((PaymentRecord payment) ->
            "ALI-TRADE-001".equals(payment.transactionId)
                && "LATE_SUCCESS_REFUND_PENDING".equals(payment.failureCode)));
        verify(refundRecordMapper).insert(argThat((com.example.venue.order.entity.RefundRecord refund) ->
            "REF-LATE-001".equals(refund.refundNo)
                && RefundStatus.PENDING_REVIEW.name().equals(refund.status)
                && "LATE_SUCCESS_REFUND_PENDING".equals(refund.failureCode)));
        verify(lifecycleService, never()).issueCredentialForPaidReservation(any(), any());
        verify(memberService, never()).recordPaidOrder(any(), any());
        verify(couponService, never()).markUsedForOrder(any(), any(), any());
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.SUCCESS.name().equals(log.status)
                && "LATE_SUCCESS_REFUND_PENDING".equals(log.failureCode)));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            "ACKNOWLEDGED".equals(log.status)
                && "LATE_SUCCESS_REFUND_PENDING".equals(log.failureCode)));
    }

    @Test
    void handleProviderCallbackRescheduledLatePaymentTriggersRefundDisposition() {
        // A26-P0-03：改期重定价后旧 attempt 已被 syncPendingOrderForRescheduledReservation 标记为
        // CLOSED + SUPERSEDED_BY_RESCHEDULE。用户在旧支付页按旧金额扣款到达回调时，
        // findActiveByOrderId 返回 null，findLatestByOrderId 返回 CLOSED attempt，
        // 回调金额等于 attempt.payableAmount，走 settleRescheduledLateSuccessfulPayment：
        // 创建 refund_record PENDING_REVIEW + payment_record + 通知 RESCHEDULED_LATE_PAYMENT_REFUND_PENDING，
        // 订单保持 PENDING_PAYMENT，预约保持 PENDING_PAYMENT。
        BizOrder order = pendingOrder();
        order.status = OrderStatus.PENDING_PAYMENT.name();
        order.payableAmount = new BigDecimal("150.00"); // 改期后新金额
        PaymentAttempt attempt = pendingAttempt(order);
        attempt.status = PaymentAttemptStatus.CLOSED.name();
        attempt.failureCode = "SUPERSEDED_BY_RESCHEDULE";
        attempt.failureMessage = "改期重定价后旧支付意图失效，请重新发起支付";
        attempt.payableAmount = new BigDecimal("100.00"); // 旧金额，用户按此扣款
        PaymentCallbackRequest request = callbackRequest("TXN-RESCHEDULED-1", "100.00", "DEV_PAYMENT");
        request.sign = sign(request, "callback-secret");
        when(orderMapper.findByOrderNoForUpdate(order.orderNo)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(null);
        when(paymentAttemptMapper.findLatestByOrderId(order.id)).thenReturn(attempt);
        when(paymentRecordMapper.findByTransactionId("TXN-RESCHEDULED-1")).thenReturn(null);
        when(refundRecordMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(numberGenerator.refundNo()).thenReturn("REF-RESCHEDULED-001");

        paymentService.handleProviderCallback("DEV_PAYMENT", request);

        // 订单保持 PENDING_PAYMENT（不转 REFUNDING，用户用新金额重新支付）
        assertThat(order.status).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
        // attempt 被更新为 SUCCEEDED + LATE_SUCCESS_REFUND_PENDING
        assertThat(attempt.status).isEqualTo(PaymentAttemptStatus.SUCCEEDED.name());
        assertThat(attempt.failureCode).isEqualTo("LATE_SUCCESS_REFUND_PENDING");
        // payment_record 写入，带 LATE_SUCCESS_REFUND_PENDING 标记
        verify(paymentRecordMapper).insert(argThat((PaymentRecord payment) ->
            "TXN-RESCHEDULED-1".equals(payment.transactionId)
                && "LATE_SUCCESS_REFUND_PENDING".equals(payment.failureCode)));
        // refund_record 写入 PENDING_REVIEW + LATE_SUCCESS_REFUND_PENDING
        verify(refundRecordMapper).insert(argThat((com.example.venue.order.entity.RefundRecord refund) ->
            "REF-RESCHEDULED-001".equals(refund.refundNo)
                && RefundStatus.PENDING_REVIEW.name().equals(refund.status)
                && "LATE_SUCCESS_REFUND_PENDING".equals(refund.failureCode)
                && ("late-payment-refund-" + attempt.id).equals(refund.idempotencyKey)));
        // 通知事件 RESCHEDULED_LATE_PAYMENT_REFUND_PENDING
        verify(noticeService).createBusinessNotification(argThat((NotificationEvent event) ->
            "RESCHEDULED_LATE_PAYMENT_REFUND_PENDING".equals(event.eventType())
                && ("RESCHEDULED_LATE_PAYMENT_REFUND_PENDING:" + order.id).equals(event.eventKey())
                && Long.valueOf(7L).equals(event.recipientUserId())
                && event.recipientKind() == NotificationRecipientKind.USER
                && "REFUND".equals(event.businessType())
                && Long.valueOf(order.id).equals(event.businessId())));
        // 不签发凭证、不记录会员成长值、不核销券（与迟到支付一致）
        verify(lifecycleService, never()).issueCredentialForPaidReservation(any(), any());
        verify(memberService, never()).recordPaidOrder(any(), any());
        verify(couponService, never()).markUsedForOrder(any(), any(), any());
        // 操作日志 ACKNOWLEDGED + LATE_SUCCESS_REFUND_PENDING（非 SUCCESS 状态走 recordOperation）
        verify(callbackLogService).recordOperation(argThat(
            (PaymentProviderOperationLog log) ->
                "ACKNOWLEDGED".equals(log.status)
                    && "LATE_SUCCESS_REFUND_PENDING".equals(log.failureCode)
                    && "TXN-RESCHEDULED-1".equals(log.externalRef)));
        // 迟到回调日志 SUCCESS + LATE_SUCCESS_REFUND_PENDING（writeLateSuccessCallbackLog 用 recordInCurrentTransaction）
        verify(callbackLogService).recordInCurrentTransaction(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.SUCCESS.name().equals(log.status)
                && "LATE_SUCCESS_REFUND_PENDING".equals(log.failureCode)
                && "TXN-RESCHEDULED-1".equals(log.transactionId)));
    }

    @Test
    void handleProviderCallbackRescheduledLatePaymentAmountMismatchFallsBackToNotFound() {
        // A26-P0-03：回调金额不等于 attempt.payableAmount（用户按其他金额扣款），不匹配改期迟到分支，
        // 回落到 PAYMENT_ATTEMPT_NOT_FOUND。
        BizOrder order = pendingOrder();
        order.status = OrderStatus.PENDING_PAYMENT.name();
        order.payableAmount = new BigDecimal("150.00");
        PaymentAttempt attempt = pendingAttempt(order);
        attempt.status = PaymentAttemptStatus.CLOSED.name();
        attempt.failureCode = "SUPERSEDED_BY_RESCHEDULE";
        attempt.failureMessage = "改期重定价后旧支付意图失效，请重新发起支付";
        attempt.payableAmount = new BigDecimal("100.00");
        PaymentCallbackRequest request = callbackRequest("TXN-RESCHEDULED-MISMATCH", "50.00", "DEV_PAYMENT");
        request.sign = sign(request, "callback-secret");
        when(orderMapper.findByOrderNoForUpdate(order.orderNo)).thenReturn(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(null);
        when(paymentAttemptMapper.findLatestByOrderId(order.id)).thenReturn(attempt);

        Throwable thrown = catchThrowable(() -> paymentService.handleProviderCallback("DEV_PAYMENT", request));

        assertThat(thrown)
            .isInstanceOf(BusinessException.class)
            .hasMessage("支付意图不存在");
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(refundRecordMapper, never()).insert(any(com.example.venue.order.entity.RefundRecord.class));
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "PAYMENT_ATTEMPT_NOT_FOUND".equals(log.failureCode)));
    }

    @Test
    void rawCallbackAcknowledgesPersistedTradeBeforeOrderStateChanges() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = sandboxPendingAttempt(order);
        PaymentProvider sandbox = verifiedSandboxProvider(verifiedCallback("TRADE_SUCCESS", true, "100.00"));
        PaymentService rawService = rawCallbackService(sandbox);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt, attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentRecordMapper.findByTransactionId("ALI-TRADE-001")).thenReturn(new PaymentRecord());

        rawService.handleRawProviderCallback("payment.alipay.sandbox", rawCallbackEnvelope());

        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.IGNORED_DUPLICATE.name().equals(log.status)
                && "DUPLICATE".equals(log.failureCode)));
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(lifecycleService, never()).issueCredentialForPaidReservation(any(), any());
    }

    @Test
    void rawCallbackRejectsReloadedAttemptBoundToAnotherOrder() {
        BizOrder order = pendingOrder();
        PaymentAttempt initialAttempt = sandboxPendingAttempt(order);
        PaymentAttempt reloadedAttempt = sandboxPendingAttempt(order);
        reloadedAttempt.orderId = 12L;
        PaymentProvider sandbox = verifiedSandboxProvider(verifiedCallback("TRADE_SUCCESS", true, "100.00"));
        PaymentService rawService = rawCallbackService(sandbox);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(initialAttempt, reloadedAttempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);

        assertThatThrownBy(() -> rawService.handleRawProviderCallback("payment.alipay.sandbox", rawCallbackEnvelope()))
            .isInstanceOf(RuntimeException.class);

        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "PROVIDER_SNAPSHOT_MISMATCH".equals(log.failureCode)));
    }

    @Test
    void rawCallbackKeepsFixedEvidenceDescriptorWhenRoutedDescriptorIsInvalid() {
        PaymentProviderDescriptor invalidDescriptor = new PaymentProviderDescriptor(
            "payment.alipay.sandbox", "DEV", "ALIPAY", "Invalid Alipay", "ALIPAY_SANDBOX_V1", true);
        PaymentProvider invalidSandbox = mock(PaymentProvider.class);
        when(invalidSandbox.descriptor()).thenReturn(invalidDescriptor);
        PaymentService rawService = newPaymentService("callback-secret", router(
            DevPaymentProvider.PROVIDER_KEY, new DevPaymentProvider(), invalidSandbox));

        assertThatThrownBy(() -> rawService.handleRawProviderCallback("payment.alipay.sandbox", rawCallbackEnvelope()))
            .isInstanceOf(RuntimeException.class);

        InOrder orderedCallbacks = inOrder(callbackLogService);
        orderedCallbacks.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.RECEIVED.name().equals(log.status)
                && "SANDBOX".equals(log.providerEnv)
                && "ALIPAY".equals(log.providerName)
                && "ALIPAY_SANDBOX_V1".equals(log.configVersion)));
        orderedCallbacks.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "SANDBOX".equals(log.providerEnv)
                && "ALIPAY".equals(log.providerName)
                && "ALIPAY_SANDBOX_V1".equals(log.configVersion)
                && "PROVIDER_SNAPSHOT_MISMATCH".equals(log.failureCode)));
        verify(invalidSandbox, never()).verifyCallback(any(PaymentCallbackEnvelope.class));
    }

    @Test
    void rawCallbackFailsAndKeepsOnlyReceivedFailureEvidenceWhenSettlementRollsBack() {
        BizOrder order = pendingOrder();
        PaymentAttempt attempt = sandboxPendingAttempt(order);
        PaymentProvider sandbox = verifiedSandboxProvider(verifiedCallback("TRADE_SUCCESS", true, "100.00"));
        PaymentService rawService = rawCallbackService(sandbox);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(attempt, attempt);
        when(orderMapper.selectByIdForUpdate(order.id)).thenReturn(order);
        when(paymentRecordMapper.findByTransactionId("ALI-TRADE-001")).thenReturn(null);
        when(paymentRecordMapper.insert(any(PaymentRecord.class)))
            .thenThrow(BusinessException.badRequest("支付记录写入失败"));

        assertThatThrownBy(() -> rawService.handleRawProviderCallback("payment.alipay.sandbox", rawCallbackEnvelope()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("支付记录写入失败");

        InOrder orderedCallbacks = inOrder(callbackLogService);
        orderedCallbacks.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.RECEIVED.name().equals(log.status) && noRawCallbackSecrets(log)));
        orderedCallbacks.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "SETTLEMENT_FAILED".equals(log.failureCode)
                && noRawCallbackSecrets(log)));
        verify(callbackLogService, never()).recordInCurrentTransaction(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.SUCCESS.name().equals(log.status)));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.HANDLE_CALLBACK.name().equals(log.operationType)
                && "FAILED".equals(log.status)
                && "SETTLEMENT_FAILED".equals(log.failureCode)
                && noRawCallbackSecrets(log)));
        InOrder orderedEvidence = inOrder(callbackLogService, paymentProviderEvidenceMirror);
        orderedEvidence.verify(callbackLogService).recordOperation(any(PaymentProviderOperationLog.class));
        orderedEvidence.verify(paymentProviderEvidenceMirror).mirrorImmediately(eq(sandboxDescriptor(true)),
            argThat((PaymentProviderOperationLog log) -> "FAILED".equals(log.status)
                && "SETTLEMENT_FAILED".equals(log.failureCode)));
    }

    @Test
    void rawCallbackRecordsRejectedEnvelopeWithFixedEvidence() {
        paymentService.recordRejectedRawProviderCallback(
            "payment.alipay.sandbox", "ALIPAY_CALLBACK_ENVELOPE_INVALID");

        InOrder orderedCallbacks = inOrder(callbackLogService);
        orderedCallbacks.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.RECEIVED.name().equals(log.status)
                && "SANDBOX".equals(log.providerEnv)
                && "ALIPAY".equals(log.providerName)
                && "ALIPAY_SANDBOX_V1".equals(log.configVersion)
                && "ALIPAY_CALLBACK_ENVELOPE_INVALID".equals(log.failureCode)
                && noRawCallbackSecrets(log)));
        orderedCallbacks.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "ALIPAY_CALLBACK_ENVELOPE_INVALID".equals(log.failureCode)
                && noRawCallbackSecrets(log)));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.HANDLE_CALLBACK.name().equals(log.operationType)
                && "FAILED".equals(log.status)
                && "ALIPAY_CALLBACK_ENVELOPE_INVALID".equals(log.failureCode)
                && "SANDBOX".equals(log.providerEnv)
                && "ALIPAY".equals(log.providerName)
                && noRawCallbackSecrets(log)));
    }

    @Test
    void rawCallbackRecordsDisabledProviderWithReceivedThenFailedEvidence() {
        PaymentProvider dev = mock(PaymentProvider.class);
        when(dev.descriptor()).thenReturn(new DevPaymentProvider().descriptor());
        PaymentProvider disabledSandbox = testPaymentProvider(sandboxDescriptor(false));
        PaymentService disabledService = newPaymentService("callback-secret", router(
            DevPaymentProvider.PROVIDER_KEY, dev, disabledSandbox));

        assertThatThrownBy(() -> disabledService.handleRawProviderCallback(
            "payment.alipay.sandbox", rawCallbackEnvelope()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PAYMENT_PROVIDER_NOT_CONFIGURED");

        InOrder orderedCallbacks = inOrder(callbackLogService);
        orderedCallbacks.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.RECEIVED.name().equals(log.status)
                && "SANDBOX".equals(log.providerEnv)
                && noRawCallbackSecrets(log)));
        orderedCallbacks.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "PAYMENT_PROVIDER_NOT_CONFIGURED".equals(log.failureCode)
                && noRawCallbackSecrets(log)));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.HANDLE_CALLBACK.name().equals(log.operationType)
                && "FAILED".equals(log.status)
                && "PAYMENT_PROVIDER_NOT_CONFIGURED".equals(log.failureCode)
                && noRawCallbackSecrets(log)));
        verify(dev, never()).verifyCallback(any(PaymentCallbackEnvelope.class));
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(lifecycleService, never()).issueCredentialForPaidReservation(any(), any());
    }

    @Test
    void rawCallbackRecordsMissingProviderWithReceivedThenFailedEvidence() {
        PaymentProvider dev = mock(PaymentProvider.class);
        when(dev.descriptor()).thenReturn(new DevPaymentProvider().descriptor());
        PaymentService missingService = newPaymentService("callback-secret", router(
            DevPaymentProvider.PROVIDER_KEY, dev));

        Throwable thrown = catchThrowable(() -> missingService.handleRawProviderCallback(
            "payment.alipay.sandbox", rawCallbackEnvelope()));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getCode()).isEqualTo(ResultCode.CONFLICT);
        verify(callbackLogService, times(2)).record(any(PaymentCallbackLog.class));
        InOrder orderedCallbacks = inOrder(callbackLogService);
        orderedCallbacks.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.RECEIVED.name().equals(log.status)
                && "SANDBOX".equals(log.providerEnv)
                && "ALIPAY".equals(log.providerName)
                && "ALIPAY_SANDBOX_V1".equals(log.configVersion)
                && noRawCallbackSecrets(log)));
        orderedCallbacks.verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "SANDBOX".equals(log.providerEnv)
                && "ALIPAY".equals(log.providerName)
                && "ALIPAY_SANDBOX_V1".equals(log.configVersion)
                && "PAYMENT_PROVIDER_NOT_CONFIGURED".equals(log.failureCode)
                && noRawCallbackSecrets(log)));
        verify(callbackLogService, times(1)).recordOperation(argThat((PaymentProviderOperationLog log) ->
            PaymentProviderOperationType.HANDLE_CALLBACK.name().equals(log.operationType)
                && "FAILED".equals(log.status)
                && "SANDBOX".equals(log.providerEnv)
                && "ALIPAY".equals(log.providerName)
                && "ALIPAY_SANDBOX_V1".equals(log.configVersion)
                && "PAYMENT_PROVIDER_NOT_CONFIGURED".equals(log.failureCode)
                && noRawCallbackSecrets(log)));
        verify(dev, never()).verifyCallback(any(PaymentCallbackEnvelope.class));
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(lifecycleService, never()).issueCredentialForPaidReservation(any(), any());
    }

    @Test
    void rawCallbackCannotBeOverwrittenByDevSimulationAfterLock() {
        BizOrder staleOrder = pendingOrder();
        PaymentAttempt staleSandboxAttempt = sandboxPendingAttempt(staleOrder);
        BizOrder lockedPaidOrder = paidOrder();
        PaymentAttempt lockedDevAttempt = succeededAttempt(lockedPaidOrder);
        PaymentProvider sandbox = verifiedSandboxProvider(verifiedCallback("TRADE_SUCCESS", true, "100.00"));
        PaymentService rawService = rawCallbackService(sandbox);
        when(paymentAttemptMapper.findByPaymentNo("PAY001")).thenReturn(staleSandboxAttempt, lockedDevAttempt);
        when(orderMapper.selectByIdForUpdate(staleOrder.id)).thenReturn(lockedPaidOrder);

        assertThatThrownBy(() -> rawService.handleRawProviderCallback("payment.alipay.sandbox", rawCallbackEnvelope()))
            .isInstanceOf(RuntimeException.class);

        verify(orderMapper).selectByIdForUpdate(staleOrder.id);
        verify(paymentAttemptMapper, times(2)).findByPaymentNo("PAY001");
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        verify(callbackLogService).record(argThat((PaymentCallbackLog log) ->
            PaymentCallbackStatus.FAILED.name().equals(log.status)
                && "PROVIDER_SNAPSHOT_MISMATCH".equals(log.failureCode)));
    }

    private PaymentService newPaymentService(String paymentCallbackSecret) {
        return newPaymentService(paymentCallbackSecret, devRouter());
    }

    private PaymentService newPaymentService(String paymentCallbackSecret, PaymentProviderRouter paymentProviderRouter) {
        StateTransitionService stateTransitionService = new StateTransitionService(
            orderMapper, orderStatusLogMapper, reservationMapper, reservationStatusLogMapper, lifecycleService);
        return new PaymentService(orderMapper, orderStatusLogMapper, paymentRecordMapper, refundRecordMapper,
            callbackLogService, paymentAttemptMapper, paymentAttemptStateService, reservationMapper, occupancyMapper,
            reservationStatusLogMapper, noticeService, memberService, couponService, lifecycleService,
            funnelEventService, venueAccessService, numberGenerator, paymentProviderRouter,
            paymentProviderEvidenceMirror, stateTransitionService, new ObjectMapper().findAndRegisterModules(), paymentCallbackSecret);
    }

    private PaymentProviderRouter devRouter() {
        return router(new DevPaymentProvider());
    }

    private PaymentProviderRouter router(PaymentProvider provider) {
        return router(provider.descriptor().providerKey(), provider);
    }

    private PaymentProviderRouter router(String defaultProviderKey, PaymentProvider... providers) {
        PaymentProviderProperties properties = new PaymentProviderProperties();
        properties.setDefaultProviderKey(defaultProviderKey);
        return new PaymentProviderRouter(List.of(providers), properties);
    }

    private PaymentProvider testPaymentProvider(PaymentProviderDescriptor descriptor) {
        return new PaymentProvider() {
            @Override
            public PaymentProviderDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public PaymentCreateResult createPayment(PaymentCreateCommand command) {
                return new PaymentCreateResult(
                    descriptor.providerEnv(), descriptor.providerName(), descriptor.configVersion(), "PENDING",
                    command.amount(), new BigDecimal("1.00"), new BigDecimal("99.00"), command.currency(),
                    "{\"providerName\":\"" + descriptor.providerName() + "\",\"orderNo\":\"ORD001\"}",
                    "{\"providerName\":\"" + descriptor.providerName() + "\",\"status\":\"PENDING\"}",
                    new PaymentAction("SUBMIT_FORM", "POST", "https://gateway.example.test/pay", Map.of()));
            }

            @Override
            public PaymentCallbackResult handleCallback(PaymentCallbackCommand command) {
                BigDecimal feeAmount = new BigDecimal("1.00");
                return new PaymentCallbackResult(
                    "SUCCESS", command.externalRef(), command.amount(), feeAmount, command.amount().subtract(feeAmount),
                    "{\"providerName\":\"" + descriptor.providerName() + "\",\"externalRef\":\""
                        + command.externalRef() + "\"}",
                    "{\"providerName\":\"" + descriptor.providerName() + "\",\"status\":\"SUCCESS\"}");
            }

            @Override
            public List<ProviderStatementItem> statement(ProviderStatementQuery query) {
                return List.of();
            }
        };
    }

    private PaymentService rawCallbackService(PaymentProvider sandbox) {
        return newPaymentService("callback-secret", router(
            DevPaymentProvider.PROVIDER_KEY, new DevPaymentProvider(), sandbox));
    }

    private PaymentProvider verifiedSandboxProvider(VerifiedPaymentCallback callback) {
        PaymentProvider provider = mock(PaymentProvider.class);
        when(provider.descriptor()).thenReturn(sandboxDescriptor(true));
        when(provider.verifyCallback(any(PaymentCallbackEnvelope.class))).thenReturn(callback);
        return provider;
    }

    private PaymentCallbackEnvelope rawCallbackEnvelope() {
        return new PaymentCallbackEnvelope(
            "application/x-www-form-urlencoded; charset=UTF-8",
            "UTF-8",
            Map.of(
                "sign", List.of("unpersisted-signature"),
                "app_id", List.of("unpersisted-app-id"),
                "seller_id", List.of("unpersisted-seller-id"),
                "out_trade_no", List.of("PAY001"),
                "trade_no", List.of("ALI-TRADE-001"),
                "trade_status", List.of("TRADE_SUCCESS"),
                "total_amount", List.of("100.00")
            )
        );
    }

    private VerifiedPaymentCallback verifiedCallback(String tradeStatus, boolean settleable, String amount) {
        return new VerifiedPaymentCallback(
            "PAY001",
            "ALI-TRADE-001",
            tradeStatus,
            settleable,
            new BigDecimal(amount),
            "CNY",
            "{\"paymentNo\":\"PAY001\",\"tradeSuffix\":\"***0001\",\"amount\":\"" + amount
                + "\",\"status\":\"" + tradeStatus + "\"}",
            "{\"merchantQueryMatched\":true,\"statusAccepted\":true,\"settleable\":" + settleable + "}"
        );
    }

    private boolean noRawCallbackSecrets(PaymentCallbackLog log) {
        return log.payload != null
            && !log.payload.contains("sign")
            && !log.payload.contains("app_id")
            && !log.payload.contains("seller_id")
            && !log.payload.contains("unpersisted")
            && (log.requestSummaryJson == null || (!log.requestSummaryJson.contains("sign")
                && !log.requestSummaryJson.contains("app_id")
                && !log.requestSummaryJson.contains("seller_id")
                && !log.requestSummaryJson.contains("unpersisted")))
            && (log.responseSummaryJson == null || (!log.responseSummaryJson.contains("sign")
                && !log.responseSummaryJson.contains("app_id")
                && !log.responseSummaryJson.contains("seller_id")
                && !log.responseSummaryJson.contains("unpersisted")));
    }

    private boolean noRawCallbackSecrets(PaymentProviderOperationLog log) {
        return (log.requestSummaryJson == null || (!log.requestSummaryJson.contains("sign")
                && !log.requestSummaryJson.contains("app_id")
                && !log.requestSummaryJson.contains("seller_id")
                && !log.requestSummaryJson.contains("unpersisted")))
            && (log.responseSummaryJson == null || (!log.responseSummaryJson.contains("sign")
                && !log.responseSummaryJson.contains("app_id")
                && !log.responseSummaryJson.contains("seller_id")
                && !log.responseSummaryJson.contains("unpersisted")))
            && (log.failureMessage == null || (!log.failureMessage.contains("sign")
                && !log.failureMessage.contains("app_id")
                && !log.failureMessage.contains("seller_id")
                && !log.failureMessage.contains("unpersisted")));
    }

    private PaymentProviderDescriptor sandboxDescriptor(boolean enabled) {
        return new PaymentProviderDescriptor(
            "payment.alipay.sandbox", "SANDBOX", "ALIPAY", "Alipay Sandbox", "ALIPAY_SANDBOX_V1", enabled);
    }

    private PaymentAction sandboxAction() {
        return new PaymentAction(
            "SUBMIT_FORM",
            "POST",
            "https://openapi-sandbox.dl.alipaydev.com/gateway.do?opaque=action-token",
            Map.of("biz_content", "sensitive-hidden-value")
        );
    }

    private PaymentCreateResult sandboxCreateResult(PaymentAction action) {
        return new PaymentCreateResult(
            "SANDBOX",
            "ALIPAY",
            "ALIPAY_SANDBOX_V1",
            PaymentAttemptStatus.PENDING.name(),
            new BigDecimal("100.00"),
            new BigDecimal("0.00"),
            new BigDecimal("100.00"),
            "CNY",
            "{\"providerName\":\"ALIPAY\",\"operation\":\"PAGE_PAY\"}",
            "{\"providerName\":\"ALIPAY\",\"status\":\"PENDING\"}",
            action
        );
    }

    private PaymentAttempt sandboxPendingAttempt(BizOrder order) {
        PaymentAttempt attempt = pendingAttempt(order);
        attempt.providerEnv = "SANDBOX";
        attempt.providerName = "ALIPAY";
        attempt.channel = "ALIPAY";
        attempt.configVersion = "ALIPAY_SANDBOX_V1";
        attempt.requestSummaryJson = "{\"providerName\":\"ALIPAY\",\"operation\":\"PAGE_PAY\"}";
        attempt.responseSummaryJson = "{\"providerName\":\"ALIPAY\",\"status\":\"PENDING\"}";
        return attempt;
    }

    private void assertUnavailableActiveAttemptOperation(PaymentProviderOperationLog operation,
                                                          PaymentAttempt attempt, BizOrder order) {
        assertThat(operation.operationType).isEqualTo(PaymentProviderOperationType.CREATE_PAYMENT.name());
        assertThat(operation.providerEnv).isEqualTo(attempt.providerEnv);
        assertThat(operation.providerName).isEqualTo(attempt.providerName);
        assertThat(operation.paymentAttemptId).isEqualTo(attempt.id);
        assertThat(operation.orderId).isEqualTo(order.id);
        assertThat(operation.externalRef).isNull();
        assertThat(operation.idempotencyKey).isEqualTo(attempt.idempotencyKey);
        assertThat(operation.status).isEqualTo("FAILED");
        assertThat(operation.failureCode).isEqualTo("PAYMENT_PROVIDER_NOT_FOUND");
        assertThat(operation.failureMessage).isEqualTo("支付创建失败");
        assertThat(operation.configVersion).isEqualTo(attempt.configVersion);
        assertThat(operation.requestSummaryJson).isEqualTo(
            "{\"providerName\":\"ALIPAY\",\"operation\":\"CREATE_PAYMENT\",\"paymentNo\":\"PAY001\","
                + "\"orderNo\":\"ORD001\",\"amount\":\"100.00\",\"currency\":\"CNY\"}");
        assertThat(operation.responseSummaryJson).isEqualTo(
            "{\"providerName\":\"ALIPAY\",\"status\":\"FAILED\",\"failureCode\":\"PAYMENT_PROVIDER_NOT_FOUND\"}");
        assertThat(operation.metadataJson).isEqualTo(
            "{\"providerEnv\":\"SANDBOX\",\"providerName\":\"ALIPAY\",\"paymentNo\":\"PAY001\","
                + "\"orderNo\":\"ORD001\"}");
    }

    private BizOrder pendingOrder() {
        BizOrder order = new BizOrder();
        order.id = 11L;
        order.orderNo = "ORD001";
        order.reservationId = 99L;
        order.userId = 7L;
        order.venueId = 20L;
        order.totalAmount = new BigDecimal("100.00");
        order.originalAmount = new BigDecimal("100.00");
        order.memberDiscountAmount = new BigDecimal("0.00");
        order.couponDiscountAmount = new BigDecimal("20.00");
        order.activityDiscountAmount = new BigDecimal("0.00");
        order.payableAmount = new BigDecimal("100.00");
        order.paidAmount = new BigDecimal("0.00");
        order.feeAmount = new BigDecimal("0.00");
        order.netAmount = new BigDecimal("0.00");
        order.userCouponId = 70L;
        order.status = OrderStatus.PENDING_PAYMENT.name();
        order.expireTime = LocalDateTime.now().plusMinutes(15);
        order.updateTime = LocalDateTime.of(2026, 7, 4, 10, 30);
        return order;
    }

    private BizOrder paidOrder() {
        BizOrder order = pendingOrder();
        order.status = OrderStatus.PAID.name();
        order.paidAmount = new BigDecimal("100.00");
        order.feeAmount = new BigDecimal("0.60");
        order.netAmount = new BigDecimal("99.40");
        order.payTime = LocalDateTime.of(2026, 7, 4, 10, 40);
        return order;
    }

    private PaymentAttempt pendingAttempt(BizOrder order) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.id = 51L;
        attempt.paymentNo = "PAY001";
        attempt.orderId = order.id;
        attempt.orderNo = order.orderNo;
        attempt.userId = order.userId;
        attempt.venueId = order.venueId;
        attempt.reservationId = order.reservationId;
        attempt.providerEnv = "DEV";
        attempt.providerName = "DEV_PAYMENT";
        attempt.channel = "DEV_PAYMENT";
        attempt.status = "PENDING";
        attempt.payableAmount = order.payableAmount;
        attempt.paidAmount = new BigDecimal("0.00");
        attempt.feeAmount = new BigDecimal("0.60");
        attempt.netAmount = new BigDecimal("99.40");
        attempt.currency = "CNY";
        attempt.idempotencyKey = "payment-intent-11";
        attempt.configVersion = "DEV_PAYMENT_V1";
        return attempt;
    }

    private PaymentAttempt failedAttempt(BizOrder order) {
        PaymentAttempt attempt = pendingAttempt(order);
        attempt.status = PaymentAttemptStatus.FAILED.name();
        attempt.externalRef = "dev-PAY001";
        attempt.failureCode = "PROVIDER_FAILED";
        attempt.failureMessage = "支付渠道返回失败";
        attempt.feeAmount = new BigDecimal("0.00");
        attempt.netAmount = new BigDecimal("0.00");
        return attempt;
    }

    private PaymentAttempt succeededAttempt(BizOrder order) {
        PaymentAttempt attempt = pendingAttempt(order);
        attempt.status = "SUCCEEDED";
        attempt.externalRef = "dev-PAY001";
        attempt.paidAmount = new BigDecimal("100.00");
        attempt.feeAmount = new BigDecimal("0.60");
        attempt.netAmount = new BigDecimal("99.40");
        attempt.paidTime = LocalDateTime.of(2026, 7, 4, 10, 40);
        return attempt;
    }

    private Reservation pendingPaymentReservation() {
        Reservation reservation = new Reservation();
        reservation.id = 99L;
        reservation.userId = 7L;
        reservation.venueId = 20L;
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        reservation.deleted = 0;
        return reservation;
    }

    private PaymentCallbackRequest callbackRequest(String transactionId, String amount, String channel) {
        PaymentCallbackRequest request = new PaymentCallbackRequest();
        request.orderNo = "ORD001";
        request.transactionId = transactionId;
        request.amount = new BigDecimal(amount);
        request.channel = channel;
        request.rawPayload = "{\"orderNo\":\"ORD001\",\"transactionId\":\"" + transactionId
            + "\",\"amount\":\"" + amount + "\",\"channel\":\"" + channel + "\"}";
        return request;
    }

    private String sign(PaymentCallbackRequest request, String secret) {
        return hmacSha256(callbackSignPayload(request), secret);
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
            throw new IllegalStateException(ex);
        }
    }
}
