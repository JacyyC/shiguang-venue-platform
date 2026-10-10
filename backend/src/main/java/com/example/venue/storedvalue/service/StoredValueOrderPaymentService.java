package com.example.venue.storedvalue.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.MoneyUtils;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.payment.service.OrderLocalSettlementService;
import com.example.venue.payment.service.PaidOrderFact;
import com.example.venue.security.SecurityContext;
import com.example.venue.storedvalue.domain.StoredValueAccountStatus;
import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.domain.StoredValueLedgerBucket;
import com.example.venue.storedvalue.domain.StoredValueLedgerDirection;
import com.example.venue.storedvalue.domain.StoredValueMoney;
import com.example.venue.storedvalue.domain.StoredValuePolicy;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Full, non-mixed stored-value order payments. */
@Service
public class StoredValueOrderPaymentService {
    public static final String PAYMENT_SOURCE = "ORDER_PAYMENT";
    public static final String REFUND_SOURCE = "ORDER_REFUND";
    public static final String PAYMENT_TYPE = OrderLocalSettlementService.STORED_VALUE_TOOL;

    private final JdbcTemplate jdbc;
    private final BizOrderMapper orderMapper;
    private final StoredValueConfigService configService;
    private final StoredValueEligibilityService eligibilityService;
    private final StoredValueAccountService accountService;
    private final StoredValueLedgerService ledgerService;
    private final OrderLocalSettlementService orderSettlementService;

    public StoredValueOrderPaymentService(JdbcTemplate jdbc,
                                          BizOrderMapper orderMapper,
                                          StoredValueConfigService configService,
                                          StoredValueEligibilityService eligibilityService,
                                          StoredValueAccountService accountService,
                                          StoredValueLedgerService ledgerService,
                                          OrderLocalSettlementService orderSettlementService) {
        this.jdbc = jdbc;
        this.orderMapper = orderMapper;
        this.configService = configService;
        this.eligibilityService = eligibilityService;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.orderSettlementService = orderSettlementService;
    }

    public StoredValueOrderPaymentResult pay(Long orderId, Long productId, String idempotencyKey) {
        return pay(new StoredValueCommands.OrderPayment(orderId, SecurityContext.currentUserId(), productId,
            idempotencyKey, "USER"));
    }

    @Transactional
    public StoredValueOrderPaymentResult pay(StoredValueCommands.OrderPayment command) {
        validateCommand(command);
        // The order is always the first business row lock. External callbacks use the same lock.
        BizOrder order = orderMapper.selectByIdForUpdate(command.orderId());
        if (order == null || !order.userId.equals(command.userId())) {
            throw BusinessException.notFound("订单不存在");
        }
        BigDecimal payable = MoneyUtils.amountOrDefault(order.payableAmount, order.totalAmount);
        StoredValueMoney money = StoredValueMoney.of("CNY", payable);
        if (money.amount().signum() <= 0) {
            throw BusinessException.conflict("订单应付金额必须大于零");
        }

        StoredPaymentRow existing = findPaymentForOrder(order.id, true);
        if (existing != null) {
            if (!command.idempotencyKey().equals(existing.idempotencyKey)) {
                throw BusinessException.conflict("订单已经存在其他支付工具事实");
            }
            if (existing.amount.compareTo(money.amount()) != 0) {
                throw BusinessException.conflict("支付幂等键对应不同订单金额");
            }
            if (!command.productId().equals(existing.productId)) {
                throw BusinessException.conflict("支付幂等键对应不同储值产品");
            }
            return toResult(existing, true);
        }
        if (!isPending(order.status)) {
            throw BusinessException.conflict("订单当前状态不可使用储值支付");
        }
        if (order.paymentToolType != null && !order.paymentToolType.isBlank()) {
            throw BusinessException.conflict("订单已经由其他支付工具结算");
        }
        StoredValueProductSnapshot product = configService.requireProduct(command.productId());
        configService.requireSpendable(product);
        eligibilityService.requireEligible(command.userId(), product);

        StoredValueAccountSnapshot account;
        try {
            account = accountService.snapshotForUpdate(command.userId(), product);
        } catch (EmptyResultDataAccessException ex) {
            throw BusinessException.conflict("储值账户不存在或尚未开通");
        }
        if (!StoredValueAccountStatus.NORMAL.name().equals(account.status())) {
            throw BusinessException.forbidden("储值账户当前不允许消费");
        }

        LocalDateTime now = LocalDateTime.now(product.policy().zoneId());
        List<SpendAllocation> allocations = allocate(account, money.amount(), now);
        BigDecimal allocated = allocations.stream().map(SpendAllocation::amount)
            .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        if (allocated.compareTo(money.amount()) != 0) {
            throw BusinessException.conflict("储值余额不足或赠送金已失效");
        }

        String paymentNo = "SVOP-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        jdbc.update("""
            INSERT INTO stored_value_order_payment
              (payment_no, order_id, order_no, user_id, account_id, issuer_id, product_id,
               policy_version, currency, amount, principal_amount, bonus_amount, idempotency_key,
               status, source_type)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CNY', ?, ?, ?, ?, 'POSTING', ?)
            """, paymentNo, order.id, order.orderNo, order.userId, account.id(), account.issuerId(),
            account.productId(), product.policy().version(), money.amount(),
            allocations.stream().filter(item -> item.bucket.equals(StoredValueLedgerBucket.PRINCIPAL_AVAILABLE.name()))
                .map(SpendAllocation::amount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add),
            allocations.stream().filter(item -> item.bucket.equals(StoredValueLedgerBucket.BONUS_AVAILABLE.name()))
                .map(SpendAllocation::amount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add),
            command.idempotencyKey(), command.sourceType() == null ? "USER" : command.sourceType());
        Long paymentId = jdbc.queryForObject("SELECT id FROM stored_value_order_payment WHERE payment_no = ?",
            Long.class, paymentNo);

        List<StoredValueCommands.LedgerLine> lines = new ArrayList<>();
        int allocationNo = 1;
        for (SpendAllocation allocation : allocations) {
            lines.add(new StoredValueCommands.LedgerLine(account.id(), allocation.bonusBatchId,
                allocation.bucket, StoredValueLedgerDirection.DEBIT.name(), allocation.amount));
            lines.add(new StoredValueCommands.LedgerLine(null, null, StoredValueLedgerBucket.ORDER_SETTLEMENT.name(),
                StoredValueLedgerDirection.CREDIT.name(), allocation.amount));
            jdbc.update("""
                INSERT INTO stored_value_order_payment_allocation
                  (payment_id, allocation_no, bucket, bonus_batch_id, amount, expires_at_snapshot)
                VALUES (?, ?, ?, ?, ?, ?)
                """, paymentId, allocationNo++, allocation.bucket, allocation.bonusBatchId,
                allocation.amount, allocation.expiresAt);
        }
        ledgerService.append(new StoredValueCommands.Ledger(
            account.issuerId(), account.productId(), account.id(), "CNY", "ORDER_STORED_VALUE_PAYMENT",
            PAYMENT_SOURCE, paymentNo, "ORDER_PAYMENT:" + paymentNo, product.policy().version(),
            "订单储值全额支付", null, command.sourceType() == null ? "USER" : command.sourceType(), now, lines));
        for (SpendAllocation allocation : allocations) {
            if (allocation.bonusBatchId != null) {
                jdbc.update("""
                    UPDATE stored_value_bonus_batch
                    SET status = CASE WHEN available_amount = 0 AND frozen_amount = 0 THEN 'EXHAUSTED' ELSE status END
                    WHERE id = ?
                    """, allocation.bonusBatchId);
            }
        }

        orderSettlementService.settle(new PaidOrderFact(order, null, null, PAYMENT_TYPE, paymentNo,
            PAYMENT_TYPE, PAYMENT_TYPE, command.sourceType() == null ? "USER" : command.sourceType(),
            money.amount(), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), now));
        jdbc.update("""
            UPDATE stored_value_order_payment
            SET status = 'SUCCEEDED', paid_at = ?, update_time = CURRENT_TIMESTAMP
            WHERE id = ? AND status = 'POSTING'
            """, now, paymentId);
        return new StoredValueOrderPaymentResult(paymentId, paymentNo, order.id, "SUCCEEDED", money.amount(),
            principalAmount(allocations), bonusAmount(allocations), false);
    }

    public StoredValueOrderPaymentResult findByOrder(Long orderId) {
        StoredPaymentRow row = findPaymentForOrder(orderId, false);
        if (row == null) {
            throw BusinessException.notFound("订单储值支付不存在");
        }
        return toResult(row, false);
    }

    /**
     * Restores a completed full stored-value order payment. The caller may hold
     * the order/refund row locks already; direct callers acquire them in the same
     * order used by payment.
     */
    @Transactional
    public StoredValueOrderRefundResult refund(StoredValueCommands.OrderRefund command) {
        if (command == null || command.orderId() == null || command.idempotencyKey() == null
            || command.idempotencyKey().isBlank()) {
            throw BusinessException.badRequest("订单储值退款请求不完整");
        }
        BizOrder order = orderMapper.selectByIdForUpdate(command.orderId());
        if (order == null) {
            throw BusinessException.notFound("订单不存在");
        }
        StoredPaymentRow payment = findPaymentForOrder(order.id, true);
        if (payment == null) {
            throw BusinessException.conflict("订单储值支付事实不存在");
        }
        StoredOrderRefundRow existing = findRefundByPayment(payment.id, true);
        if (existing != null) {
            if (!command.idempotencyKey().equals(existing.idempotencyKey)) {
                throw BusinessException.conflict("订单退款幂等键对应不同事实");
            }
            if ("SUCCEEDED".equals(existing.status)) {
                return toRefundResult(existing, true);
            }
            if ("MANUAL_REVIEW".equals(existing.status)) {
                throw BusinessException.conflict("订单储值退款需要人工处置");
            }
        }
        if (!"SUCCEEDED".equals(payment.status) && !"REFUNDING".equals(payment.status)) {
            throw BusinessException.conflict("订单储值支付当前不能退款");
        }
        if (command.refundRecordId() != null) {
            RefundRecordRow refundRecord = jdbc.queryForObject("""
                SELECT order_id, user_id, refund_amount, status
                FROM refund_record WHERE id = ?
                """, (rs, n) -> new RefundRecordRow(rs.getLong("order_id"), rs.getLong("user_id"),
                rs.getBigDecimal("refund_amount"), rs.getString("status")), command.refundRecordId());
            if (!order.id.equals(refundRecord.orderId) || !order.userId.equals(refundRecord.userId)
                || refundRecord.refundAmount == null || refundRecord.refundAmount.compareTo(payment.amount) != 0) {
                throw BusinessException.conflict("储值订单仅支持全额退款");
            }
        }
        Long productId = jdbc.queryForObject("SELECT product_id FROM stored_value_order_payment WHERE id = ?",
            Long.class, payment.id);
        StoredValueProductSnapshot product = configService.requireProduct(productId);
        StoredValuePolicy policy = configService.requirePolicy(product.id(),
            paymentPolicyVersion(payment.id));
        StoredValueAccountSnapshot account;
        try {
            account = accountService.snapshotForUpdate(order.userId, product);
        } catch (EmptyResultDataAccessException ex) {
            throw BusinessException.conflict("订单储值账户不存在");
        }
        if (!payment.accountId.equals(account.id()) || !payment.userId.equals(order.userId)
            || !payment.productId.equals(account.productId()) || !payment.issuerId.equals(account.issuerId())) {
            throw BusinessException.conflict("订单储值支付账户快照不一致");
        }
        LocalDateTime confirmedAt = LocalDateTime.now(policy.zoneId());
        String refundNo = existing == null
            ? "SVOR-" + UUID.randomUUID().toString().replace("-", "").toUpperCase()
            : existing.refundNo;
        Long refundId;
        if (existing == null) {
            jdbc.update("""
                INSERT INTO stored_value_order_refund
                  (refund_no, order_payment_id, order_id, refund_record_id, idempotency_key,
                   amount, principal_amount, bonus_amount, compensation_amount, currency,
                   policy_version, status, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0.00, 'CNY', ?, 'POSTING', ?)
                """, refundNo, payment.id, order.id, command.refundRecordId(), command.idempotencyKey(),
                payment.amount, payment.principalAmount, payment.bonusAmount,
                paymentPolicyVersion(payment.id),
                command.reason() == null || command.reason().isBlank() ? "订单储值退款" : command.reason());
            refundId = jdbc.queryForObject("SELECT id FROM stored_value_order_refund WHERE refund_no = ?",
                Long.class, refundNo);
        } else {
            refundId = existing.id;
        }

        List<PaymentAllocationRow> allocations = jdbc.query("""
            SELECT id, bucket, bonus_batch_id, amount, refunded_amount, expires_at_snapshot
            FROM stored_value_order_payment_allocation
            WHERE payment_id = ?
            ORDER BY allocation_no, id
            FOR UPDATE
            """, (rs, n) -> new PaymentAllocationRow(rs.getLong("id"), rs.getString("bucket"),
            (Long) rs.getObject("bonus_batch_id"), rs.getBigDecimal("amount"),
            rs.getBigDecimal("refunded_amount"), rs.getTimestamp("expires_at_snapshot") == null
                ? null : rs.getTimestamp("expires_at_snapshot").toLocalDateTime()), payment.id);
        if (allocations.isEmpty()) {
            throw BusinessException.conflict("订单储值支付分摊不存在");
        }
        BigDecimal principal = BigDecimal.ZERO.setScale(2);
        BigDecimal bonus = BigDecimal.ZERO.setScale(2);
        BigDecimal compensation = BigDecimal.ZERO.setScale(2);
        for (PaymentAllocationRow allocation : allocations) {
            BigDecimal amount = allocation.amount.subtract(allocation.refundedAmount);
            if (amount.signum() <= 0) {
                continue;
            }
            if (StoredValueLedgerBucket.PRINCIPAL_AVAILABLE.name().equals(allocation.bucket)) {
                appendRefundLedger(account, payment, refundNo, allocation.id, allocation.bucket, null,
                    amount, paymentPolicyVersion(payment.id), "订单储值本金原桶退款");
                principal = principal.add(amount);
            } else {
                Long restoredBatchId = null;
                if (allocation.expiresAt != null && allocation.expiresAt.isAfter(confirmedAt)) {
                    restoredBatchId = allocation.bonusBatchId;
                    appendRefundLedger(account, payment, refundNo, allocation.id, allocation.bucket,
                        restoredBatchId, amount, paymentPolicyVersion(payment.id), "订单储值赠送金原桶退款");
                    jdbc.update("""
                        UPDATE stored_value_bonus_batch
                        SET status = CASE WHEN available_amount + ? > 0 THEN 'AVAILABLE' ELSE status END
                        WHERE id = ?
                        """, amount, restoredBatchId);
                } else {
                    jdbc.update("""
                        INSERT INTO stored_value_bonus_batch
                          (account_id, recharge_id, source_order_payment_id, compensation_ref,
                           issuer_id, product_id, policy_version, currency, original_amount,
                           available_amount, frozen_amount, issued_at, expires_at, status)
                        VALUES (?, NULL, ?, ?, ?, ?, ?, 'CNY', ?, 0.00, 0.00, ?, ?, 'AVAILABLE')
                        """, account.id(), payment.id, refundNo, account.issuerId(), account.productId(),
                        paymentPolicyVersion(payment.id), amount, confirmedAt,
                        confirmedAt.plusDays(policy.refundCompensationValidDays()));
                    restoredBatchId = jdbc.queryForObject("""
                        SELECT id FROM stored_value_bonus_batch
                        WHERE source_order_payment_id = ? AND compensation_ref = ?
                        """, Long.class, payment.id, refundNo);
                    appendRefundLedger(account, payment, refundNo, allocation.id, allocation.bucket,
                        restoredBatchId, amount, paymentPolicyVersion(payment.id), "订单储值赠送金过期补偿");
                    compensation = compensation.add(amount);
                }
                bonus = bonus.add(amount);
                jdbc.update("""
                    INSERT INTO stored_value_order_refund_allocation
                      (refund_id, payment_allocation_id, source_bonus_batch_id,
                       restored_bonus_batch_id, bucket, amount)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, refundId, allocation.id, allocation.bonusBatchId, restoredBatchId,
                    allocation.bucket, amount);
            }
            if (StoredValueLedgerBucket.PRINCIPAL_AVAILABLE.name().equals(allocation.bucket)) {
                jdbc.update("""
                    INSERT INTO stored_value_order_refund_allocation
                      (refund_id, payment_allocation_id, bucket, amount)
                    VALUES (?, ?, ?, ?)
                    """, refundId, allocation.id, allocation.bucket, amount);
            }
            int allocationUpdated = jdbc.update("""
                UPDATE stored_value_order_payment_allocation
                SET refunded_amount = refunded_amount + ?
                WHERE id = ? AND refunded_amount + ? <= amount
                """, amount, allocation.id, amount);
            if (allocationUpdated != 1) {
                throw BusinessException.conflict("订单储值退款分摊已被并发处理");
            }
        }
        if (principal.add(bonus).compareTo(payment.amount) != 0) {
            throw BusinessException.conflict("订单储值退款分摊金额不完整");
        }
        int refundUpdated = jdbc.update("""
            UPDATE stored_value_order_refund
            SET principal_amount = ?, bonus_amount = ?, compensation_amount = ?,
                status = 'SUCCEEDED', confirmation_time = ?, update_time = CURRENT_TIMESTAMP
            WHERE id = ? AND status = 'POSTING'
            """, principal, bonus, compensation, confirmedAt, refundId);
        if (refundUpdated != 1) {
            throw BusinessException.conflict("订单储值退款状态未能收敛");
        }
        int paymentUpdated = jdbc.update("""
            UPDATE stored_value_order_payment
            SET status = 'REFUNDED', refunded_at = ?, update_time = CURRENT_TIMESTAMP
            WHERE id = ? AND status IN ('SUCCEEDED','REFUNDING')
            """, confirmedAt, payment.id);
        if (paymentUpdated != 1) {
            throw BusinessException.conflict("订单储值支付状态未能收敛");
        }
        StoredOrderRefundRow completed = findRefundById(refundId, false);
        return toRefundResult(completed, false);
    }

    public StoredValueOrderRefundResult refund(Long orderId, Long refundRecordId, String idempotencyKey,
                                               String reason) {
        return refund(new StoredValueCommands.OrderRefund(orderId, refundRecordId, idempotencyKey, reason));
    }

    private void appendRefundLedger(StoredValueAccountSnapshot account, StoredPaymentRow payment,
                                    String refundNo, Long allocationId, String bucket, Long bonusBatchId,
                                    BigDecimal amount, String policyVersion, String reason) {
        String accountBucket = StoredValueLedgerBucket.PRINCIPAL_AVAILABLE.name().equals(bucket)
            ? StoredValueLedgerBucket.PRINCIPAL_AVAILABLE.name() : StoredValueLedgerBucket.BONUS_AVAILABLE.name();
        ledgerService.append(new StoredValueCommands.Ledger(
            account.issuerId(), account.productId(), account.id(), "CNY", "ORDER_STORED_VALUE_REFUND",
            REFUND_SOURCE, refundNo, "ORDER_REFUND:" + refundNo + ":" + allocationId, policyVersion, reason,
            null, "SYSTEM", LocalDateTime.now(), List.of(
            new StoredValueCommands.LedgerLine(null, null, StoredValueLedgerBucket.ORDER_SETTLEMENT.name(),
                StoredValueLedgerDirection.DEBIT.name(), amount),
            new StoredValueCommands.LedgerLine(account.id(), bonusBatchId, accountBucket,
                StoredValueLedgerDirection.CREDIT.name(), amount))));
    }

    private String paymentPolicyVersion(Long paymentId) {
        return jdbc.queryForObject("SELECT policy_version FROM stored_value_order_payment WHERE id = ?",
            String.class, paymentId);
    }

    private StoredOrderRefundRow findRefundByPayment(Long paymentId, boolean forUpdate) {
        String sql = "SELECT id, refund_no, order_payment_id, order_id, idempotency_key, amount, "
            + "principal_amount, bonus_amount, compensation_amount, status FROM stored_value_order_refund "
            + "WHERE order_payment_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(sql, rs -> rs.next() ? mapRefund(rs) : null, paymentId);
    }

    private StoredOrderRefundRow findRefundById(Long refundId, boolean forUpdate) {
        String sql = "SELECT id, refund_no, order_payment_id, order_id, idempotency_key, amount, "
            + "principal_amount, bonus_amount, compensation_amount, status FROM stored_value_order_refund "
            + "WHERE id = ?" + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.queryForObject(sql, (rs, n) -> mapRefund(rs), refundId);
    }

    private StoredOrderRefundRow mapRefund(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredOrderRefundRow(rs.getLong("id"), rs.getString("refund_no"),
            rs.getLong("order_payment_id"), rs.getLong("order_id"), rs.getString("idempotency_key"),
            rs.getBigDecimal("amount"), rs.getBigDecimal("principal_amount"),
            rs.getBigDecimal("bonus_amount"), rs.getBigDecimal("compensation_amount"), rs.getString("status"));
    }

    private StoredValueOrderRefundResult toRefundResult(StoredOrderRefundRow row, boolean replayed) {
        return new StoredValueOrderRefundResult(row.id, row.refundNo, row.orderId, row.status, row.amount,
            row.principalAmount, row.bonusAmount, row.compensationAmount, replayed);
    }

    private List<SpendAllocation> allocate(StoredValueAccountSnapshot account, BigDecimal amount, LocalDateTime now) {
        BigDecimal remaining = amount;
        List<SpendAllocation> result = new ArrayList<>();
        List<BonusRow> bonuses = jdbc.query("""
            SELECT id, available_amount, expires_at
            FROM stored_value_bonus_batch
            WHERE account_id = ? AND status = 'AVAILABLE' AND available_amount > 0 AND expires_at > ?
            ORDER BY expires_at, issued_at, id
            FOR UPDATE
            """, (rs, n) -> new BonusRow(rs.getLong("id"), rs.getBigDecimal("available_amount"),
                rs.getTimestamp("expires_at").toLocalDateTime()), account.id(), now);
        for (BonusRow bonus : bonuses) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal used = remaining.min(bonus.availableAmount);
            if (used.signum() > 0) {
                result.add(new SpendAllocation(StoredValueLedgerBucket.BONUS_AVAILABLE.name(), bonus.id,
                    used, bonus.expiresAt));
                remaining = remaining.subtract(used);
            }
        }
        if (remaining.signum() > 0) {
            BigDecimal principal = remaining.min(account.principalAvailable());
            if (principal.signum() > 0) {
                result.add(new SpendAllocation(StoredValueLedgerBucket.PRINCIPAL_AVAILABLE.name(), null, principal, null));
                remaining = remaining.subtract(principal);
            }
        }
        return result;
    }

    private StoredPaymentRow findPaymentForOrder(Long orderId, boolean forUpdate) {
        String sql = "SELECT id, payment_no, order_id, user_id, product_id, account_id, issuer_id, amount, "
            + "principal_amount, bonus_amount, idempotency_key, status "
            + "FROM stored_value_order_payment WHERE order_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(sql, rs -> rs.next() ? new StoredPaymentRow(rs.getLong("id"), rs.getString("payment_no"),
            rs.getLong("order_id"), rs.getLong("user_id"), rs.getLong("product_id"), rs.getLong("account_id"),
            rs.getLong("issuer_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("principal_amount"),
            rs.getBigDecimal("bonus_amount"), rs.getString("idempotency_key"), rs.getString("status")) : null, orderId);
    }

    private StoredValueOrderPaymentResult toResult(StoredPaymentRow row, boolean replayed) {
        return new StoredValueOrderPaymentResult(row.id, row.paymentNo, row.orderId, row.status, row.amount,
            row.principalAmount, row.bonusAmount, replayed);
    }

    private BigDecimal principalAmount(List<SpendAllocation> allocations) {
        return allocations.stream().filter(item -> StoredValueLedgerBucket.PRINCIPAL_AVAILABLE.name().equals(item.bucket))
            .map(SpendAllocation::amount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private BigDecimal bonusAmount(List<SpendAllocation> allocations) {
        return allocations.stream().filter(item -> StoredValueLedgerBucket.BONUS_AVAILABLE.name().equals(item.bucket))
            .map(SpendAllocation::amount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private void validateCommand(StoredValueCommands.OrderPayment command) {
        if (command == null || command.orderId() == null || command.userId() == null || command.productId() == null
            || command.idempotencyKey() == null || command.idempotencyKey().isBlank()
            || command.idempotencyKey().length() > 192) {
            throw BusinessException.badRequest("订单储值支付请求不完整");
        }
    }

    private boolean isPending(String status) {
        return "PENDING_PAYMENT".equals(status);
    }

    private record SpendAllocation(String bucket, Long bonusBatchId, BigDecimal amount,
                                   LocalDateTime expiresAt) {
    }

    private record BonusRow(Long id, BigDecimal availableAmount, LocalDateTime expiresAt) {
    }

    private record StoredPaymentRow(Long id, String paymentNo, Long orderId, Long userId, Long productId,
                                    Long accountId, Long issuerId, BigDecimal amount,
                                    BigDecimal principalAmount, BigDecimal bonusAmount,
                                    String idempotencyKey, String status) {
    }

    private record PaymentAllocationRow(Long id, String bucket, Long bonusBatchId, BigDecimal amount,
                                        BigDecimal refundedAmount, LocalDateTime expiresAt) {
    }

    private record StoredOrderRefundRow(Long id, String refundNo, Long orderPaymentId, Long orderId,
                                        String idempotencyKey, BigDecimal amount, BigDecimal principalAmount,
                                        BigDecimal bonusAmount, BigDecimal compensationAmount, String status) {
    }

    private record RefundRecordRow(Long orderId, Long userId, BigDecimal refundAmount, String status) {
    }
}
