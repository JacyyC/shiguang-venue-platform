package com.example.venue.payment;

import com.example.venue.common.AuditLog;
import com.example.venue.order.entity.PaymentCallbackLog;
import com.example.venue.payment.controller.PaymentAdminController;
import com.example.venue.payment.dto.AdjustReconciliationRequest;
import com.example.venue.payment.dto.PaymentAdminOverviewResponse;
import com.example.venue.payment.dto.PaymentAdminQuery;
import com.example.venue.payment.dto.PaymentCallbackEvidenceView;
import com.example.venue.payment.dto.ReconciliationRunRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentAdminControllerTest {
    @Test
    void paymentAdminEndpointsRequirePaymentAdminPermission() throws Exception {
        assertPaymentAdminMethod("overview", PaymentAdminQuery.class, "/api/admin/payments/overview");
        assertPaymentAdminMethod("attempts", PaymentAdminQuery.class, "/api/admin/payments/attempts");
        assertPaymentAdminMethod("callbacks", PaymentAdminQuery.class, "/api/admin/payments/callbacks");

        Method providers = PaymentAdminController.class.getMethod("providers");
        assertThat(providers.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('payment:admin')");
        assertThat(providers.getAnnotation(GetMapping.class).value())
            .containsExactly("/api/admin/payments/providers");
    }

    @Test
    void reconciliationEndpointsRequireFinanceReconcilePermission() throws Exception {
        Method run = PaymentAdminController.class.getMethod("runReconciliation", ReconciliationRunRequest.class);
        assertThat(run.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('finance:reconcile')");
        assertThat(run.getAnnotation(PostMapping.class).value()).containsExactly("/api/admin/finance/reconciliation/run");

        Method items = PaymentAdminController.class.getMethod("reconciliationItems", PaymentAdminQuery.class);
        assertThat(items.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('finance:reconcile')");
        assertThat(items.getAnnotation(GetMapping.class).value()).containsExactly("/api/admin/finance/reconciliation/items");

        Method adjust = PaymentAdminController.class.getMethod("adjustReconciliationItem",
            Long.class, AdjustReconciliationRequest.class);
        assertThat(adjust.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('finance:reconcile')");
        assertThat(adjust.getAnnotation(PutMapping.class).value())
            .containsExactly("/api/admin/finance/reconciliation/items/{id}/adjust");
        assertThat(adjust.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
    }

    @Test
    void reconciliationRunAndAdjustAreAudited() throws Exception {
        Method run = PaymentAdminController.class.getMethod("runReconciliation", ReconciliationRunRequest.class);
        AuditLog runAudit = run.getAnnotation(AuditLog.class);
        assertThat(runAudit).isNotNull();
        assertThat(runAudit.module()).isEqualTo("财务对账");

        Method adjust = PaymentAdminController.class.getMethod("adjustReconciliationItem",
            Long.class, AdjustReconciliationRequest.class);
        AuditLog adjustAudit = adjust.getAnnotation(AuditLog.class);
        assertThat(adjustAudit).isNotNull();
        assertThat(adjustAudit.module()).isEqualTo("财务对账");
    }

    @Test
    void callbacksReturnMinimalEvidenceViewWithoutRawProviderPayload() throws Exception {
        Method callbacks = PaymentAdminController.class.getMethod("callbacks", PaymentAdminQuery.class);
        assertThat(callbacks.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('payment:admin')");
        assertThat(callbacks.getGenericReturnType().getTypeName())
            .contains(PaymentCallbackEvidenceView.class.getSimpleName());

        Set<String> viewFields = Arrays.stream(PaymentCallbackEvidenceView.class.getDeclaredFields())
            .map(Field::getName)
            .collect(Collectors.toSet());
        assertThat(viewFields).contains(
            "id", "channel", "orderNo", "transactionId", "status", "message", "sourceType",
            "confidence", "providerEnv", "providerName", "externalRef", "failureCode",
            "configVersion", "durationMs", "createTime");
        assertThat(viewFields).doesNotContain(
            "payload", "requestSummaryJson", "responseSummaryJson", "metadataJson", "idempotencyKey");
    }

    @Test
    void callbackEvidenceMappingKeepsMinimalSummaryAndNeverCopiesRawPayload() {
        PaymentCallbackLog log = new PaymentCallbackLog();
        log.id = 9L;
        log.channel = "NOTIFY";
        log.orderNo = "O-0912-073";
        log.transactionId = "TX-20260912-073";
        log.status = "SUCCESS";
        log.message = "已验签 · 已处理";
        log.providerEnv = "DEV";
        log.providerName = "DEV";
        log.configVersion = "DEV_V1";
        log.createTime = LocalDateTime.of(2026, 9, 12, 9, 42);
        log.payload = "{\"raw\":\"third-party-payload\",\"sign\":\"secret\"}";
        log.requestSummaryJson = "{\"raw\":\"request\"}";
        log.responseSummaryJson = "{\"raw\":\"response\"}";
        log.metadataJson = "{\"raw\":\"meta\"}";
        log.idempotencyKey = "idem-secret";

        PaymentCallbackEvidenceView view = PaymentCallbackEvidenceView.from(log);

        assertThat(view.id).isEqualTo(9L);
        assertThat(view.orderNo).isEqualTo("O-0912-073");
        assertThat(view.status).isEqualTo("SUCCESS");
        assertThat(view.message).isEqualTo("已验签 · 已处理");
        assertThat(view.providerEnv).isEqualTo("DEV");
        assertThat(view.providerName).isEqualTo("DEV");
        assertThat(view.configVersion).isEqualTo("DEV_V1");
        assertThat(view.createTime).isEqualTo(LocalDateTime.of(2026, 9, 12, 9, 42));

        String allValues = Arrays.stream(PaymentCallbackEvidenceView.class.getDeclaredFields())
            .map(field -> {
                field.setAccessible(true);
                try {
                    return String.valueOf(field.get(view));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            })
            .collect(Collectors.joining("|"));
        assertThat(allValues)
            .doesNotContain("third-party-payload", "\"raw\":\"request\"", "\"raw\":\"response\"",
                "\"raw\":\"meta\"", "idem-secret");
    }

    @Test
    void overviewIncludesDevProviderDataGapText() {
        PaymentAdminOverviewResponse response = PaymentAdminOverviewResponse.empty();

        assertThat(response.successAmount).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.dataGaps).contains("当前支付数据来自 DEV PaymentProvider，不代表真实生产支付网关成功率或渠道成本。");
    }

    @Test
    void reconciliationRunRequestPreservesMissingProviderValuesForRouterDefault() {
        ReconciliationRunRequest request = new ReconciliationRunRequest();
        request.reconcileDate = LocalDate.of(2026, 7, 4);

        assertThat(request.normalizedProviderEnv()).isNull();
        assertThat(request.normalizedProviderName()).isNull();
    }

    @Test
    void reconciliationRunRequestNormalizesConfiguredProviderValues() {
        ReconciliationRunRequest request = new ReconciliationRunRequest();
        request.providerEnv = " sandbox ";
        request.providerName = "\ttest_pay ";

        assertThat(request.normalizedProviderEnv()).isEqualTo("SANDBOX");
        assertThat(request.normalizedProviderName()).isEqualTo("TEST_PAY");
    }

    @Test
    void paymentAdminQueryPreservesMissingProviderValuesForRouterDefault() {
        PaymentAdminQuery query = new PaymentAdminQuery();

        assertThat(query.normalizedProviderEnv()).isNull();
        assertThat(query.normalizedProviderName()).isNull();
    }

    @Test
    void paymentAdminQueryNormalizesConfiguredProviderValues() {
        PaymentAdminQuery query = new PaymentAdminQuery();
        query.providerEnv = " sandbox ";
        query.providerName = "\ttest_pay ";

        assertThat(query.normalizedProviderEnv()).isEqualTo("SANDBOX");
        assertThat(query.normalizedProviderName()).isEqualTo("TEST_PAY");
    }

    private void assertPaymentAdminMethod(String methodName, Class<?> queryClass, String mapping) throws Exception {
        Method method = PaymentAdminController.class.getMethod(methodName, queryClass);
        PreAuthorize auth = method.getAnnotation(PreAuthorize.class);
        assertThat(auth.value()).isEqualTo("hasAuthority('payment:admin')");
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(mapping);
    }
}
