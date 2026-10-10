package com.example.venue.storedvalue.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.ResultCode;
import com.example.venue.security.SecurityContext;
import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.dto.StoredValueRequests;
import com.example.venue.storedvalue.dto.StoredValueResponses;
import com.example.venue.storedvalue.provider.StoredValueProviderDescriptor;
import com.example.venue.storedvalue.provider.StoredValueProviderRouter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * HTTP-facing stored-value facade. It owns protocol-facing composition,
 * action normalization, and reference resolution; query DTOs and writes remain
 * in their narrower domain services.
 */
@Service
public class StoredValueApiService {
    private final JdbcTemplate jdbc;
    private final StoredValueConfigService configService;
    private final StoredValueEligibilityService eligibilityService;
    private final StoredValueRechargeService rechargeService;
    private final StoredValueOrderPaymentService orderPaymentService;
    private final StoredValueReconciliationService reconciliationService;
    private final StoredValueConfigurationService configurationService;
    private final StoredValueProviderRouter providerRouter;
    private final StoredValueAccountService accountService;
    private final StoredValueUserQueryService userQueryService;
    private final StoredValueFinanceQueryService financeQueryService;

    public StoredValueApiService(JdbcTemplate jdbc,
                                 StoredValueConfigService configService,
                                 StoredValueEligibilityService eligibilityService,
                                 StoredValueRechargeService rechargeService,
                                 StoredValueOrderPaymentService orderPaymentService,
                                 StoredValueReconciliationService reconciliationService,
                                 StoredValueConfigurationService configurationService,
                                 StoredValueProviderRouter providerRouter,
                                 StoredValueAccountService accountService,
                                 StoredValueUserQueryService userQueryService,
                                 StoredValueFinanceQueryService financeQueryService) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.eligibilityService = eligibilityService;
        this.rechargeService = rechargeService;
        this.orderPaymentService = orderPaymentService;
        this.reconciliationService = reconciliationService;
        this.configurationService = configurationService;
        this.providerRouter = providerRouter;
        this.accountService = accountService;
        this.userQueryService = userQueryService;
        this.financeQueryService = financeQueryService;
    }

    public StoredValueResponses.SelfResponse self() {
        return userQueryService.self();
    }

    public List<StoredValueResponses.BonusBatchResponse> bonusBatches() {
        return userQueryService.bonusBatches();
    }

    public PageResult<StoredValueResponses.RechargeResponse> myRecharges(PageQuery query) {
        return userQueryService.myRecharges(query);
    }

    public StoredValueResponses.RechargeResponse myRecharge(String rechargeNo) {
        return userQueryService.myRecharge(rechargeNo);
    }

    public StoredValueResponses.RechargeResponse createRecharge(StoredValueRequests.CreateRechargeRequest request) {
        Long userId = currentUserId();
        StoredValueRechargeResult result = rechargeService.create(new StoredValueCommands.CreateRecharge(
            userId, request.productId, request.principalAmount, request.currency,
            request.clientRequestId, blankToNull(request.providerKey), "USER"));
        return userQueryService.rechargeForUser(result.rechargeNo(), userId);
    }

    public StoredValueResponses.RechargeResponse confirmDevCallback(String rechargeNo,
                                                                     StoredValueRequests.DevCallbackRequest request) {
        Long userId = currentUserId();
        String status = request.status == null ? "" : request.status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("SUCCESS", "FAILED").contains(status)) {
            throw BusinessException.badRequest("DEV 回调状态只能是 SUCCESS 或 FAILED");
        }
        rechargeService.confirmDevCallback(userId, rechargeNo, status);
        return userQueryService.rechargeForUser(rechargeNo, userId);
    }

    public PageResult<StoredValueResponses.LedgerResponse> myLedger(PageQuery query) {
        return userQueryService.myLedger(query);
    }

    public StoredValueResponses.OrderPaymentOptionResponse orderPaymentOption(Long orderId) {
        return userQueryService.orderPaymentOption(orderId);
    }

    public StoredValueResponses.OrderPaymentResponse payOrder(Long orderId,
                                                               StoredValueRequests.OrderPaymentRequest request) {
        Long userId = currentUserId();
        StoredValueOrderPaymentResult result = orderPaymentService.pay(new StoredValueCommands.OrderPayment(
            orderId, userId, request.productId, request.idempotencyKey, "USER"));
        return new StoredValueResponses.OrderPaymentResponse(result.paymentId(), result.paymentNo(),
            result.orderId(), result.status(), result.amount(), result.principalAmount(),
            result.bonusAmount(), result.replayed());
    }

    public StoredValueResponses.OrderStoredValueStatusResponse orderStoredValueStatus(Long orderId) {
        return userQueryService.orderStoredValueStatus(orderId);
    }

    public List<StoredValueResponses.ProductResponse> products(boolean enabledOnly) {
        return userQueryService.products(enabledOnly);
    }

    public StoredValueResponses.ProductResponse createDevProduct(
        StoredValueRequests.CreateProductRequest request, Long operatorId) {
        StoredValueProductSnapshot product = configurationService.enableDevProduct(
            request.productCode, request.productName, operatorId);
        return userQueryService.product(product.id());
    }

    public StoredValueResponses.ProductResponse setProductFlags(Long productId,
                                                                 StoredValueRequests.ProductFlagsRequest request,
                                                                 Long operatorId) {
        StoredValueProductSnapshot product = configurationService.setProductFlags(
            productId, request.rechargeEnabled, request.spendEnabled, request.status, operatorId);
        return userQueryService.product(product.id());
    }

    public List<StoredValueResponses.ProviderResponse> providers() {
        return providerRouter.catalog().stream().map(this::provider).toList();
    }

    public void verifyIdentity(Long userId, StoredValueRequests.IdentityVerificationRequest request,
                               Long operatorId) {
        String environment = request.environment == null ? "DEV"
            : request.environment.trim().toUpperCase(Locale.ROOT);
        LocalDateTime expiresAt = request.expiresOn == null
            ? null : request.expiresOn.plusDays(1).atStartOfDay().minusNanos(1);
        eligibilityService.recordDevVerification(userId, environment, request.evidenceDigest,
            // DATETIME(0) must not round an immediate verification into the future.
            LocalDateTime.now().withNano(0), expiresAt, operatorId);
    }

    public StoredValueResponses.FinanceSummaryResponse financeSummary() {
        return financeQueryService.financeSummary();
    }

    public PageResult<StoredValueResponses.FinanceAccountResponse> financeAccounts(PageQuery query) {
        return financeQueryService.financeAccounts(query);
    }

    public PageResult<StoredValueResponses.FinanceRechargeResponse> financeRecharges(PageQuery query) {
        return financeQueryService.financeRecharges(query);
    }

    public PageResult<StoredValueResponses.FinanceLedgerResponse> financeLedger(PageQuery query, String accountRef) {
        return financeQueryService.financeLedger(query, accountRef);
    }

    public StoredValueResponses.OrderStoredFactsResponse orderStoredFacts(String orderNo) {
        return financeQueryService.orderStoredFacts(orderNo);
    }

    public PageResult<StoredValueResponses.ReconciliationRunResponse> reconciliationRuns(PageQuery query) {
        return financeQueryService.reconciliationRuns(query);
    }

    public PageResult<StoredValueResponses.ReconciliationDifferenceResponse> reconciliationDifferences(PageQuery query) {
        return financeQueryService.reconciliationDifferences(query);
    }

    public StoredValueResponses.ReconciliationRunResponse runReconciliation(
        StoredValueRequests.ReconciliationRequest request, Long operatorId) {
        StoredValueReconciliationResult result = reconciliationService.run(request.reconcileDate, operatorId);
        return financeQueryService.reconciliationRun(result.runId());
    }

    public StoredValueResponses.ActionResponse openDispute(String rechargeNo,
                                                            StoredValueRequests.DisputeRequest request,
                                                            Long operatorId) {
        rechargeService.dispute(new StoredValueCommands.Dispute(rechargeNo, request.providerCaseId,
            request.amount, operatorId, request.reason));
        return new StoredValueResponses.ActionResponse("UNDER_REVIEW", "争议已进入处置流程");
    }

    public StoredValueResponses.ActionResponse freezeAccount(String accountRef,
                                                              StoredValueRequests.AccountActionRequest request,
                                                              Long operatorId) {
        accountService.freezeAccount(resolveAccountId(accountRef), operatorId, request.reason, request.businessRef);
        return new StoredValueResponses.ActionResponse("FROZEN", "账户已冻结");
    }

    public StoredValueResponses.ActionResponse beginClosing(String accountRef,
                                                             StoredValueRequests.AccountActionRequest request,
                                                             Long operatorId) {
        accountService.beginClosing(resolveAccountId(accountRef), operatorId, request.reason, request.businessRef);
        return new StoredValueResponses.ActionResponse("CLOSING", "账户已进入注销流程");
    }

    public StoredValueResponses.ActionResponse closeAccount(String accountRef,
                                                            StoredValueRequests.AccountActionRequest request,
                                                            Long operatorId) {
        accountService.close(resolveAccountId(accountRef), operatorId, request.reason, request.businessRef);
        return new StoredValueResponses.ActionResponse("CLOSED", "账户已注销");
    }

    public Long resolveAccountRef(String accountRef) {
        return resolveAccountId(accountRef);
    }

    public String resolveRechargeRef(String rechargeRef) {
        if (rechargeRef == null || rechargeRef.isBlank()) {
            throw BusinessException.badRequest("充值引用不能为空");
        }
        if (rechargeRef.startsWith("SVR-REF-")) {
            String digest = rechargeRef.substring("SVR-REF-".length()).toLowerCase(Locale.ROOT);
            List<String> rows = jdbc.query("""
                SELECT recharge_no FROM stored_value_recharge
                WHERE LEFT(SHA2(CONCAT('stored-value:recharge:', recharge_no), 256), 24) = ?
                """, (rs, n) -> rs.getString(1), digest);
            if (rows.size() == 1) {
                return rows.get(0);
            }
            if (rows.size() > 1) {
                throw BusinessException.conflict("充值引用不唯一");
            }
        }
        String resolved = jdbc.query("SELECT recharge_no FROM stored_value_recharge WHERE recharge_no = ?",
            rs -> rs.next() ? rs.getString(1) : null, rechargeRef);
        if (resolved == null) {
            throw BusinessException.notFound("充值不存在");
        }
        return resolved;
    }

    public Long resolveDifferenceRef(String differenceRef) {
        if (differenceRef == null || differenceRef.isBlank()) {
            throw BusinessException.badRequest("对账差异引用不能为空");
        }
        if (differenceRef.matches("\\d+")) {
            Long differenceId = Long.valueOf(differenceRef);
            if (!differenceExists(differenceId)) {
                throw BusinessException.notFound("对账差异不存在");
            }
            return differenceId;
        }
        if (differenceRef.startsWith("SVD-")) {
            String digest = differenceRef.substring("SVD-".length()).toLowerCase(Locale.ROOT);
            Long differenceId = jdbc.query("""
                SELECT id FROM stored_value_reconciliation_difference
                WHERE LEFT(SHA2(CONCAT('stored-value:difference:', id), 256), 24) = ?
                """, rs -> rs.next() ? rs.getLong(1) : null, digest);
            if (differenceId == null) {
                throw BusinessException.notFound("对账差异不存在");
            }
            return differenceId;
        }
        Long id = jdbc.query("SELECT id FROM stored_value_reconciliation_difference WHERE difference_no = ?",
            rs -> rs.next() ? rs.getLong(1) : null, differenceRef);
        if (id == null) {
            throw BusinessException.notFound("对账差异不存在");
        }
        return id;
    }

    private boolean differenceExists(Long differenceId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stored_value_reconciliation_difference WHERE id = ?", Integer.class, differenceId);
        return count != null && count == 1;
    }

    private StoredValueResponses.ProviderResponse provider(StoredValueProviderDescriptor descriptor) {
        return new StoredValueResponses.ProviderResponse(descriptor.providerKey(), descriptor.providerEnv(),
            descriptor.providerName(), descriptor.configVersion(), descriptor.enabled());
    }

    private Long currentUserId() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long resolveAccountId(String accountRef) {
        if (accountRef == null || accountRef.isBlank()) {
            throw BusinessException.badRequest("账户引用不能为空");
        }
        if (accountRef.matches("\\d+")) {
            return Long.valueOf(accountRef);
        }
        if (!accountRef.startsWith("SVA-")) {
            throw BusinessException.notFound("储值账户不存在");
        }
        String digest = accountRef.substring("SVA-".length()).toLowerCase(Locale.ROOT);
        Long accountId = jdbc.query("""
            SELECT id FROM stored_value_account
            WHERE LEFT(SHA2(CONCAT('stored-value:account:', id), 256), 24) = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, digest);
        if (accountId == null) {
            throw BusinessException.notFound("储值账户不存在");
        }
        return accountId;
    }

}
