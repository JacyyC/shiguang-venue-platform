package com.example.venue.payment;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.venue.payment.entity.PaymentProviderOperationLog;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import com.example.venue.payment.provider.PaymentProviderEvidenceMirror;
import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.service.ProviderObservabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentProviderEvidenceMirrorTest {
    @Mock
    private ProviderObservabilityService observabilityService;

    private RecordingTransactionManager transactionManager;
    private PaymentProviderEvidenceMirror mirror;

    @BeforeEach
    void setUp() {
        transactionManager = new RecordingTransactionManager();
        mirror = new PaymentProviderEvidenceMirror(observabilityService, transactionManager);
    }

    @Test
    void immediateMirrorRebuildsSafeSystemEvidenceInItsOwnRequiresNewTransaction() {
        PaymentProviderOperationLog log = operation("SUCCESS", "HANDLE_CALLBACK", null);

        mirror.mirrorImmediately(sandboxDescriptor(true), log);

        ProviderCallRecord record = capturedRecord();
        assertThat(record.sourceType()).isEqualTo("SYSTEM");
        assertThat(record.triggeredBy()).isNull();
        assertThat(record.providerKey()).isEqualTo("payment.alipay.sandbox");
        assertThat(record.providerDomain()).isEqualTo("PAYMENT");
        assertThat(record.providerEnv()).isEqualTo("SANDBOX");
        assertThat(record.providerName()).isEqualTo("ALIPAY");
        assertThat(record.configVersion()).isEqualTo("ALIPAY_SANDBOX_V1");
        assertThat(record.operationType()).isEqualTo("HANDLE_CALLBACK");
        assertThat(record.status()).isEqualTo("SUCCESS");
        assertThat(record.updateRegistry()).isTrue();
        assertThat(record.durationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(record.externalRef()).isEqualTo("***7890");
        assertThat(record.idempotencyKey()).isEqualTo("payment-operation-1");
        assertThat(record.snapshotType()).isEqualTo("PAYMENT_OPERATION");
        assertThat(record.snapshotId()).isEqualTo(41L);
        assertThat(record.confidence()).isEqualByComparingTo(BigDecimal.ONE);
        assertSafe(record);
        assertThat(transactionManager.propagationBehaviors)
            .containsExactly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(transactionManager.commitCount).isEqualTo(1);
        assertThat(transactionManager.rollbackCount).isZero();
    }

    @Test
    void normalizesLedgerOutcomesAndFailsClosedForUntrustedFailures() {
        assertOutcome("SUCCESS", "HANDLE_CALLBACK", null, true, "SUCCESS", true);
        assertOutcome("IGNORED", "HANDLE_CALLBACK", "DUPLICATE", true, "SKIPPED", false);
        assertOutcome("ACKNOWLEDGED", "HANDLE_CALLBACK", null, true, "SKIPPED", false);
        assertOutcome("FAILED", "HANDLE_CALLBACK", "AMOUNT_MISMATCH", true, "SKIPPED", false);
        assertOutcome("FAILED", "HANDLE_CALLBACK", "ALIPAY_TRADE_QUERY_FAILED", true, "FAILED", true);
        assertOutcome("FAILED", "STATEMENT", "ALIPAY_BILL_NOT_READY", true, "FAILED", true);
        assertOutcome("TIMEOUT", "QUERY_PAYMENT", "untrusted timeout detail", true, "TIMEOUT", true);
        assertOutcome("FAILED", "STATEMENT", "ALIPAY_BILL_DOWNLOAD_REJECTED", true, "SKIPPED", false);
        assertOutcome("FAILED", "STATEMENT", "ALIPAY_BILL_SCHEMA_UNSUPPORTED", true, "SKIPPED", false);
        assertOutcome("FAILED", "STATEMENT", "ALIPAY_BILL_PARSE_FAILED", true, "SKIPPED", false);
        assertOutcome("FAILED", "HANDLE_CALLBACK", "UNKNOWN_SECRET_SHAPED_CODE", true, "SKIPPED", false);
        assertOutcome("FAILED", "HANDLE_CALLBACK", "ALIPAY_TRADE_QUERY_FAILED", false, "SKIPPED", false);
    }

    @Test
    void ignoresDevAndInconsistentDescriptors() {
        PaymentProviderOperationLog log = operation("SUCCESS", "HANDLE_CALLBACK", null);

        mirror.mirrorImmediately(new PaymentProviderDescriptor(
            "payment.dev", "DEV", "DEV_PAYMENT", "Dev", "DEV_PAYMENT_V1", true), log);
        mirror.mirrorImmediately(new PaymentProviderDescriptor(
            "payment.alipay.sandbox", "SANDBOX", "ALIPAY", "Alipay Sandbox", "ALIPAY_SANDBOX_V0", true), log);

        verifyNoInteractions(observabilityService);
    }

    @Test
    void ignoresLedgerRowsWhosePersistedIdentityIsNotTheExactSandboxSnapshot() {
        PaymentProviderOperationLog log = operation("SUCCESS", "HANDLE_CALLBACK", null);
        log.providerEnv = "DEV";

        mirror.mirrorImmediately(sandboxDescriptor(true), log);

        verifyNoInteractions(observabilityService);
    }

    @Test
    void fullyRedactsExternalReferencesTooShortToLeaveAnIrreversibleSuffix() {
        PaymentProviderOperationLog log = operation("SUCCESS", "HANDLE_CALLBACK", null);
        log.externalRef = "ABCD";

        mirror.mirrorImmediately(sandboxDescriptor(true), log);

        assertThat(capturedRecord().externalRef()).isNull();
    }

    @Test
    void afterCommitCapturesAnImmutableProjectionThenUsesDistinctRequiresNewWork() {
        PaymentProviderOperationLog log = operation("SUCCESS", "HANDLE_CALLBACK", null);
        doAnswer(invocation -> {
            transactionManager.events.add("record");
            return null;
        }).when(observabilityService).recordCall(any(ProviderCallRecord.class));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            mirror.mirrorAfterCommit(sandboxDescriptor(true), log);
            log.status = "FAILED";
            log.externalRef = "changed-after-registration";
            verifyNoInteractions(observabilityService);
        });

        ProviderCallRecord record = capturedRecord();
        assertThat(record.status()).isEqualTo("SUCCESS");
        assertThat(record.externalRef()).isEqualTo("***7890");
        assertThat(transactionManager.propagationBehaviors).containsExactly(
            TransactionDefinition.PROPAGATION_REQUIRED,
            TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(transactionManager.events).containsSubsequence("commit", "record", "commit");
        assertThat(transactionManager.commitCount).isEqualTo(2);
        assertThat(transactionManager.rollbackCount).isZero();
    }

    @Test
    void swallowsPersistenceFailureWithoutLeakingItsTextToTheMirrorLog() {
        String rawExceptionText = "https://gateway.example/form?sign=leaked-secret";
        doThrow(new IllegalStateException(rawExceptionText)).when(observabilityService)
            .recordCall(any(ProviderCallRecord.class));
        Logger logger = (Logger) LoggerFactory.getLogger(PaymentProviderEvidenceMirror.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatCode(() -> mirror.mirrorImmediately(sandboxDescriptor(true),
                operation("SUCCESS", "HANDLE_CALLBACK", null))).doesNotThrowAnyException();

            assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .contains("Payment provider evidence mirror persistence failed")
                .noneMatch(message -> message.contains(rawExceptionText));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private void assertOutcome(String ledgerStatus, String operationType, String failureCode, boolean enabled,
                               String expectedStatus, boolean expectedRegistryUpdate) {
        clearInvocations(observabilityService);

        mirror.mirrorImmediately(sandboxDescriptor(enabled), operation(ledgerStatus, operationType, failureCode));

        ProviderCallRecord record = capturedRecord();
        assertThat(record.status()).isEqualTo(expectedStatus);
        assertThat(record.updateRegistry()).isEqualTo(expectedRegistryUpdate);
        if ("SKIPPED".equals(expectedStatus)) {
            assertThat(record.failureCode()).isNull();
        }
    }

    private ProviderCallRecord capturedRecord() {
        ArgumentCaptor<ProviderCallRecord> record = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(observabilityService).recordCall(record.capture());
        return record.getValue();
    }

    private void assertSafe(ProviderCallRecord record) {
        String visible = String.valueOf(record.requestSummaryJson())
            + record.responseSummaryJson()
            + record.failureCode()
            + record.failureMessage()
            + record.externalRef();
        assertThat(visible).doesNotContain(
            "sign", "app_id", "seller_id", "https://", "private-key", "form-field", "bill-row", "raw exception");
        assertThat(record.failureMessage()).isNull();
    }

    private PaymentProviderDescriptor sandboxDescriptor(boolean enabled) {
        return new PaymentProviderDescriptor(
            "payment.alipay.sandbox", "SANDBOX", "ALIPAY", "Alipay Sandbox", "ALIPAY_SANDBOX_V1", enabled);
    }

    private PaymentProviderOperationLog operation(String status, String operationType, String failureCode) {
        PaymentProviderOperationLog log = new PaymentProviderOperationLog();
        log.id = 41L;
        log.operationType = operationType;
        log.providerEnv = "SANDBOX";
        log.providerName = "ALIPAY";
        log.status = status;
        log.idempotencyKey = "payment-operation-1";
        log.externalRef = "ALI-TRADE-7890";
        log.configVersion = "ALIPAY_SANDBOX_V1";
        log.durationMs = -10L;
        log.failureCode = failureCode;
        log.failureMessage = "raw exception with private-key";
        log.requestSummaryJson = "{\"sign\":\"secret\",\"app_id\":\"app\",\"url\":\"https://gateway.example/form\"}";
        log.responseSummaryJson = "{\"seller_id\":\"seller\",\"form-field\":\"value\",\"bill-row\":\"raw\"}";
        log.metadataJson = "{\"key\":\"private-key\"}";
        return log;
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final List<Integer> propagationBehaviors = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final ThreadLocal<RecordingTransaction> currentTransaction = new ThreadLocal<>();
        private int commitCount;
        private int rollbackCount;

        @Override
        protected Object doGetTransaction() {
            RecordingTransaction existing = currentTransaction.get();
            return existing == null ? new RecordingTransaction() : existing;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return transaction == currentTransaction.get();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            propagationBehaviors.add(definition.getPropagationBehavior());
            currentTransaction.set((RecordingTransaction) transaction);
        }

        @Override
        protected Object doSuspend(Object transaction) {
            RecordingTransaction suspended = currentTransaction.get();
            currentTransaction.remove();
            return suspended;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            currentTransaction.set((RecordingTransaction) suspendedResources);
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((RecordingTransaction) status.getTransaction()).rollbackOnly = true;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
            commitCount++;
            currentTransaction.remove();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbackCount++;
            currentTransaction.remove();
        }

        private static final class RecordingTransaction implements SmartTransactionObject {
            private boolean rollbackOnly;

            @Override
            public boolean isRollbackOnly() {
                return rollbackOnly;
            }
        }
    }
}
