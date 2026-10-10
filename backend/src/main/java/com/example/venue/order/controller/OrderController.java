package com.example.venue.order.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.order.dto.RefundApplyRequest;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.entity.RefundRecord;
import com.example.venue.order.service.OrderService;
import com.example.venue.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class OrderController {
    private final OrderService orderService;
    private final PaymentService paymentService;

    public OrderController(OrderService orderService, PaymentService paymentService) {
        this.orderService = orderService;
        this.paymentService = paymentService;
    }

    @GetMapping("/api/orders/my")
    public Result<PageResult<BizOrder>> myOrders(@Valid PageQuery query) {
        return Result.ok(orderService.myOrders(query));
    }

    /**
     * @deprecated 遗留模拟支付端点，调用 legacySimulatePay。
     * 前端已迁移到 createPaymentIntent + simulateDevPayment 两步流程。
     * 保留端点以兼容可能的外部调用，下个版本计划移除。
     */
    @Deprecated
    @AuditLog(module = "订单支付", action = "模拟支付")
    @PostMapping("/api/orders/{id}/pay")
    @PreAuthorize("hasAuthority('order:my')")
    public Result<BizOrder> pay(@PathVariable Long id) {
        return Result.ok(paymentService.legacySimulatePay(id));
    }

    @AuditLog(module = "退款管理", action = "申请退款")
    @PostMapping("/api/orders/{id}/refunds")
    @PreAuthorize("hasAuthority('order:my')")
    public Result<RefundRecord> applyRefund(@PathVariable Long id, @Valid @RequestBody RefundApplyRequest request) {
        return Result.ok(orderService.applyRefund(id, request));
    }

    @GetMapping("/api/admin/orders")
    @PreAuthorize("hasAuthority('order:admin')")
    public Result<PageResult<BizOrder>> adminOrders(@Valid PageQuery query, @RequestParam(required = false) Long venueId) {
        return Result.ok(orderService.adminOrders(query, venueId));
    }

    @GetMapping("/api/admin/refunds")
    @PreAuthorize("hasAuthority('order:admin')")
    public Result<PageResult<RefundRecord>> adminRefunds(@Valid PageQuery query,
                                                         @RequestParam(required = false) Long venueId,
                                                         @RequestParam(required = false) String status) {
        return Result.ok(orderService.adminRefunds(query, venueId, status));
    }

    @AuditLog(module = "退款审核", action = "审核退款")
    @PutMapping("/api/admin/refunds/{id}/approve")
    @PreAuthorize("hasAuthority('order:admin')")
    public Result<Void> approveRefund(@PathVariable Long id, @RequestBody Map<String, String> body) {
        orderService.approveRefund(id, true, body.getOrDefault("reason", "退款审核通过"));
        return Result.ok();
    }

    @AuditLog(module = "退款审核", action = "拒绝退款")
    @PutMapping("/api/admin/refunds/{id}/reject")
    @PreAuthorize("hasAuthority('order:admin')")
    public Result<Void> rejectRefund(@PathVariable Long id, @RequestBody Map<String, String> body) {
        orderService.approveRefund(id, false, body.get("reason"));
        return Result.ok();
    }

    @AuditLog(module = "退款审核", action = "确认退款到账")
    @PutMapping("/api/admin/refunds/{id}/success")
    @PreAuthorize("hasAuthority('order:admin')")
    public Result<Void> refundSuccess(@PathVariable Long id) {
        orderService.markRefundSuccess(id);
        return Result.ok();
    }
}
