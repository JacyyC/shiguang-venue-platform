package com.example.venue.storedvalue.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.BusinessException;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.common.ResultCode;
import com.example.venue.security.SecurityContext;
import com.example.venue.storedvalue.dto.StoredValueRequests;
import com.example.venue.storedvalue.dto.StoredValueResponses;
import com.example.venue.storedvalue.service.StoredValueApiService;
import com.example.venue.storedvalue.service.StoredValueApprovalService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/stored-value")
public class StoredValueAdminController {
    private final StoredValueApiService service;
    private final StoredValueApprovalService approvalService;

    public StoredValueAdminController(StoredValueApiService service,
                                      StoredValueApprovalService approvalService) {
        this.service = service;
        this.approvalService = approvalService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('stored-value:finance:view')")
    public Result<StoredValueResponses.FinanceSummaryResponse> summary() {
        return Result.ok(service.financeSummary());
    }

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('stored-value:finance:view')")
    public Result<List<StoredValueResponses.ProductResponse>> products() {
        return Result.ok(service.products(false));
    }

    @AuditLog(module = "储值", action = "创建 DEV 储值产品")
    @PostMapping("/products")
    @PreAuthorize("hasAuthority('stored-value:policy:manage')")
    public Result<StoredValueResponses.ProductResponse> createProduct(
        @Valid @RequestBody StoredValueRequests.CreateProductRequest request) {
        return Result.ok(service.createDevProduct(request, operatorId()));
    }

    @AuditLog(module = "储值", action = "修改储值产品启停")
    @PutMapping("/products/{productId}/flags")
    @PreAuthorize("hasAuthority('stored-value:policy:manage')")
    public Result<StoredValueResponses.ProductResponse> setProductFlags(
        @PathVariable Long productId,
        @Valid @RequestBody StoredValueRequests.ProductFlagsRequest request) {
        return Result.ok(service.setProductFlags(productId, request, operatorId()));
    }

    @GetMapping("/providers")
    @PreAuthorize("hasAuthority('stored-value:finance:view')")
    public Result<List<StoredValueResponses.ProviderResponse>> providers() {
        return Result.ok(service.providers());
    }

    @AuditLog(module = "储值", action = "记录储值身份核验")
    @PostMapping("/users/{userId}/identity-verifications")
    @PreAuthorize("hasAuthority('stored-value:policy:manage')")
    public Result<Void> verifyIdentity(@PathVariable Long userId,
                                       @Valid @RequestBody StoredValueRequests.IdentityVerificationRequest request) {
        service.verifyIdentity(userId, request, operatorId());
        return Result.ok();
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('stored-value:finance:view')")
    public Result<PageResult<StoredValueResponses.FinanceAccountResponse>> accounts(@Valid PageQuery query) {
        return Result.ok(service.financeAccounts(query));
    }

    @PostMapping("/accounts/{accountId}/freeze")
    @PreAuthorize("hasAuthority('stored-value:account:freeze')")
    public Result<StoredValueResponses.ActionResponse> freeze(
        @PathVariable String accountId, @Valid @RequestBody StoredValueRequests.AccountActionRequest request) {
        return Result.ok(service.freezeAccount(accountId, request, operatorId()));
    }

    @PostMapping("/accounts/{accountId}/unfreeze")
    @PreAuthorize("hasAuthority('stored-value:account:unfreeze')")
    public Result<StoredValueResponses.ApprovalDetailResponse> unfreeze(
        @PathVariable String accountId, @Valid @RequestBody StoredValueRequests.ApprovalReasonRequest request) {
        return Result.ok(approvalService.submitUnfreeze(service.resolveAccountRef(accountId), request.reason));
    }

    @PostMapping("/accounts/{accountId}/begin-closing")
    @PreAuthorize("hasAuthority('stored-value:account:close')")
    public Result<StoredValueResponses.ActionResponse> beginClosing(
        @PathVariable String accountId, @Valid @RequestBody StoredValueRequests.AccountActionRequest request) {
        return Result.ok(service.beginClosing(accountId, request, operatorId()));
    }

    @PostMapping("/accounts/{accountId}/close")
    @PreAuthorize("hasAuthority('stored-value:account:close')")
    public Result<StoredValueResponses.ActionResponse> close(
        @PathVariable String accountId, @Valid @RequestBody StoredValueRequests.AccountActionRequest request) {
        return Result.ok(service.closeAccount(accountId, request, operatorId()));
    }

    @GetMapping("/recharges")
    @PreAuthorize("hasAuthority('stored-value:finance:view')")
    public Result<PageResult<StoredValueResponses.FinanceRechargeResponse>> recharges(@Valid PageQuery query) {
        return Result.ok(service.financeRecharges(query));
    }

    @PostMapping("/recharges/{rechargeNo}/refund")
    @PreAuthorize("hasAuthority('stored-value:refund:manage')")
    public Result<StoredValueResponses.ApprovalDetailResponse> refund(
        @PathVariable String rechargeNo,
        @Valid @RequestBody StoredValueRequests.RechargeRefundRequest request) {
        return Result.ok(approvalService.submitRefund(service.resolveRechargeRef(rechargeNo), request.reason));
    }

    @PostMapping("/recharges/{rechargeNo}/disputes")
    @PreAuthorize("hasAuthority('stored-value:dispute:manage')")
    public Result<StoredValueResponses.ActionResponse> dispute(
        @PathVariable String rechargeNo,
        @Valid @RequestBody StoredValueRequests.DisputeRequest request) {
        return Result.ok(service.openDispute(service.resolveRechargeRef(rechargeNo), request, operatorId()));
    }

    @AuditLog(module = "储值", action = "人工恢复储值入账")
    @PostMapping("/recharges/{rechargeNo}/posting-recovery")
    @PreAuthorize("hasAuthority('stored-value:recharge:execute')")
    public Result<StoredValueResponses.ApprovalDetailResponse> recoverPosting(
        @PathVariable String rechargeNo,
        @Valid @RequestBody StoredValueRequests.PostingRecoveryRequest request) {
        return Result.ok(approvalService.submitPostingRecovery(service.resolveRechargeRef(rechargeNo), request.reason));
    }

    @PostMapping("/reconciliation/differences/{differenceId}/adjustment")
    @PreAuthorize("hasAuthority('stored-value:adjustment:execute')")
    public Result<StoredValueResponses.ApprovalDetailResponse> requestAdjustment(
        @PathVariable String differenceId,
        @Valid @RequestBody StoredValueRequests.AdjustmentApprovalRequest request) {
        return Result.ok(approvalService.submitAdjustment(service.resolveDifferenceRef(differenceId), request.amount, request.reason));
    }

    @GetMapping("/approvals")
    @PreAuthorize("hasAnyAuthority('stored-value:recharge:review','stored-value:recharge:execute',"
        + "'stored-value:refund:review','stored-value:refund:manage',"
        + "'stored-value:account:unfreeze:review','stored-value:account:unfreeze',"
        + "'stored-value:adjustment:review','stored-value:adjustment:execute')")
    public Result<PageResult<StoredValueResponses.ApprovalListResponse>> approvals(@Valid PageQuery query) {
        return Result.ok(approvalService.list(query));
    }

    @GetMapping("/approvals/{approvalNo}")
    @PreAuthorize("hasAnyAuthority('stored-value:recharge:review','stored-value:recharge:execute',"
        + "'stored-value:refund:review','stored-value:refund:manage',"
        + "'stored-value:account:unfreeze:review','stored-value:account:unfreeze',"
        + "'stored-value:adjustment:review','stored-value:adjustment:execute')")
    public Result<StoredValueResponses.ApprovalDetailResponse> approval(@PathVariable String approvalNo) {
        return Result.ok(approvalService.detail(approvalNo));
    }

    @PostMapping("/approvals/{approvalNo}/approve")
    @PreAuthorize("hasAnyAuthority('stored-value:recharge:review','stored-value:refund:review',"
        + "'stored-value:account:unfreeze:review','stored-value:adjustment:review')")
    public Result<StoredValueResponses.ApprovalDetailResponse> approve(
        @PathVariable String approvalNo,
        @Valid @RequestBody StoredValueRequests.ApprovalReasonRequest request) {
        return Result.ok(approvalService.approve(approvalNo, request.reason));
    }

    @PostMapping("/approvals/{approvalNo}/reject")
    @PreAuthorize("hasAnyAuthority('stored-value:recharge:review','stored-value:refund:review',"
        + "'stored-value:account:unfreeze:review','stored-value:adjustment:review')")
    public Result<StoredValueResponses.ApprovalDetailResponse> reject(
        @PathVariable String approvalNo,
        @Valid @RequestBody StoredValueRequests.ApprovalReasonRequest request) {
        return Result.ok(approvalService.reject(approvalNo, request.reason));
    }

    @PostMapping("/approvals/{approvalNo}/execute")
    @PreAuthorize("hasAnyAuthority('stored-value:recharge:execute','stored-value:refund:manage',"
        + "'stored-value:account:unfreeze','stored-value:adjustment:execute')")
    public Result<StoredValueResponses.ApprovalDetailResponse> execute(@PathVariable String approvalNo) {
        return Result.ok(approvalService.execute(approvalNo));
    }

    @PostMapping("/approvals/{approvalNo}/revoke")
    @PreAuthorize("hasAnyAuthority('stored-value:recharge:execute','stored-value:refund:manage',"
        + "'stored-value:account:unfreeze','stored-value:adjustment:execute')")
    public Result<StoredValueResponses.ApprovalDetailResponse> revoke(
        @PathVariable String approvalNo,
        @Valid @RequestBody StoredValueRequests.ApprovalReasonRequest request) {
        return Result.ok(approvalService.revoke(approvalNo, request.reason));
    }

    @GetMapping("/ledger")
    @PreAuthorize("hasAuthority('stored-value:finance:view')")
    public Result<PageResult<StoredValueResponses.FinanceLedgerResponse>> ledger(
        @Valid PageQuery query,
        @RequestParam(name = "accountRef", required = false) String accountRef) {
        return Result.ok(service.financeLedger(query, accountRef));
    }

    @GetMapping("/order-stored-facts")
    @PreAuthorize("hasAuthority('stored-value:finance:view')")
    public Result<StoredValueResponses.OrderStoredFactsResponse> orderStoredFacts(
        @RequestParam("orderNo") String orderNo) {
        return Result.ok(service.orderStoredFacts(orderNo));
    }

    @GetMapping("/reconciliation/runs")
    @PreAuthorize("hasAuthority('stored-value:finance:view')")
    public Result<PageResult<StoredValueResponses.ReconciliationRunResponse>> reconciliationRuns(
        @Valid PageQuery query) {
        return Result.ok(service.reconciliationRuns(query));
    }

    @GetMapping("/reconciliation/differences")
    @PreAuthorize("hasAuthority('stored-value:finance:view')")
    public Result<PageResult<StoredValueResponses.ReconciliationDifferenceResponse>> reconciliationDifferences(
        @Valid PageQuery query) {
        return Result.ok(service.reconciliationDifferences(query));
    }

    @AuditLog(module = "储值", action = "执行储值对账")
    @PostMapping("/reconciliation/run")
    @PreAuthorize("hasAuthority('stored-value:reconciliation:run')")
    public Result<StoredValueResponses.ReconciliationRunResponse> runReconciliation(
        @Valid @RequestBody StoredValueRequests.ReconciliationRequest request) {
        return Result.ok(service.runReconciliation(request, operatorId()));
    }

    private Long operatorId() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }
}
