package com.example.venue.payment;

import com.example.venue.common.BusinessException;
import com.example.venue.order.entity.PaymentRecord;
import com.example.venue.order.mapper.PaymentRecordMapper;
import com.example.venue.payment.dto.AdjustReconciliationRequest;
import com.example.venue.payment.dto.PaymentAdminQuery;
import com.example.venue.payment.dto.PaymentProviderOperationType;
import com.example.venue.payment.dto.ProviderStatementItem;
import com.example.venue.payment.dto.ProviderStatementQuery;
import com.example.venue.payment.dto.ReconciliationRunResponse;
import com.example.venue.payment.entity.FinanceReconciliationItem;
import com.example.venue.payment.entity.PaymentProviderOperationLog;
import com.example.venue.payment.mapper.FinanceReconciliationItemMapper;
import com.example.venue.payment.mapper.PaymentAttemptMapper;
import com.example.venue.payment.provider.PaymentProvider;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import com.example.venue.payment.provider.PaymentProviderEvidenceMirror;
import com.example.venue.payment.provider.PaymentProviderOptionResponse;
import com.example.venue.payment.provider.PaymentProviderRouter;
import com.example.venue.payment.provider.alipay.AlipaySandboxException;
import com.example.venue.payment.service.FinanceReconciliationPersistenceService;
import com.example.venue.payment.service.FinanceReconciliationService;
import com.example.venue.payment.service.PaymentCallbackLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinanceReconciliationServiceTest {
    @Mock
    private PaymentRecordMapper paymentRecordMapper;
    @Mock
    private PaymentAttemptMapper paymentAttemptMapper;
    @Mock
    private FinanceReconciliationItemMapper reconciliationMapper;
    @Mock
    private PaymentProviderRouter paymentProviderRouter;
    @Mock
    private PaymentProvider paymentProvider;
    @Mock
    private PaymentProvider sandboxPaymentProvider;
    @Mock
    private PaymentCallbackLogService callbackLogService;
    @Mock
    private FinanceReconciliationPersistenceService persistenceService;
    @Mock
    private PaymentProviderEvidenceMirror paymentProviderEvidenceMirror;

    private FinanceReconciliationService service;
    private FinanceReconciliationPersistenceService realPersistenceService;
    private final LocalDate date = LocalDate.of(2026, 7, 4);

    @BeforeEach
    void setUp() {
        lenient().when(paymentProviderRouter.requireDefault()).thenReturn(paymentProvider);
        lenient().when(paymentProviderRouter.require("DEV", "DEV_PAYMENT")).thenReturn(paymentProvider);
        lenient().when(paymentProvider.descriptor()).thenReturn(devDescriptor());
        service = new FinanceReconciliationService(paymentRecordMapper, paymentAttemptMapper, null,
            reconciliationMapper, paymentProviderRouter, callbackLogService, persistenceService,
            paymentProviderEvidenceMirror,
            Clock.fixed(Instant.parse("2026-07-04T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
        realPersistenceService = new FinanceReconciliationPersistenceService(paymentRecordMapper, reconciliationMapper);
    }

    @Test
    void fetchesImmutableStatementBeforeCallingTheTransactionalPersistenceService() throws Exception {
        ReconciliationRunResponse expected = new ReconciliationRunResponse(date, "DEV", "DEV_PAYMENT");
        when(paymentProvider.statement(any(ProviderStatementQuery.class))).thenReturn(List.of());
        when(persistenceService.persist(eq(date), eq(devDescriptor()), any())).thenReturn(expected);

        assertThat(service.generate(date, "DEV", "DEV_PAYMENT")).isSameAs(expected);

        assertThat(FinanceReconciliationService.class
            .getMethod("generate", LocalDate.class, String.class, String.class)
            .getAnnotation(Transactional.class)).isNull();
        assertThat(FinanceReconciliationPersistenceService.class
            .getMethod("persist", LocalDate.class, PaymentProviderDescriptor.class, List.class)
            .getAnnotation(Transactional.class)).isNotNull();
        var inOrder = inOrder(paymentProvider, persistenceService);
        inOrder.verify(paymentProvider).statement(any(ProviderStatementQuery.class));
        inOrder.verify(persistenceService).persist(eq(date), eq(devDescriptor()),
            argThat((List<ProviderStatementItem.ImmutableSnapshot> statements) -> statements.isEmpty()));
        verifyNoInteractions(paymentProviderEvidenceMirror);
    }

    @Test
    void coordinatorDeepCopiesPaymentRowsFiltersNonPaymentsAndRecordsOnlySafeCounts() {
        ProviderStatementItem payment = ProviderStatementItem.payment("TRADE-1", "ORDER-1",
            new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("100.00"));
        payment.dataGap = "FEE_AMOUNT_MISSING";
        ProviderStatementItem refund = ProviderStatementItem.payment("REFUND-1", "ORDER-2",
            new BigDecimal("10.00"), new BigDecimal("0.00"), new BigDecimal("10.00"));
        refund.businessType = "REFUND_OR_VOID";
        ReconciliationRunResponse expected = new ReconciliationRunResponse(date, "DEV", "DEV_PAYMENT");
        when(paymentProvider.statement(any())).thenReturn(List.of(payment, refund));
        when(persistenceService.persist(eq(date), eq(devDescriptor()), any())).thenReturn(expected);

        service.generate(date, "DEV", "DEV_PAYMENT");

        ArgumentCaptor<List<ProviderStatementItem.ImmutableSnapshot>> snapshots = snapshotCaptor();
        verify(persistenceService).persist(eq(date), eq(devDescriptor()), snapshots.capture());
        payment.businessType = "MUTATED";
        payment.externalRef = "MUTATED";
        payment.transactionId = "MUTATED";
        payment.orderNo = "MUTATED";
        payment.amount = BigDecimal.ZERO;
        payment.feeAmount = BigDecimal.ONE;
        payment.netAmount = BigDecimal.ONE;
        payment.dataGap = "MUTATED";
        assertThat(snapshots.getValue()).containsExactly(new ProviderStatementItem.ImmutableSnapshot(
            "PAYMENT", "TRADE-1", "TRADE-1", "ORDER-1", new BigDecimal("100.00"),
            new BigDecimal("0.00"), new BigDecimal("100.00"), "FEE_AMOUNT_MISSING"));

        ArgumentCaptor<PaymentProviderOperationLog> operation = ArgumentCaptor.forClass(PaymentProviderOperationLog.class);
        verify(callbackLogService).recordOperation(operation.capture());
        assertThat(operation.getValue().responseSummaryJson)
            .isEqualTo("{\"providerItemCount\":2,\"skippedNonPaymentCount\":1,\"missingFeeCount\":1}");
        assertThat(operation.getValue().responseSummaryJson)
            .doesNotContain("TRADE-1", "REFUND-1", "ORDER-1", "FEE_AMOUNT_MISSING");
    }

    @Test
    void failedStatementNeverCallsPersistenceAndRecordsStableSafeEvidence() {
        String rawFailure = "https://fixture-download.alipay.com/bill.zip contains private csv row";
        when(paymentProvider.statement(any())).thenThrow(new IllegalStateException(rawFailure));

        assertThatThrownBy(() -> service.generate(date, "DEV", "DEV_PAYMENT"))
            .isInstanceOf(AlipaySandboxException.class)
            .extracting(error -> ((AlipaySandboxException) error).getCode())
            .isEqualTo("ALIPAY_BILL_PARSE_FAILED");

        verifyNoInteractions(persistenceService);
        ArgumentCaptor<PaymentProviderOperationLog> operation = ArgumentCaptor.forClass(PaymentProviderOperationLog.class);
        verify(callbackLogService).recordOperation(operation.capture());
        PaymentProviderOperationLog log = operation.getValue();
        assertThat(log.operationType).isEqualTo(PaymentProviderOperationType.STATEMENT.name());
        assertThat(log.status).isEqualTo("FAILED");
        assertThat(log.failureCode).isEqualTo("ALIPAY_BILL_PARSE_FAILED");
        assertThat(log.failureMessage).isEqualTo("账单获取或解析失败，请稍后重试");
        assertThat(log.requestSummaryJson + String.valueOf(log.responseSummaryJson) + log.failureMessage)
            .doesNotContain(rawFailure, "fixture-download", "bill.zip", "csv");
    }

    @Test
    void normalizesUnknownBillPrefixedFailureCodeBeforeRecordingOrReturningIt() {
        String untrustedCode = "ALIPAY_BILL_UNTRUSTED_PROVIDER_TEXT";
        when(paymentProvider.statement(any())).thenThrow(AlipaySandboxException.config(untrustedCode));

        assertThatThrownBy(() -> service.generate(date, "DEV", "DEV_PAYMENT"))
            .isInstanceOf(AlipaySandboxException.class)
            .extracting(error -> ((AlipaySandboxException) error).getCode())
            .isEqualTo("ALIPAY_BILL_PARSE_FAILED");

        ArgumentCaptor<PaymentProviderOperationLog> operation = ArgumentCaptor.forClass(PaymentProviderOperationLog.class);
        verify(callbackLogService).recordOperation(operation.capture());
        assertThat(operation.getValue().failureCode).isEqualTo("ALIPAY_BILL_PARSE_FAILED");
        assertThat(operation.getValue().failureMessage).doesNotContain("UNTRUSTED_PROVIDER_TEXT");
    }

    @Test
    void persistenceRetainsAdjustedItemsAndNeverCreatesMissingSystemForRefundOrVoid() {
        PaymentRecord record = successPaymentRecord("ORD001", "TRADE-1", "100.00", "0.60", "99.40");
        FinanceReconciliationItem adjusted = differenceItem();
        adjusted.status = "ADJUSTED";
        when(paymentRecordMapper.selectList(any())).thenReturn(List.of(record));
        when(reconciliationMapper.findByReconcileNo("REC-20260704-PAYMENT-TRADE-1")).thenReturn(adjusted);
        ProviderStatementItem.ImmutableSnapshot payment = ProviderStatementItem.ImmutableSnapshot.from(
            ProviderStatementItem.payment("TRADE-1", "ORD001", new BigDecimal("100.00"),
                new BigDecimal("0.60"), new BigDecimal("99.40")));
        ProviderStatementItem refundRow = ProviderStatementItem.payment("REFUND-1", "ORD002",
            new BigDecimal("10.00"), new BigDecimal("0.00"), new BigDecimal("10.00"));
        refundRow.businessType = "REFUND_OR_VOID";

        ReconciliationRunResponse response = realPersistenceService.persist(date, devDescriptor(), List.of(
            payment, ProviderStatementItem.ImmutableSnapshot.from(refundRow)));

        assertThat(response.missingSystemCount).isZero();
        verify(reconciliationMapper).findByReconcileNo("REC-20260704-PAYMENT-TRADE-1");
        verifyNoMoreInteractions(reconciliationMapper);
    }

    @Test
    void persistenceCreatesMatchedDifferenceAndMissingItemsFromPaymentSnapshots() {
        PaymentRecord record = successPaymentRecord("ORD001", "TRADE-1", "100.00", "0.60", "99.40");
        when(paymentRecordMapper.selectList(any())).thenReturn(List.of(record));
        ProviderStatementItem.ImmutableSnapshot matching = ProviderStatementItem.ImmutableSnapshot.from(
            ProviderStatementItem.payment("TRADE-1", "ORD001", new BigDecimal("100.00"),
                new BigDecimal("0.60"), new BigDecimal("99.40")));
        ProviderStatementItem.ImmutableSnapshot providerOnly = ProviderStatementItem.ImmutableSnapshot.from(
            ProviderStatementItem.payment("TRADE-2", "ORD002", new BigDecimal("50.00"),
                new BigDecimal("0.30"), new BigDecimal("49.70")));

        ReconciliationRunResponse response = realPersistenceService.persist(date, devDescriptor(), List.of(matching, providerOnly));

        assertThat(response.matchedCount).isEqualTo(1);
        assertThat(response.missingSystemCount).isEqualTo(1);
        verify(reconciliationMapper).insert(argThat((FinanceReconciliationItem item) ->
            "MATCHED".equals(item.status) && "REC-20260704-PAYMENT-TRADE-1".equals(item.reconcileNo)));
        verify(reconciliationMapper).insert(argThat((FinanceReconciliationItem item) ->
            "MISSING_SYSTEM".equals(item.status) && "REC-20260704-PAYMENT-TRADE-2".equals(item.reconcileNo)));
    }

    @Test
    void persistenceHashesLongExternalReferencesIntoBoundedDistinctReconciliationNumbers() {
        when(paymentRecordMapper.selectList(any())).thenReturn(List.of());
        String firstReference = "T".repeat(127) + "A";
        String secondReference = "T".repeat(127) + "B";

        realPersistenceService.persist(date, devDescriptor(), List.of(
            ProviderStatementItem.ImmutableSnapshot.from(ProviderStatementItem.payment(firstReference, "ORD001",
                new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("10.00"))),
            ProviderStatementItem.ImmutableSnapshot.from(ProviderStatementItem.payment(secondReference, "ORD002",
                new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("10.00")))));

        ArgumentCaptor<FinanceReconciliationItem> items = ArgumentCaptor.forClass(FinanceReconciliationItem.class);
        verify(reconciliationMapper, org.mockito.Mockito.times(2)).insert(items.capture());
        assertThat(items.getAllValues()).allSatisfy(item -> assertThat(item.reconcileNo).hasSizeLessThanOrEqualTo(64));
        assertThat(items.getAllValues().get(0).reconcileNo).isNotEqualTo(items.getAllValues().get(1).reconcileNo);
    }

    @Test
    void persistenceEvidenceNeverCopiesProviderCsvFields() {
        String providerCsvValue = "CSV_FIELD_SHOULD_NOT_ENTER_EVIDENCE";
        when(paymentRecordMapper.selectList(any())).thenReturn(List.of());
        ProviderStatementItem.ImmutableSnapshot providerOnly = ProviderStatementItem.ImmutableSnapshot.from(
            ProviderStatementItem.payment(providerCsvValue, "ORDER-2", new BigDecimal("50.00"),
                new BigDecimal("0.30"), new BigDecimal("49.70")));

        realPersistenceService.persist(date, devDescriptor(), List.of(providerOnly));

        verify(reconciliationMapper).insert(argThat((FinanceReconciliationItem item) ->
            "MISSING_SYSTEM".equals(item.status)
                && providerCsvValue.equals(item.externalRef)
                && "{\"systemRecordPresent\":false,\"providerRecordPresent\":true}".equals(item.evidenceJson)
                && !item.evidenceJson.contains(providerCsvValue)));
    }

    @Test
    void persistenceDoesNotOverwriteMatchedItemWhenProviderBillHasDuplicateTradeNo() {
        // A26-P3-10：账单出现重复 trade_no 行时，第二次写入同 reconcileNo 不可把 MATCHED 静默改写为 MISSING_SYSTEM，
        // 响应计数必须与落库一致（matchedCount==1, missingSystemCount==0）。
        PaymentRecord record = successPaymentRecord("ORD001", "TRADE-DUP", "100.00", "0.60", "99.40");
        when(paymentRecordMapper.selectList(any())).thenReturn(List.of(record));
        // 两条相同 externalRef 的账单行（模拟账单重复 trade_no）
        ProviderStatementItem.ImmutableSnapshot firstBill = ProviderStatementItem.ImmutableSnapshot.from(
            ProviderStatementItem.payment("TRADE-DUP", "ORD001", new BigDecimal("100.00"),
                new BigDecimal("0.60"), new BigDecimal("99.40")));
        ProviderStatementItem.ImmutableSnapshot secondBill = ProviderStatementItem.ImmutableSnapshot.from(
            ProviderStatementItem.payment("TRADE-DUP", "ORD001", new BigDecimal("100.00"),
                new BigDecimal("0.60"), new BigDecimal("99.40")));
        // 第一次 findByReconcileNo（系统记录循环）返回 null → insert MATCHED；
        // 第二次 findByReconcileNo（剩余账单循环，同 reconcileNo）返回已存在的 MATCHED → 跳过不计数。
        FinanceReconciliationItem existingMatched = new FinanceReconciliationItem();
        existingMatched.reconcileNo = "REC-20260704-PAYMENT-TRADE-DUP";
        existingMatched.status = "MATCHED";
        when(reconciliationMapper.findByReconcileNo("REC-20260704-PAYMENT-TRADE-DUP"))
            .thenReturn(null)
            .thenReturn(existingMatched);

        ReconciliationRunResponse response = realPersistenceService.persist(date, devDescriptor(),
            List.of(firstBill, secondBill));

        // 第一条匹配系统记录写 MATCHED；第二条作为剩余账单项本应写 MISSING_SYSTEM，
        // 但 findByReconcileNo 命中已存在的 MATCHED（同一 reconcileNo REC-20260704-PAYMENT-TRADE-DUP），
        // 跳过不覆盖也不计数。
        assertThat(response.matchedCount).isEqualTo(1);
        assertThat(response.missingSystemCount).isZero();
        assertThat(response.totalCount).isEqualTo(1);
        verify(reconciliationMapper, org.mockito.Mockito.times(1)).insert(any(FinanceReconciliationItem.class));
        verify(reconciliationMapper, never()).updateById(any(FinanceReconciliationItem.class));
    }

    @Test
    void persistenceCatchesDuplicateKeyExceptionAndSkipsConcurrentInsertWithoutDropping500() {
        // A26-P3-11：并发同日期对账撞 unique key 时不应以裸 DuplicateKeyException(500) 失败丢弃已算结果。
        PaymentRecord record = successPaymentRecord("ORD001", "TRADE-CONCURRENT", "100.00", "0.60", "99.40");
        when(paymentRecordMapper.selectList(any())).thenReturn(List.of(record));
        ProviderStatementItem.ImmutableSnapshot matching = ProviderStatementItem.ImmutableSnapshot.from(
            ProviderStatementItem.payment("TRADE-CONCURRENT", "ORD001", new BigDecimal("100.00"),
                new BigDecimal("0.60"), new BigDecimal("99.40")));
        // 第一次 findByReconcileNo 返回 null（select-then-insert 路径开始）；
        // insert 抛 DuplicateKeyException 模拟并发已写入；
        // 第二次 findByReconcileNo 重查返回已存在项，跳过本条不计数。
        FinanceReconciliationItem concurrentItem = new FinanceReconciliationItem();
        concurrentItem.reconcileNo = "REC-20260704-PAYMENT-TRADE-CONCURRENT";
        concurrentItem.status = "MATCHED";
        when(reconciliationMapper.findByReconcileNo("REC-20260704-PAYMENT-TRADE-CONCURRENT"))
            .thenReturn(null)
            .thenReturn(concurrentItem);
        when(reconciliationMapper.insert(any(FinanceReconciliationItem.class)))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_finance_reconciliation_no"));

        ReconciliationRunResponse response = realPersistenceService.persist(date, devDescriptor(),
            List.of(matching));

        // 不抛异常，已存在项不被覆盖，响应计数为 0（跳过本条）
        assertThat(response.matchedCount).isZero();
        assertThat(response.totalCount).isZero();
        verify(reconciliationMapper, never()).updateById(any(FinanceReconciliationItem.class));
    }

    @Test
    void generateUsesRequestedProviderAndConfigVersionForStatementEvidence() {
        PaymentProviderDescriptor descriptor = sandboxDescriptor();
        when(paymentProviderRouter.require("SANDBOX", "TEST_PAY")).thenReturn(sandboxPaymentProvider);
        when(sandboxPaymentProvider.descriptor()).thenReturn(descriptor);
        when(sandboxPaymentProvider.statement(any())).thenReturn(List.of());
        when(persistenceService.persist(eq(date), eq(descriptor), any())).thenReturn(
            new ReconciliationRunResponse(date, "SANDBOX", "TEST_PAY"));

        ReconciliationRunResponse response = service.generate(
            date, "SANDBOX", "TEST_PAY");

        assertThat(response.providerEnv).isEqualTo("SANDBOX");
        assertThat(response.providerName).isEqualTo("TEST_PAY");
        verify(sandboxPaymentProvider).statement(argThat((ProviderStatementQuery query) ->
            "SANDBOX".equals(query.providerEnv) && "TEST_PAY".equals(query.providerName)));
        verify(callbackLogService).recordOperation(argThat((PaymentProviderOperationLog log) ->
            "SANDBOX".equals(log.providerEnv)
                && "TEST_PAY".equals(log.providerName)
                && "TEST_V1".equals(log.configVersion)));
    }

    @Test
    void sandboxStatementMirrorRunsImmediatelyOnlyAfterItsLedgerInsert() {
        PaymentProviderDescriptor descriptor = alipaySandboxDescriptor();
        when(paymentProviderRouter.require("SANDBOX", "ALIPAY")).thenReturn(sandboxPaymentProvider);
        when(sandboxPaymentProvider.descriptor()).thenReturn(descriptor);
        when(sandboxPaymentProvider.statement(any())).thenReturn(List.of());
        when(persistenceService.persist(eq(date), eq(descriptor), any())).thenReturn(
            new ReconciliationRunResponse(date, "SANDBOX", "ALIPAY"));

        service.generate(date, "SANDBOX", "ALIPAY");

        var orderedEvidence = inOrder(callbackLogService, paymentProviderEvidenceMirror);
        orderedEvidence.verify(callbackLogService).recordOperation(any(PaymentProviderOperationLog.class));
        orderedEvidence.verify(paymentProviderEvidenceMirror).mirrorImmediately(eq(descriptor),
            argThat((PaymentProviderOperationLog log) -> "SUCCESS".equals(log.status)
                && PaymentProviderOperationType.STATEMENT.name().equals(log.operationType)));
    }

    @Test
    void sandboxStatementFailureMirrorsImmediatelyOnlyAfterItsLedgerInsert() {
        PaymentProviderDescriptor descriptor = alipaySandboxDescriptor();
        when(paymentProviderRouter.require("SANDBOX", "ALIPAY")).thenReturn(sandboxPaymentProvider);
        when(sandboxPaymentProvider.descriptor()).thenReturn(descriptor);
        when(sandboxPaymentProvider.statement(any())).thenThrow(
            AlipaySandboxException.config("ALIPAY_BILL_NOT_READY"));

        assertThatThrownBy(() -> service.generate(date, "SANDBOX", "ALIPAY"))
            .isInstanceOf(AlipaySandboxException.class);

        var orderedEvidence = inOrder(callbackLogService, paymentProviderEvidenceMirror);
        orderedEvidence.verify(callbackLogService).recordOperation(any(PaymentProviderOperationLog.class));
        orderedEvidence.verify(paymentProviderEvidenceMirror).mirrorImmediately(eq(descriptor),
            argThat((PaymentProviderOperationLog log) -> "FAILED".equals(log.status)
                && "ALIPAY_BILL_NOT_READY".equals(log.failureCode)));
    }

    @Test
    void generateRejectsUnknownProviderBeforeQueryOrInsert() {
        when(paymentProviderRouter.require("SANDBOX", "UNKNOWN_PAY")).thenThrow(
            BusinessException.notFound("PAYMENT_PROVIDER_NOT_FOUND: 支付 Provider 不存在或未启用"));

        assertThatThrownBy(() -> service.generate(LocalDate.of(2026, 7, 4), "SANDBOX", "UNKNOWN_PAY"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PAYMENT_PROVIDER_NOT_FOUND");
        verifyNoInteractions(paymentRecordMapper, reconciliationMapper, persistenceService);
    }

    @Test
    void overviewQueriesKnownProviderAndReturnsProviderSpecificDataGap() {
        PaymentAdminQuery query = new PaymentAdminQuery();
        query.providerEnv = " sandbox ";
        query.providerName = " test_pay ";
        when(paymentProviderRouter.require("SANDBOX", "TEST_PAY")).thenReturn(sandboxPaymentProvider);
        when(sandboxPaymentProvider.descriptor()).thenReturn(sandboxDescriptor());
        when(paymentRecordMapper.selectList(any())).thenReturn(List.of());
        when(paymentAttemptMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.overview(query).dataGaps)
            .anyMatch(gap -> gap.contains("Test Pay") && gap.contains("SANDBOX"));

        verify(paymentProviderRouter).require("SANDBOX", "TEST_PAY");
        verify(paymentRecordMapper).selectList(any());
        verify(paymentAttemptMapper).selectList(any());
    }

    @Test
    void providerOptionsExposeCatalogAndMarkConfiguredDefault() {
        PaymentProviderDescriptor dev = devDescriptor();
        PaymentProviderDescriptor sandbox = sandboxDescriptor();
        when(paymentProviderRouter.catalog()).thenReturn(List.of(dev, sandbox));

        List<PaymentProviderOptionResponse> options = service.providerOptions();

        assertThat(options).containsExactly(
            new PaymentProviderOptionResponse(
                "payment.dev", "DEV", "DEV_PAYMENT", "DEV PaymentProvider", "DEV_PAYMENT_V1", true, true),
            new PaymentProviderOptionResponse(
                "payment.test", "SANDBOX", "TEST_PAY", "Test Pay", "TEST_V1", true, false));
    }

    @Test
    void adjustDifferenceRequiresReasonAndMarksAdjusted() {
        FinanceReconciliationItem item = differenceItem();
        when(reconciliationMapper.selectById(8L)).thenReturn(item);

        service.adjust(8L, new AdjustReconciliationRequest(new BigDecimal("-2.00"),
            "DEV provider test correction"));

        verify(reconciliationMapper).updateById(argThat((FinanceReconciliationItem updated) ->
            "ADJUSTED".equals(updated.status)
                && updated.adjustmentAmount.compareTo(new BigDecimal("-2.00")) == 0
                && "DEV provider test correction".equals(updated.adjustmentReason)));
    }

    @Test
    void adjustDifferenceRejectsBlankReason() {
        FinanceReconciliationItem item = differenceItem();
        when(reconciliationMapper.selectById(8L)).thenReturn(item);

        assertThatThrownBy(() -> service.adjust(8L, new AdjustReconciliationRequest(new BigDecimal("-2.00"), " ")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("原因");
    }

    private PaymentRecord successPaymentRecord(String orderNo, String externalRef, String amount,
                                               String fee, String net) {
        PaymentRecord record = new PaymentRecord();
        record.id = 21L;
        record.paymentAttemptId = 31L;
        record.orderId = 11L;
        record.orderNo = orderNo;
        record.channel = "DEV_PAYMENT";
        record.transactionId = externalRef;
        record.payAmount = new BigDecimal(amount);
        record.feeAmount = new BigDecimal(fee);
        record.netAmount = new BigDecimal(net);
        record.status = "SUCCESS";
        record.payTime = LocalDateTime.of(2026, 7, 4, 10, 30);
        record.sourceType = "PROVIDER";
        record.providerEnv = "DEV";
        record.providerName = "DEV_PAYMENT";
        record.externalRef = externalRef;
        record.configVersion = "DEV_PAYMENT_V1";
        record.createTime = record.payTime;
        return record;
    }

    private FinanceReconciliationItem differenceItem() {
        FinanceReconciliationItem item = new FinanceReconciliationItem();
        item.id = 8L;
        item.reconcileNo = "REC-20260704-PAYMENT-dev-PAY001";
        item.status = "DIFFERENCE";
        item.systemAmount = new BigDecimal("100.00");
        item.providerAmount = new BigDecimal("98.00");
        item.differenceAmount = new BigDecimal("2.00");
        item.deleted = 0;
        return item;
    }

    private PaymentProviderDescriptor devDescriptor() {
        return new PaymentProviderDescriptor(
            "payment.dev", "DEV", "DEV_PAYMENT", "DEV PaymentProvider", "DEV_PAYMENT_V1", true);
    }

    private PaymentProviderDescriptor sandboxDescriptor() {
        return new PaymentProviderDescriptor(
            "payment.test", "SANDBOX", "TEST_PAY", "Test Pay", "TEST_V1", true);
    }

    private PaymentProviderDescriptor alipaySandboxDescriptor() {
        return new PaymentProviderDescriptor(
            "payment.alipay.sandbox", "SANDBOX", "ALIPAY", "Alipay Sandbox", "ALIPAY_SANDBOX_V1", true);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<ProviderStatementItem.ImmutableSnapshot>> snapshotCaptor() {
        return (ArgumentCaptor<List<ProviderStatementItem.ImmutableSnapshot>>) (ArgumentCaptor<?>)
            ArgumentCaptor.forClass(List.class);
    }
}
