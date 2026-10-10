package com.example.venue.storedvalue.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.storedvalue.dto.StoredValueRequests;
import com.example.venue.storedvalue.dto.StoredValueResponses;
import com.example.venue.storedvalue.service.StoredValueApiService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stored-value")
public class StoredValueController {
    private final StoredValueApiService service;

    public StoredValueController(StoredValueApiService service) {
        this.service = service;
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('stored-value:me')")
    public Result<StoredValueResponses.SelfResponse> self() {
        return Result.ok(service.self());
    }

    @GetMapping("/me/bonus-batches")
    @PreAuthorize("hasAuthority('stored-value:me')")
    public Result<List<StoredValueResponses.BonusBatchResponse>> bonusBatches() {
        return Result.ok(service.bonusBatches());
    }

    @GetMapping("/me/recharges")
    @PreAuthorize("hasAuthority('stored-value:me')")
    public Result<PageResult<StoredValueResponses.RechargeResponse>> recharges(@Valid PageQuery query) {
        return Result.ok(service.myRecharges(query));
    }

    @GetMapping("/me/recharges/{rechargeNo}")
    @PreAuthorize("hasAuthority('stored-value:me')")
    public Result<StoredValueResponses.RechargeResponse> recharge(@PathVariable String rechargeNo) {
        return Result.ok(service.myRecharge(rechargeNo));
    }

    @AuditLog(module = "储值", action = "发起储值充值")
    @PostMapping("/me/recharges")
    @PreAuthorize("hasAuthority('stored-value:me')")
    public Result<StoredValueResponses.RechargeResponse> createRecharge(
        @Valid @RequestBody StoredValueRequests.CreateRechargeRequest request) {
        return Result.ok(service.createRecharge(request));
    }

    @AuditLog(module = "储值", action = "确认 DEV 储值回调")
    @PostMapping({
        "/me/recharges/{rechargeNo}/dev-callback",
        "/dev/recharges/{rechargeNo}/callback",
        "/providers/dev/recharges/{rechargeNo}/callback"
    })
    @PreAuthorize("hasAuthority('stored-value:me')")
    public Result<StoredValueResponses.RechargeResponse> confirmDevCallback(
        @PathVariable String rechargeNo,
        @Valid @RequestBody StoredValueRequests.DevCallbackRequest request) {
        return Result.ok(service.confirmDevCallback(rechargeNo, request));
    }

    @GetMapping("/me/ledger")
    @PreAuthorize("hasAuthority('stored-value:me')")
    public Result<PageResult<StoredValueResponses.LedgerResponse>> ledger(@Valid PageQuery query) {
        return Result.ok(service.myLedger(query));
    }

    @GetMapping("/me/orders/{orderId}/payment-option")
    @PreAuthorize("hasAuthority('stored-value:me')")
    public Result<StoredValueResponses.OrderPaymentOptionResponse> paymentOption(
        @PathVariable Long orderId) {
        return Result.ok(service.orderPaymentOption(orderId));
    }

    @AuditLog(module = "储值", action = "使用储值支付订单")
    @PostMapping("/me/orders/{orderId}/payments")
    @PreAuthorize("hasAuthority('stored-value:me')")
    public Result<StoredValueResponses.OrderPaymentResponse> payOrder(
        @PathVariable Long orderId,
        @Valid @RequestBody StoredValueRequests.OrderPaymentRequest request) {
        return Result.ok(service.payOrder(orderId, request));
    }

    @GetMapping("/me/orders/{orderId}/status")
    @PreAuthorize("hasAuthority('stored-value:me')")
    public Result<StoredValueResponses.OrderStoredValueStatusResponse> orderStatus(
        @PathVariable Long orderId) {
        return Result.ok(service.orderStoredValueStatus(orderId));
    }
}
