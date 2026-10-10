package com.example.venue.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.BusinessException;
import com.example.venue.common.PageResult;
import com.example.venue.order.entity.PaymentCallbackLog;
import com.example.venue.order.entity.PaymentRecord;
import com.example.venue.order.mapper.PaymentCallbackLogMapper;
import com.example.venue.order.mapper.PaymentRecordMapper;
import com.example.venue.payment.dto.AdjustReconciliationRequest;
import com.example.venue.payment.dto.FailureReasonMetric;
import com.example.venue.payment.dto.PaymentAdminOverviewResponse;
import com.example.venue.payment.dto.PaymentAdminQuery;
import com.example.venue.payment.dto.PaymentAttemptStatus;
import com.example.venue.payment.dto.PaymentCallbackEvidenceView;
import com.example.venue.payment.dto.PaymentProviderOperationType;
import com.example.venue.payment.dto.ProviderStatementItem;
import com.example.venue.payment.dto.ProviderStatementQuery;
import com.example.venue.payment.dto.ReconciliationRunResponse;
import com.example.venue.payment.entity.FinanceReconciliationItem;
import com.example.venue.payment.entity.PaymentAttempt;
import com.example.venue.payment.entity.PaymentProviderOperationLog;
import com.example.venue.payment.mapper.FinanceReconciliationItemMapper;
import com.example.venue.payment.mapper.PaymentAttemptMapper;
import com.example.venue.payment.provider.PaymentProvider;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import com.example.venue.payment.provider.PaymentProviderEvidenceMirror;
import com.example.venue.payment.provider.PaymentProviderOptionResponse;
import com.example.venue.payment.provider.PaymentProviderRouter;
import com.example.venue.payment.provider.alipay.AlipaySandboxException;
import com.example.venue.security.SecurityContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class FinanceReconciliationService {
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");
    private static final String STATEMENT_FAILURE_CODE = "ALIPAY_BILL_PARSE_FAILED";
    private static final String STATEMENT_FAILURE_MESSAGE = "账单获取或解析失败，请稍后重试";
    private static final String ALIPAY_SANDBOX_PROVIDER_KEY = "payment.alipay.sandbox";
    private static final String ALIPAY_SANDBOX_PROVIDER_ENV = "SANDBOX";
    private static final String ALIPAY_SANDBOX_PROVIDER_NAME = "ALIPAY";
    private static final String ALIPAY_SANDBOX_CONFIG_VERSION = "ALIPAY_SANDBOX_V1";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Set<String> STABLE_STATEMENT_FAILURE_CODES = Set.of(
        "ALIPAY_BILL_NOT_READY",
        "ALIPAY_BILL_DOWNLOAD_REJECTED",
        "ALIPAY_BILL_SCHEMA_UNSUPPORTED",
        STATEMENT_FAILURE_CODE);

    private final PaymentRecordMapper paymentRecordMapper;
    private final PaymentAttemptMapper paymentAttemptMapper;
    private final PaymentCallbackLogMapper paymentCallbackLogMapper;
    private final FinanceReconciliationItemMapper reconciliationMapper;
    private final PaymentProviderRouter paymentProviderRouter;
    private final PaymentCallbackLogService callbackLogService;
    private final FinanceReconciliationPersistenceService persistenceService;
    private final PaymentProviderEvidenceMirror paymentProviderEvidenceMirror;
    private final Clock clock;

    @Autowired
    public FinanceReconciliationService(PaymentRecordMapper paymentRecordMapper,
                                        PaymentAttemptMapper paymentAttemptMapper,
                                        PaymentCallbackLogMapper paymentCallbackLogMapper,
                                        FinanceReconciliationItemMapper reconciliationMapper,
                                        PaymentProviderRouter paymentProviderRouter,
                                        PaymentCallbackLogService callbackLogService,
                                        FinanceReconciliationPersistenceService persistenceService,
                                        PaymentProviderEvidenceMirror paymentProviderEvidenceMirror) {
        this(paymentRecordMapper, paymentAttemptMapper, paymentCallbackLogMapper, reconciliationMapper,
            paymentProviderRouter, callbackLogService, persistenceService, paymentProviderEvidenceMirror,
            Clock.system(SHANGHAI));
    }

    public FinanceReconciliationService(PaymentRecordMapper paymentRecordMapper,
                                        PaymentAttemptMapper paymentAttemptMapper,
                                        PaymentCallbackLogMapper paymentCallbackLogMapper,
                                        FinanceReconciliationItemMapper reconciliationMapper,
                                        PaymentProviderRouter paymentProviderRouter,
                                        PaymentCallbackLogService callbackLogService,
                                        FinanceReconciliationPersistenceService persistenceService,
                                        PaymentProviderEvidenceMirror paymentProviderEvidenceMirror,
                                        Clock clock) {
        this.paymentRecordMapper = paymentRecordMapper;
        this.paymentAttemptMapper = paymentAttemptMapper;
        this.paymentCallbackLogMapper = paymentCallbackLogMapper;
        this.reconciliationMapper = reconciliationMapper;
        this.paymentProviderRouter = paymentProviderRouter;
        this.callbackLogService = callbackLogService;
        this.persistenceService = persistenceService;
        this.paymentProviderEvidenceMirror = paymentProviderEvidenceMirror;
        this.clock = clock;
    }

    public ReconciliationRunResponse generate(LocalDate reconcileDate, String providerEnv, String providerName) {
        LocalDate date = reconcileDate == null ? LocalDate.now(clock.withZone(SHANGHAI)) : reconcileDate;
        PaymentProvider provider = resolveProvider(providerEnv, providerName);
        PaymentProviderDescriptor descriptor = provider.descriptor();
        long statementStartedAt = System.currentTimeMillis();
        StatementSnapshot snapshot;
        try {
            List<ProviderStatementItem> providerItems = provider.statement(new ProviderStatementQuery(
                date, descriptor.providerEnv(), descriptor.providerName(), List.of()));
            snapshot = snapshot(providerItems);
        } catch (RuntimeException ex) {
            String failureCode = statementFailureCode(ex);
            recordStatementFailure(date, descriptor, failureCode, statementStartedAt);
            if (ex instanceof AlipaySandboxException alipayException
                && failureCode.equals(alipayException.getCode())) {
                throw alipayException;
            }
            throw AlipaySandboxException.config(failureCode);
        }
        recordStatementSuccess(date, descriptor, snapshot, statementStartedAt);
        return persistenceService.persist(date, descriptor, snapshot.paymentItems());
    }

    @Transactional
    public FinanceReconciliationItem adjust(Long id, AdjustReconciliationRequest request) {
        FinanceReconciliationItem item = reconciliationMapper.selectById(id);
        if (item == null || Integer.valueOf(1).equals(item.deleted)) {
            throw BusinessException.notFound("对账明细不存在");
        }
        if (request == null || request.adjustmentReason == null || request.adjustmentReason.isBlank()) {
            throw BusinessException.badRequest("调整原因不能为空");
        }
        if (request.adjustmentAmount == null) {
            throw BusinessException.badRequest("调整金额不能为空");
        }
        item.status = "ADJUSTED";
        item.adjustmentAmount = money(request.adjustmentAmount);
        item.adjustmentReason = request.adjustmentReason;
        item.operatorId = SecurityContext.currentUserId();
        item.updateTime = LocalDateTime.now();
        reconciliationMapper.updateById(item);
        return item;
    }

    public PaymentAdminOverviewResponse overview(PaymentAdminQuery query) {
        ResolvedAdminQuery resolved = resolvedQuery(query);
        PaymentAdminQuery safeQuery = resolved.query();
        PaymentAdminOverviewResponse response = PaymentAdminOverviewResponse.empty(resolved.descriptor());
        List<PaymentRecord> successRecords = paymentRecordMapper.selectList(paymentRecordOverviewWrapper(safeQuery));
        for (PaymentRecord record : successRecords) {
            response.successAmount = response.successAmount.add(money(record.payAmount));
            response.feeAmount = response.feeAmount.add(money(record.feeAmount));
            response.netAmount = response.netAmount.add(money(record.netAmount));
        }

        List<PaymentAttempt> attempts = paymentAttemptMapper.selectList(paymentAttemptOverviewWrapper(safeQuery));
        Map<String, FailureReasonMetric> failureReasons = new LinkedHashMap<>();
        for (PaymentAttempt attempt : attempts) {
            if (PaymentAttemptStatus.SUCCEEDED.name().equals(attempt.status)) {
                response.successCount++;
            } else if (PaymentAttemptStatus.FAILED.name().equals(attempt.status)) {
                response.failedCount++;
                recordFailureReason(failureReasons, attempt);
            } else if (PaymentAttemptStatus.CANCELED.name().equals(attempt.status)) {
                response.canceledCount++;
                recordFailureReason(failureReasons, attempt);
            } else if (PaymentAttemptStatus.TIMEOUT.name().equals(attempt.status)) {
                response.timeoutCount++;
                recordFailureReason(failureReasons, attempt);
            }
        }
        response.failureReasons = new ArrayList<>(failureReasons.values());
        return response;
    }

    public PageResult<PaymentAttempt> attempts(PaymentAdminQuery query) {
        PaymentAdminQuery safeQuery = resolvedQuery(query).query();
        Page<PaymentAttempt> page = new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize());
        IPage<PaymentAttempt> result = paymentAttemptMapper.selectAdminPage(page, safeQuery.normalizedProviderEnv(),
            safeQuery.normalizedProviderName(), safeQuery.status, safeQuery.orderNo, safeQuery.venueId,
            safeQuery.startTime, safeQuery.endTime);
        return PageResult.of(result.getRecords(), result.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    public PageResult<PaymentCallbackEvidenceView> callbacks(PaymentAdminQuery query) {
        PaymentAdminQuery safeQuery = resolvedQuery(query).query();
        IPage<PaymentCallbackLog> page = paymentCallbackLogMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            callbackLogWrapper(safeQuery));
        List<PaymentCallbackEvidenceView> records = page.getRecords().stream()
            .map(PaymentCallbackEvidenceView::from)
            .toList();
        return PageResult.of(records, page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    public PageResult<FinanceReconciliationItem> reconciliationItems(PaymentAdminQuery query) {
        PaymentAdminQuery safeQuery = resolvedQuery(query).query();
        Page<FinanceReconciliationItem> page = new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize());
        IPage<FinanceReconciliationItem> result = reconciliationMapper.selectAdminPage(
            page, safeQuery.normalizedProviderEnv(), safeQuery.normalizedProviderName(),
            safeQuery.status, safeQuery.orderNo, safeQuery.venueId,
            safeQuery.startTime, safeQuery.endTime);
        return PageResult.of(result.getRecords(), result.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    public List<PaymentProviderOptionResponse> providerOptions() {
        String defaultProviderKey = paymentProviderRouter.requireDefault().descriptor().providerKey();
        return paymentProviderRouter.catalog().stream()
            .map(descriptor -> new PaymentProviderOptionResponse(
                descriptor.providerKey(),
                descriptor.providerEnv(),
                descriptor.providerName(),
                descriptor.displayName(),
                descriptor.configVersion(),
                descriptor.enabled(),
                Objects.equals(defaultProviderKey, descriptor.providerKey())))
            .toList();
    }

    private QueryWrapper<PaymentRecord> paymentRecordOverviewWrapper(PaymentAdminQuery query) {
        QueryWrapper<PaymentRecord> wrapper = new QueryWrapper<PaymentRecord>()
            .eq("provider_env", query.normalizedProviderEnv())
            .eq("provider_name", query.normalizedProviderName())
            .eq("status", "SUCCESS");
        addTimeRange(wrapper, "pay_time", query.startTime, query.endTime);
        return wrapper;
    }

    private QueryWrapper<PaymentAttempt> paymentAttemptOverviewWrapper(PaymentAdminQuery query) {
        QueryWrapper<PaymentAttempt> wrapper = new QueryWrapper<PaymentAttempt>()
            .eq("deleted", 0)
            .eq("provider_env", query.normalizedProviderEnv())
            .eq("provider_name", query.normalizedProviderName())
            .in("status", List.of(PaymentAttemptStatus.SUCCEEDED.name(), PaymentAttemptStatus.FAILED.name(),
                PaymentAttemptStatus.CANCELED.name(), PaymentAttemptStatus.TIMEOUT.name()));
        addTimeRange(wrapper, "create_time", query.startTime, query.endTime);
        return wrapper;
    }

    private static final Set<String> CALLBACK_EVIDENCE_EXCLUDED_PROPERTIES =
        Set.of("payload", "requestSummaryJson", "responseSummaryJson", "metadataJson", "idempotencyKey");

    private QueryWrapper<PaymentCallbackLog> callbackLogWrapper(PaymentAdminQuery query) {
        QueryWrapper<PaymentCallbackLog> wrapper = new QueryWrapper<PaymentCallbackLog>()
            .select(PaymentCallbackLog.class,
                column -> !CALLBACK_EVIDENCE_EXCLUDED_PROPERTIES.contains(column.getProperty()))
            .eq("provider_env", query.normalizedProviderEnv())
            .eq("provider_name", query.normalizedProviderName());
        if (query.status != null && !query.status.isBlank()) {
            wrapper.eq("status", query.status);
        }
        if (query.orderNo != null && !query.orderNo.isBlank()) {
            wrapper.like("order_no", query.orderNo);
        }
        addTimeRange(wrapper, "create_time", query.startTime, query.endTime);
        return wrapper.orderByDesc("id");
    }

    private <T> void addTimeRange(QueryWrapper<T> wrapper, String column,
                                  LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime != null) {
            wrapper.ge(column, startTime);
        }
        if (endTime != null) {
            wrapper.lt(column, endTime);
        }
    }

    private void recordFailureReason(Map<String, FailureReasonMetric> metrics, PaymentAttempt attempt) {
        String code = firstNonBlank(attempt.failureCode, attempt.status);
        String message = attempt.failureMessage;
        String key = code + "\n" + (message == null ? "" : message);
        FailureReasonMetric metric = metrics.computeIfAbsent(key, ignored ->
            new FailureReasonMetric(code, message, 0));
        metric.count++;
    }

    private BigDecimal money(BigDecimal amount) {
        return (amount == null ? ZERO_AMOUNT : amount).setScale(2, RoundingMode.HALF_UP);
    }

    private ResolvedAdminQuery resolvedQuery(PaymentAdminQuery query) {
        PaymentAdminQuery safeQuery = query == null ? new PaymentAdminQuery() : query;
        PaymentProviderDescriptor descriptor = resolveProvider(
            safeQuery.normalizedProviderEnv(), safeQuery.normalizedProviderName()).descriptor();
        safeQuery.providerEnv = descriptor.providerEnv();
        safeQuery.providerName = descriptor.providerName();
        return new ResolvedAdminQuery(safeQuery, descriptor);
    }

    private PaymentProvider resolveProvider(String providerEnv, String providerName) {
        String normalizedEnv = normalizeProviderValue(providerEnv);
        String normalizedName = normalizeProviderValue(providerName);
        if (normalizedEnv == null && normalizedName == null) {
            return paymentProviderRouter.requireDefault();
        }
        return paymentProviderRouter.require(normalizedEnv, normalizedName);
    }

    private String normalizeProviderValue(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private record ResolvedAdminQuery(PaymentAdminQuery query, PaymentProviderDescriptor descriptor) {
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private StatementSnapshot snapshot(List<ProviderStatementItem> providerItems) {
        if (providerItems == null) {
            throw AlipaySandboxException.config(STATEMENT_FAILURE_CODE);
        }
        List<ProviderStatementItem.ImmutableSnapshot> paymentItems = new ArrayList<>();
        long skippedNonPaymentCount = 0;
        long missingFeeCount = 0;
        for (ProviderStatementItem item : providerItems) {
            if (item == null) {
                throw AlipaySandboxException.config(STATEMENT_FAILURE_CODE);
            }
            if ("PAYMENT".equals(item.businessType)) {
                ProviderStatementItem.ImmutableSnapshot snapshot = item.immutableSnapshot();
                paymentItems.add(snapshot);
                if ("FEE_AMOUNT_MISSING".equals(snapshot.dataGap())) {
                    missingFeeCount++;
                }
            } else if ("REFUND_OR_VOID".equals(item.businessType)) {
                skippedNonPaymentCount++;
            } else {
                throw AlipaySandboxException.config(STATEMENT_FAILURE_CODE);
            }
        }
        return new StatementSnapshot(List.copyOf(paymentItems), providerItems.size(), skippedNonPaymentCount, missingFeeCount);
    }

    private String statementFailureCode(RuntimeException exception) {
        if (exception instanceof AlipaySandboxException alipayException
            && STABLE_STATEMENT_FAILURE_CODES.contains(alipayException.getCode())) {
            return alipayException.getCode();
        }
        return STATEMENT_FAILURE_CODE;
    }

    private void recordStatementSuccess(LocalDate date, PaymentProviderDescriptor descriptor,
                                        StatementSnapshot snapshot, long startedAtMillis) {
        String summary = "{\"providerItemCount\":" + snapshot.providerItemCount()
            + ",\"skippedNonPaymentCount\":" + snapshot.skippedNonPaymentCount()
            + ",\"missingFeeCount\":" + snapshot.missingFeeCount() + "}";
        recordStatementOperation(date, descriptor, "SUCCESS", summary, null, null, startedAtMillis);
    }

    private void recordStatementFailure(LocalDate date, PaymentProviderDescriptor descriptor,
                                        String failureCode, long startedAtMillis) {
        recordStatementOperation(date, descriptor, "FAILED", null, failureCode, STATEMENT_FAILURE_MESSAGE,
            startedAtMillis);
    }

    private void recordStatementOperation(LocalDate date, PaymentProviderDescriptor descriptor, String status,
                                          String responseSummaryJson, String failureCode, String failureMessage,
                                          long startedAtMillis) {
        PaymentProviderOperationLog log = new PaymentProviderOperationLog();
        log.operationNo = "POP-" + UUID.randomUUID();
        log.operationType = PaymentProviderOperationType.STATEMENT.name();
        log.providerEnv = descriptor.providerEnv();
        log.providerName = descriptor.providerName();
        log.status = status;
        log.requestSummaryJson = "{\"statementDate\":\"" + date + "\",\"providerEnv\":\""
            + descriptor.providerEnv() + "\",\"providerName\":\"" + descriptor.providerName() + "\"}";
        log.responseSummaryJson = responseSummaryJson;
        log.failureCode = failureCode;
        log.failureMessage = failureMessage;
        log.configVersion = descriptor.configVersion();
        log.durationMs = Math.max(0, System.currentTimeMillis() - startedAtMillis);
        log.metadataJson = "{\"source\":\"provider_operation_ledger\"}";
        log.createTime = LocalDateTime.now();
        callbackLogService.recordOperation(log);
        if (isExactAlipaySandboxDescriptor(descriptor)) {
            paymentProviderEvidenceMirror.mirrorImmediately(descriptor, log);
        }
    }

    private boolean isExactAlipaySandboxDescriptor(PaymentProviderDescriptor descriptor) {
        return descriptor != null
            && ALIPAY_SANDBOX_PROVIDER_KEY.equals(descriptor.providerKey())
            && ALIPAY_SANDBOX_PROVIDER_ENV.equals(descriptor.providerEnv())
            && ALIPAY_SANDBOX_PROVIDER_NAME.equals(descriptor.providerName())
            && ALIPAY_SANDBOX_CONFIG_VERSION.equals(descriptor.configVersion());
    }

    private record StatementSnapshot(List<ProviderStatementItem.ImmutableSnapshot> paymentItems,
                                     long providerItemCount,
                                     long skippedNonPaymentCount,
                                     long missingFeeCount) {
    }
}
