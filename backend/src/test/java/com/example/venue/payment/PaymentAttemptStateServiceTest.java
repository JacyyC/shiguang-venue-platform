package com.example.venue.payment;

import com.example.venue.payment.entity.PaymentAttempt;
import com.example.venue.payment.mapper.PaymentAttemptMapper;
import com.example.venue.payment.service.PaymentAttemptStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentAttemptStateServiceTest {
    @Mock
    private PaymentAttemptMapper paymentAttemptMapper;

    @Test
    void terminalFailureWriteUsesRequiresNewAndZeroesActualAmounts() {
        RecordingTransactionManager txManager = new RecordingTransactionManager();
        PaymentAttemptStateService service = new PaymentAttemptStateService(paymentAttemptMapper, txManager);
        PaymentAttempt attempt = pendingAttempt();

        service.markTerminalFailureInRequiresNew(attempt, "FAILED", "SIGNATURE_INVALID", "支付回调签名错误",
            "dev-PAY001", "{\"scenario\":\"SIGNATURE_INVALID\"}");

        assertThat(txManager.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(txManager.commitCount).isEqualTo(1);
        assertThat(txManager.rollbackCount).isZero();
        verify(paymentAttemptMapper).updateById(argThat((PaymentAttempt updated) ->
            "FAILED".equals(updated.status)
                && "dev-PAY001".equals(updated.externalRef)
                && "SIGNATURE_INVALID".equals(updated.failureCode)
                && "支付回调签名错误".equals(updated.failureMessage)
                && updated.paidAmount.compareTo(new BigDecimal("0.00")) == 0
                && updated.feeAmount.compareTo(new BigDecimal("0.00")) == 0
                && updated.netAmount.compareTo(new BigDecimal("0.00")) == 0
                && updated.requestSummaryJson.contains("SIGNATURE_INVALID")
                && updated.responseSummaryJson.contains("SIGNATURE_INVALID")
                && updated.updateTime != null));
    }

    private PaymentAttempt pendingAttempt() {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.paymentNo = "PAY001";
        attempt.status = "PENDING";
        attempt.paidAmount = new BigDecimal("13.00");
        attempt.feeAmount = new BigDecimal("0.60");
        attempt.netAmount = new BigDecimal("12.40");
        return attempt;
    }

    private static class RecordingTransactionManager implements PlatformTransactionManager {
        int propagationBehavior;
        int commitCount;
        int rollbackCount;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            propagationBehavior = definition.getPropagationBehavior();
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commitCount++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbackCount++;
        }
    }
}
