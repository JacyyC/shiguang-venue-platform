package com.example.venue.payment.provider;

import com.example.venue.payment.entity.PaymentProviderOperationLog;
import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.service.ProviderObservabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

@Service
public class PaymentProviderEvidenceMirror {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentProviderEvidenceMirror.class);
    private static final String ALIPAY_SANDBOX_PROVIDER_KEY = "payment.alipay.sandbox";
    private static final String ALIPAY_SANDBOX_PROVIDER_ENV = "SANDBOX";
    private static final String ALIPAY_SANDBOX_PROVIDER_NAME = "ALIPAY";
    private static final String ALIPAY_SANDBOX_CONFIG_VERSION = "ALIPAY_SANDBOX_V1";
    private static final String EXTERNAL_TIMEOUT_CODE = "ALIPAY_EXTERNAL_TIMEOUT";
    private static final Set<String> ALLOWED_OPERATION_TYPES = Set.of(
        "CREATE_PAYMENT", "QUERY_PAYMENT", "CLOSE_PAYMENT", "HANDLE_CALLBACK", "REQUEST_REFUND",
        "QUERY_REFUND", "STATEMENT");
    private static final Set<String> EXTERNAL_TIMEOUT_OPERATION_TYPES = Set.of(
        "QUERY_PAYMENT", "QUERY_REFUND", "STATEMENT");

    private final ProviderObservabilityService observabilityService;
    private final TransactionTemplate requiresNew;

    public PaymentProviderEvidenceMirror(ProviderObservabilityService observabilityService,
                                         PlatformTransactionManager transactionManager) {
        this.observabilityService = observabilityService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void mirrorAfterCommit(PaymentProviderDescriptor descriptor, PaymentProviderOperationLog operationLog) {
        Projection projection = projection(descriptor, operationLog);
        if (projection == null) {
            return;
        }
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        persist(projection);
                    }
                });
                return;
            }
            persist(projection);
        } catch (RuntimeException ex) {
            safeWarning();
        }
    }

    public void mirrorImmediately(PaymentProviderDescriptor descriptor, PaymentProviderOperationLog operationLog) {
        Projection projection = projection(descriptor, operationLog);
        if (projection != null) {
            persist(projection);
        }
    }

    private void persist(Projection projection) {
        try {
            requiresNew.executeWithoutResult(status -> observabilityService.recordCall(projection.toCallRecord()));
        } catch (RuntimeException ex) {
            safeWarning();
        }
    }

    private Projection projection(PaymentProviderDescriptor descriptor, PaymentProviderOperationLog operationLog) {
        if (!isExactSandboxDescriptor(descriptor) || !isExactSandboxLedger(operationLog)) {
            return null;
        }
        String operationType = safeOperationType(operationLog.operationType);
        Outcome outcome = normalizeOutcome(operationLog.status, operationType, operationLog.failureCode, descriptor.enabled());
        long durationMs = operationLog.durationMs == null ? 0L : Math.max(0L, operationLog.durationMs);
        return new Projection(
            nonBlank(operationLog.idempotencyKey),
            operationType,
            outcome,
            durationMs,
            maskedSuffix(operationLog.externalRef),
            operationLog.id);
    }

    private boolean isExactSandboxDescriptor(PaymentProviderDescriptor descriptor) {
        return descriptor != null
            && ALIPAY_SANDBOX_PROVIDER_KEY.equals(descriptor.providerKey())
            && ALIPAY_SANDBOX_PROVIDER_ENV.equals(descriptor.providerEnv())
            && ALIPAY_SANDBOX_PROVIDER_NAME.equals(descriptor.providerName())
            && ALIPAY_SANDBOX_CONFIG_VERSION.equals(descriptor.configVersion());
    }

    private boolean isExactSandboxLedger(PaymentProviderOperationLog operationLog) {
        return operationLog != null
            && ALIPAY_SANDBOX_PROVIDER_ENV.equals(operationLog.providerEnv)
            && ALIPAY_SANDBOX_PROVIDER_NAME.equals(operationLog.providerName)
            && ALIPAY_SANDBOX_CONFIG_VERSION.equals(operationLog.configVersion);
    }

    private Outcome normalizeOutcome(String ledgerStatus, String operationType, String failureCode, boolean enabled) {
        String status = upper(ledgerStatus);
        if ("SUCCESS".equals(status)) {
            return new Outcome("SUCCESS", null, true);
        }
        if (enabled && "FAILED".equals(status)
            && "HANDLE_CALLBACK".equals(operationType)
            && "ALIPAY_TRADE_QUERY_FAILED".equals(failureCode)) {
            return new Outcome("FAILED", "ALIPAY_TRADE_QUERY_FAILED", true);
        }
        if (enabled && "FAILED".equals(status)
            && "STATEMENT".equals(operationType)
            && "ALIPAY_BILL_NOT_READY".equals(failureCode)) {
            return new Outcome("FAILED", "ALIPAY_BILL_NOT_READY", true);
        }
        if (enabled && "TIMEOUT".equals(status)
            && EXTERNAL_TIMEOUT_OPERATION_TYPES.contains(operationType)) {
            return new Outcome("TIMEOUT", EXTERNAL_TIMEOUT_CODE, true);
        }
        return new Outcome("SKIPPED", null, false);
    }

    private String safeOperationType(String operationType) {
        String normalized = upper(operationType);
        return ALLOWED_OPERATION_TYPES.contains(normalized) ? normalized : "UNKNOWN";
    }

    private String maskedSuffix(String externalRef) {
        String value = nonBlank(externalRef);
        if (value == null) {
            return null;
        }
        if (value.length() <= 4) {
            return null;
        }
        int suffixStart = Math.max(0, value.length() - 4);
        return "***" + value.substring(suffixStart);
    }

    private String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private void safeWarning() {
        LOGGER.warn("Payment provider evidence mirror persistence failed");
    }

    private record Projection(String idempotencyKey,
                              String operationType,
                              Outcome outcome,
                              long durationMs,
                              String externalRef,
                              Long operationId) {
        private ProviderCallRecord toCallRecord() {
            String requestSummary = "{\"operationType\":\"" + operationType
                + "\",\"mirrorStatus\":\"" + outcome.status() + "\"}";
            String responseSummary = "{\"durationMs\":" + durationMs
                + ",\"configVersion\":\"ALIPAY_SANDBOX_V1\"}";
            return ProviderCallRecord.of(
                ProviderRunContext.system(),
                ALIPAY_SANDBOX_PROVIDER_KEY,
                "PAYMENT",
                ALIPAY_SANDBOX_PROVIDER_ENV,
                ALIPAY_SANDBOX_PROVIDER_NAME,
                idempotencyKey,
                operationType,
                outcome.status(),
                ALIPAY_SANDBOX_CONFIG_VERSION,
                requestSummary,
                responseSummary,
                outcome.failureCode(),
                null,
                durationMs,
                externalRef,
                "PAYMENT_OPERATION",
                operationId,
                BigDecimal.ONE,
                outcome.updateRegistry());
        }
    }

    private record Outcome(String status, String failureCode, boolean updateRegistry) {
    }
}
