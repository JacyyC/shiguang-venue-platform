package com.example.venue.payment.service;

import com.example.venue.payment.dto.PaymentAttemptStatus;
import com.example.venue.payment.dto.PaymentCloseCommand;
import com.example.venue.payment.dto.PaymentCloseResult;
import com.example.venue.payment.dto.PaymentProviderOperationType;
import com.example.venue.payment.entity.PaymentAttempt;
import com.example.venue.payment.entity.PaymentProviderOperationLog;
import com.example.venue.payment.mapper.PaymentAttemptMapper;
import com.example.venue.payment.provider.PaymentProvider;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import com.example.venue.payment.provider.PaymentProviderEvidenceMirror;
import com.example.venue.payment.provider.PaymentProviderRouter;
import com.example.venue.order.entity.BizOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Terminates local attempts in the order transaction, then closes the provider trade only after commit.
 */
@Service
public class PaymentAttemptExpirationService {
    private static final String PAYMENT_TIMEOUT = "PAYMENT_TIMEOUT";
    private static final String PAYMENT_CLOSE_FAILED = "PAYMENT_CLOSE_FAILED";

    private final PaymentAttemptMapper paymentAttemptMapper;
    private final PaymentProviderRouter paymentProviderRouter;
    private final PaymentCallbackLogService callbackLogService;
    private final PaymentProviderEvidenceMirror paymentProviderEvidenceMirror;
    private final ObjectMapper objectMapper;

    public PaymentAttemptExpirationService(PaymentAttemptMapper paymentAttemptMapper,
                                           PaymentProviderRouter paymentProviderRouter,
                                           PaymentCallbackLogService callbackLogService,
                                           PaymentProviderEvidenceMirror paymentProviderEvidenceMirror,
                                           ObjectMapper objectMapper) {
        this.paymentAttemptMapper = paymentAttemptMapper;
        this.paymentProviderRouter = paymentProviderRouter;
        this.callbackLogService = callbackLogService;
        this.paymentProviderEvidenceMirror = paymentProviderEvidenceMirror;
        this.objectMapper = objectMapper;
    }

    public void expireActiveAttemptAfterOrderCommit(BizOrder order) {
        if (order == null || order.id == null) {
            return;
        }
        PaymentAttempt attempt = paymentAttemptMapper.findActiveByOrderId(order.id);
        if (attempt == null) {
            return;
        }
        attempt.status = PaymentAttemptStatus.TIMEOUT.name();
        attempt.failureCode = PAYMENT_TIMEOUT;
        attempt.failureMessage = "订单支付超时，预约已释放";
        attempt.paidAmount = com.example.venue.common.MoneyUtils.ZERO_AMOUNT;
        attempt.feeAmount = com.example.venue.common.MoneyUtils.ZERO_AMOUNT;
        attempt.netAmount = com.example.venue.common.MoneyUtils.ZERO_AMOUNT;
        attempt.responseSummaryJson = timeoutSummary(attempt);
        attempt.updateTime = LocalDateTime.now();
        paymentAttemptMapper.updateById(attempt);

        CloseSnapshot snapshot = CloseSnapshot.from(attempt);
        if (TransactionSynchronizationManager.isSynchronizationActive()
            && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    requestProviderClose(snapshot);
                }
            });
            return;
        }
        requestProviderClose(snapshot);
    }

    public void requestProviderClose(CloseSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        PaymentAttempt current = paymentAttemptMapper.findByPaymentNo(snapshot.paymentNo());
        if (current == null || !PaymentAttemptStatus.TIMEOUT.name().equals(current.status)) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        PaymentProviderDescriptor descriptor = new PaymentProviderDescriptor(
            null, snapshot.providerEnv(), snapshot.providerName(), snapshot.providerName(), snapshot.configVersion(), false);
        PaymentCloseCommand command = new PaymentCloseCommand(
            snapshot.paymentNo(), snapshot.orderNo(), "payment-close-" + snapshot.attemptId());
        String requestSummary = closeRequestSummary(command, snapshot.providerName());
        try {
            PaymentProvider provider = paymentProviderRouter.require(snapshot.providerEnv(), snapshot.providerName());
            descriptor = provider.descriptor();
            PaymentCloseResult result = provider.closePayment(command);
            String responseSummary = result == null || result.responseSummaryJson() == null
                ? closeResponseSummary(snapshot.providerName(), "CLOSED", null)
                : result.responseSummaryJson();
            recordCloseOperation(descriptor, snapshot, result == null ? null : result.externalRef(), requestSummary,
                responseSummary, "SUCCESS", null, null, startedAt);
        } catch (RuntimeException ex) {
            recordCloseOperation(descriptor, snapshot, null, requestSummary,
                closeResponseSummary(snapshot.providerName(), "FAILED", PAYMENT_CLOSE_FAILED), "FAILED",
                PAYMENT_CLOSE_FAILED, "支付关闭请求失败", startedAt);
        }
    }

    private void recordCloseOperation(PaymentProviderDescriptor descriptor, CloseSnapshot snapshot, String externalRef,
                                      String requestSummary, String responseSummary, String status, String failureCode,
                                      String failureMessage, long startedAt) {
        PaymentProviderOperationLog log = new PaymentProviderOperationLog();
        log.operationNo = "POP-" + UUID.randomUUID();
        log.operationType = PaymentProviderOperationType.CLOSE_PAYMENT.name();
        log.providerEnv = snapshot.providerEnv();
        log.providerName = snapshot.providerName();
        log.paymentAttemptId = snapshot.attemptId();
        log.orderId = snapshot.orderId();
        log.externalRef = externalRef;
        log.idempotencyKey = "payment-close-" + snapshot.attemptId();
        log.status = status;
        log.requestSummaryJson = requestSummary;
        log.responseSummaryJson = responseSummary;
        log.failureCode = failureCode;
        log.failureMessage = failureMessage;
        log.configVersion = snapshot.configVersion();
        log.durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
        log.metadataJson = "{\"paymentNo\":\"" + snapshot.paymentNo() + "\",\"orderNo\":\""
            + snapshot.orderNo() + "\"}";
        log.createTime = LocalDateTime.now();
        callbackLogService.recordOperation(log);
        if (isExactAlipaySandboxDescriptor(descriptor)) {
            paymentProviderEvidenceMirror.mirrorImmediately(descriptor, log);
        }
    }

    private String timeoutSummary(PaymentAttempt attempt) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("providerName", attempt.providerName);
        node.put("status", PaymentAttemptStatus.TIMEOUT.name());
        node.put("failureCode", PAYMENT_TIMEOUT);
        return node.toString();
    }

    private String closeRequestSummary(PaymentCloseCommand command, String providerName) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("providerName", providerName);
        node.put("operation", PaymentProviderOperationType.CLOSE_PAYMENT.name());
        node.put("paymentNo", command.paymentNo());
        node.put("orderNo", command.orderNo());
        return node.toString();
    }

    private String closeResponseSummary(String providerName, String status, String failureCode) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("providerName", providerName);
        node.put("status", status);
        if (failureCode != null) {
            node.put("failureCode", failureCode);
        }
        return node.toString();
    }

    private boolean isExactAlipaySandboxDescriptor(PaymentProviderDescriptor descriptor) {
        return descriptor != null
            && "payment.alipay.sandbox".equals(descriptor.providerKey())
            && "SANDBOX".equals(descriptor.providerEnv())
            && "ALIPAY".equals(descriptor.providerName())
            && "ALIPAY_SANDBOX_V1".equals(descriptor.configVersion());
    }

    public record CloseSnapshot(Long attemptId, Long orderId, String paymentNo, String orderNo,
                                String providerEnv, String providerName, String configVersion) {
        static CloseSnapshot from(PaymentAttempt attempt) {
            return new CloseSnapshot(attempt.id, attempt.orderId, attempt.paymentNo, attempt.orderNo,
                attempt.providerEnv, attempt.providerName, attempt.configVersion);
        }
    }
}
