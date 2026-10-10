package com.example.venue.payment.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.payment.dto.AdjustReconciliationRequest;
import com.example.venue.payment.dto.PaymentAdminOverviewResponse;
import com.example.venue.payment.dto.PaymentAdminQuery;
import com.example.venue.payment.dto.PaymentCallbackEvidenceView;
import com.example.venue.payment.dto.ReconciliationRunRequest;
import com.example.venue.payment.dto.ReconciliationRunResponse;
import com.example.venue.payment.entity.FinanceReconciliationItem;
import com.example.venue.payment.entity.PaymentAttempt;
import com.example.venue.payment.provider.PaymentProviderOptionResponse;
import com.example.venue.payment.service.FinanceReconciliationService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class PaymentAdminController {
    private final FinanceReconciliationService financeReconciliationService;

    public PaymentAdminController(FinanceReconciliationService financeReconciliationService) {
        this.financeReconciliationService = financeReconciliationService;
    }

    @GetMapping("/api/admin/payments/overview")
    @PreAuthorize("hasAuthority('payment:admin')")
    public Result<PaymentAdminOverviewResponse> overview(@Valid PaymentAdminQuery query) {
        return Result.ok(financeReconciliationService.overview(query));
    }

    @GetMapping("/api/admin/payments/attempts")
    @PreAuthorize("hasAuthority('payment:admin')")
    public Result<PageResult<PaymentAttempt>> attempts(@Valid PaymentAdminQuery query) {
        return Result.ok(financeReconciliationService.attempts(query));
    }

    @GetMapping("/api/admin/payments/callbacks")
    @PreAuthorize("hasAuthority('payment:admin')")
    public Result<PageResult<PaymentCallbackEvidenceView>> callbacks(@Valid PaymentAdminQuery query) {
        return Result.ok(financeReconciliationService.callbacks(query));
    }

    @GetMapping("/api/admin/payments/providers")
    @PreAuthorize("hasAuthority('payment:admin')")
    public Result<List<PaymentProviderOptionResponse>> providers() {
        return Result.ok(financeReconciliationService.providerOptions());
    }

    @AuditLog(module = "财务对账", action = "执行支付对账")
    @PostMapping("/api/admin/finance/reconciliation/run")
    @PreAuthorize("hasAuthority('finance:reconcile')")
    public Result<ReconciliationRunResponse> runReconciliation(@Valid @RequestBody ReconciliationRunRequest request) {
        return Result.ok(financeReconciliationService.generate(request.reconcileDate(),
            request.normalizedProviderEnv(), request.normalizedProviderName()));
    }

    @GetMapping("/api/admin/finance/reconciliation/items")
    @PreAuthorize("hasAuthority('finance:reconcile')")
    public Result<PageResult<FinanceReconciliationItem>> reconciliationItems(@Valid PaymentAdminQuery query) {
        return Result.ok(financeReconciliationService.reconciliationItems(query));
    }

    @AuditLog(module = "财务对账", action = "调整对账明细")
    @PutMapping("/api/admin/finance/reconciliation/items/{id}/adjust")
    @PreAuthorize("hasAuthority('finance:reconcile')")
    public Result<FinanceReconciliationItem> adjustReconciliationItem(
        @PathVariable Long id,
        @Valid @RequestBody AdjustReconciliationRequest request) {
        return Result.ok(financeReconciliationService.adjust(id, request));
    }
}
