package com.example.venue.payment;

import com.example.venue.payment.dto.DevPaymentScenario;
import com.example.venue.payment.dto.PaymentAttemptStatus;
import com.example.venue.payment.dto.PaymentCallbackCommand;
import com.example.venue.payment.dto.PaymentCallbackResult;
import com.example.venue.payment.dto.PaymentCallbackStatus;
import com.example.venue.payment.dto.PaymentCloseCommand;
import com.example.venue.payment.dto.PaymentCloseResult;
import com.example.venue.payment.dto.PaymentCreateCommand;
import com.example.venue.payment.dto.PaymentCreateResult;
import com.example.venue.payment.dto.PaymentProviderOperationType;
import com.example.venue.payment.dto.ProviderStatementItem;
import com.example.venue.payment.dto.ProviderStatementQuery;
import com.example.venue.payment.entity.PaymentProviderOperationLog;
import com.example.venue.payment.mapper.PaymentProviderOperationLogMapper;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import com.example.venue.payment.service.DevPaymentProvider;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DevPaymentProviderTest {
    @Mock
    private PaymentProviderOperationLogMapper operationLogMapper;

    @Test
    void descriptorExposesStableNonSecretProviderIdentity() {
        PaymentProviderDescriptor descriptor = new DevPaymentProvider().descriptor();

        assertThat(descriptor.providerKey()).isEqualTo("payment.dev");
        assertThat(descriptor.providerEnv()).isEqualTo("DEV");
        assertThat(descriptor.providerName()).isEqualTo("DEV_PAYMENT");
        assertThat(descriptor.configVersion()).isEqualTo("DEV_PAYMENT_V1");
        assertThat(descriptor.enabled()).isTrue();
    }

    @Test
    void createPaymentReturnsPendingDevAttemptWithFeePreview() {
        DevPaymentProvider provider = new DevPaymentProvider();
        PaymentCreateResult result = provider.createPayment(new PaymentCreateCommand(
            "PAY-1", "ORD001", new BigDecimal("100.00"), "CNY", "idem-1"));

        assertThat(result.providerEnv()).isEqualTo("DEV");
        assertThat(result.providerName()).isEqualTo("DEV_PAYMENT");
        assertThat(result.configVersion()).isEqualTo("DEV_PAYMENT_V1");
        assertThat(result.status()).isEqualTo(PaymentAttemptStatus.PENDING.name());
        assertThat(result.feeAmount()).isEqualByComparingTo("0.60");
        assertThat(result.netAmount()).isEqualByComparingTo("99.40");
    }

    @Test
    void devCreateResultCarriesOnlyTheDevSimulationAction() {
        PaymentCreateResult result = new DevPaymentProvider().createPayment(new PaymentCreateCommand(
            "PAY001", "ORD001", new BigDecimal("100.00"), "CNY", "idem-1"));

        assertThat(result.paymentAction().type()).isEqualTo("DEV_SIMULATION");
        assertThat(result.paymentAction().url()).isNull();
        assertThat(result.paymentAction().fields()).isEmpty();
    }

    @Test
    void closePaymentReturnsMaskedDevelopmentCloseEvidence() {
        PaymentCloseResult result = new DevPaymentProvider().closePayment(
            new PaymentCloseCommand("PAY001", "ORD001", "payment-close-51"));

        assertThat(result.status()).isEqualTo("CLOSED");
        assertThat(result.externalRef()).isEqualTo("dev-close-PAY001");
        assertThat(result.requestSummaryJson()).contains("PAY001", "ORD001");
        assertThat(result.responseSummaryJson()).contains("CLOSED");
    }

    @Test
    void successScenarioBuildsSignedCallbackPayload() {
        DevPaymentProvider provider = new DevPaymentProvider();
        PaymentCallbackResult callback = provider.handleCallback(PaymentCallbackCommand.devScenario(
            "PAY-1", "ORD001", "TXN-1", new BigDecimal("100.00"), DevPaymentScenario.SUCCESS));

        assertThat(callback.status()).isEqualTo(PaymentCallbackStatus.SUCCESS.name());
        assertThat(callback.externalRef()).isEqualTo("TXN-1");
        assertThat(callback.feeAmount()).isEqualByComparingTo("0.60");
        assertThat(callback.netAmount()).isEqualByComparingTo("99.40");
        assertThat(callback.requestSummaryJson()).contains("ORD001");
        assertThat(callback.responseSummaryJson()).contains("TXN-1", "100.00", "0.60", "99.40");
    }

    @Test
    void statementBuildsProviderItemsFromSuccessfulCallbackOperationLedger() {
        PaymentProviderOperationLog log = new PaymentProviderOperationLog();
        log.operationType = PaymentProviderOperationType.HANDLE_CALLBACK.name();
        log.status = "SUCCESS";
        log.providerEnv = "DEV";
        log.providerName = "DEV_PAYMENT";
        log.externalRef = "TXN-1";
        log.responseSummaryJson = "{\"providerName\":\"DEV_PAYMENT\",\"status\":\"SUCCESS\","
            + "\"orderNo\":\"ORD001\",\"externalRef\":\"TXN-1\",\"paidAmount\":\"100.00\","
            + "\"feeAmount\":\"0.60\",\"netAmount\":\"99.40\"}";
        log.createTime = LocalDateTime.of(2026, 7, 4, 10, 30);
        when(operationLogMapper.selectList(any())).thenReturn(List.of(log));
        DevPaymentProvider provider = new DevPaymentProvider(operationLogMapper, new ObjectMapper().findAndRegisterModules());

        List<ProviderStatementItem> items = provider.statement(new ProviderStatementQuery(
            LocalDate.of(2026, 7, 4), "DEV", "DEV_PAYMENT", List.of()));

        assertThat(items).hasSize(1);
        ProviderStatementItem item = items.get(0);
        assertThat(item.externalRef).isEqualTo("TXN-1");
        assertThat(item.transactionId).isEqualTo("TXN-1");
        assertThat(item.orderNo).isEqualTo("ORD001");
        assertThat(item.amount).isEqualByComparingTo("100.00");
        assertThat(item.feeAmount).isEqualByComparingTo("0.60");
        assertThat(item.netAmount).isEqualByComparingTo("99.40");
    }

    @Test
    void statementIncludesAcknowledgedLateSuccessCallbackToAvoidMissingProviderFalseDiff() {
        // A26-P3-07：迟到成功支付写的 operation status 是 ACKNOWLEDGED（非 SUCCESS），
        // statement 必须同时包含 ACKNOWLEDGED 与 SUCCESS，否则对账会产生 MISSING_PROVIDER 假差异。
        PaymentProviderOperationLog successLog = new PaymentProviderOperationLog();
        successLog.operationType = PaymentProviderOperationType.HANDLE_CALLBACK.name();
        successLog.status = "SUCCESS";
        successLog.providerEnv = "DEV";
        successLog.providerName = "DEV_PAYMENT";
        successLog.externalRef = "TXN-SUCCESS";
        successLog.responseSummaryJson = "{\"providerName\":\"DEV_PAYMENT\",\"status\":\"SUCCESS\","
            + "\"orderNo\":\"ORD001\",\"externalRef\":\"TXN-SUCCESS\",\"paidAmount\":\"100.00\","
            + "\"feeAmount\":\"0.60\",\"netAmount\":\"99.40\"}";
        successLog.createTime = LocalDateTime.of(2026, 7, 4, 10, 30);

        PaymentProviderOperationLog acknowledgedLog = new PaymentProviderOperationLog();
        acknowledgedLog.operationType = PaymentProviderOperationType.HANDLE_CALLBACK.name();
        acknowledgedLog.status = "ACKNOWLEDGED";
        acknowledgedLog.providerEnv = "DEV";
        acknowledgedLog.providerName = "DEV_PAYMENT";
        acknowledgedLog.externalRef = "TXN-LATE";
        acknowledgedLog.responseSummaryJson = "{\"providerName\":\"DEV_PAYMENT\",\"status\":\"ACKNOWLEDGED\","
            + "\"orderNo\":\"ORD002\",\"externalRef\":\"TXN-LATE\",\"paidAmount\":\"50.00\","
            + "\"feeAmount\":\"0.30\",\"netAmount\":\"49.70\"}";
        acknowledgedLog.createTime = LocalDateTime.of(2026, 7, 4, 11, 30);

        when(operationLogMapper.selectList(any())).thenReturn(List.of(successLog, acknowledgedLog));
        DevPaymentProvider provider = new DevPaymentProvider(operationLogMapper, new ObjectMapper().findAndRegisterModules());

        List<ProviderStatementItem> items = provider.statement(new ProviderStatementQuery(
            LocalDate.of(2026, 7, 4), "DEV", "DEV_PAYMENT", List.of()));

        assertThat(items).hasSize(2);
        assertThat(items).extracting(item -> item.externalRef)
            .containsExactly("TXN-SUCCESS", "TXN-LATE");
        assertThat(items.get(1).amount).isEqualByComparingTo("50.00");
        assertThat(items.get(1).feeAmount).isEqualByComparingTo("0.30");
    }

    @Test
    void statementDoesNotCopyExpectedSystemItemsWhenProviderLedgerIsEmpty() {
        when(operationLogMapper.selectList(any())).thenReturn(List.of());
        DevPaymentProvider provider = new DevPaymentProvider(operationLogMapper, new ObjectMapper().findAndRegisterModules());

        List<ProviderStatementItem> items = provider.statement(new ProviderStatementQuery(
            LocalDate.of(2026, 7, 4),
            "DEV",
            "DEV_PAYMENT",
            List.of(ProviderStatementItem.payment("dev-PAY001", "ORD001", new BigDecimal("100.00"),
                new BigDecimal("0.60"), new BigDecimal("99.40")))));

        assertThat(items).isEmpty();
    }

    @Test
    void paymentStatusEnumsMatchB6SeededValues() {
        assertThat(PaymentAttemptStatus.values()).extracting(Enum::name)
            .containsExactly("CREATED", "PENDING", "SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT", "CLOSED");
        assertThat(PaymentCallbackStatus.values()).extracting(Enum::name)
            .containsExactly("RECEIVED", "SUCCESS", "IGNORED_DUPLICATE", "SIGNATURE_INVALID",
                "AMOUNT_MISMATCH", "ORDER_STATE_INVALID", "FAILED");
        assertThat(PaymentProviderOperationType.values()).extracting(Enum::name)
            .contains("CREATE_PAYMENT", "CLOSE_PAYMENT", "HANDLE_CALLBACK", "STATEMENT");
    }
}
