package com.example.venue.storedvalue.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.ResultCode;
import com.example.venue.security.SecurityContext;
import com.example.venue.storedvalue.dto.StoredValueResponses;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Owns user-scoped stored-value reads and their DTO projections.
 * It does not perform balance, payment, or lifecycle writes.
 */
@Service
public class StoredValueUserQueryService {
    private static final String PRODUCT_SELECT = """
        SELECT p.id, p.product_code, p.product_name, i.issuer_code, p.currency,
               p.environment, p.status, p.recharge_enabled, p.stored_value_spend_enabled,
               policy.policy_version, policy.min_recharge_amount, policy.max_recharge_amount,
               policy.daily_recharge_limit, policy.principal_balance_limit,
               policy.total_balance_limit, policy.bonus_rate_percent,
               policy.bonus_per_recharge_limit, policy.monthly_bonus_limit,
               policy.bonus_valid_days, policy.refund_compensation_valid_days
        FROM stored_value_product p
        JOIN stored_value_issuer i ON i.id = p.issuer_id
        LEFT JOIN stored_value_policy policy ON policy.id = p.current_policy_id
        """;
    private static final String RECHARGE_SELECT = """
        SELECT id, recharge_no, product_id, status, principal_amount,
               expected_bonus_amount, actual_bonus_amount, currency, provider_key,
               provider_env, provider_name, provider_business_no, external_ref,
               failure_code, create_time, update_time
        FROM stored_value_recharge
        """;

    private final JdbcTemplate jdbc;

    public StoredValueUserQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public StoredValueResponses.SelfResponse self() {
        Long userId = currentUserId();
        List<StoredValueResponses.ProductResponse> products = products(true);
        StoredValueResponses.ProductResponse product = products.isEmpty() ? null : products.get(0);
        StoredValueResponses.AccountResponse account = product == null ? null : account(userId, product.id());
        boolean identityVerified = product != null && identityVerified(userId, product.environment());
        String status;
        String message;
        if (product == null) {
            status = "UNAVAILABLE";
            message = "当前没有启用的 DEV/SANDBOX 储值产品";
        } else if (!identityVerified) {
            status = "IDENTITY_REQUIRED";
            message = "完成储值身份核验后才能充值或消费";
        } else if (account == null) {
            status = "ACCOUNT_REQUIRED";
            message = "储值资格已满足，首次充值时会开通账户";
        } else if (!"NORMAL".equals(account.status())) {
            status = "ACCOUNT_" + account.status();
            message = "储值账户当前状态为 " + account.status();
        } else {
            status = "READY";
            message = "DEV/SANDBOX 储值可用";
        }
        return new StoredValueResponses.SelfResponse(product, products, account,
            identityVerified, status, message);
    }

    public List<StoredValueResponses.BonusBatchResponse> bonusBatches() {
        Long userId = currentUserId();
        return jdbc.query("""
            SELECT b.id, b.recharge_id, b.original_amount, b.available_amount,
                   b.frozen_amount, b.issued_at, b.expires_at, b.status
            FROM stored_value_bonus_batch b
            JOIN stored_value_account a ON a.id = b.account_id
            WHERE a.user_id = ?
            ORDER BY b.expires_at, b.issued_at, b.id
            """, (rs, n) -> new StoredValueResponses.BonusBatchResponse(
                rs.getLong("id"), nullableLong(rs.getObject("recharge_id")),
                money(rs.getBigDecimal("original_amount")), money(rs.getBigDecimal("available_amount")),
                money(rs.getBigDecimal("frozen_amount")), dateTime(rs, "issued_at"),
                dateTime(rs, "expires_at"), rs.getString("status")), userId);
    }

    public PageResult<StoredValueResponses.RechargeResponse> myRecharges(PageQuery query) {
        Long userId = currentUserId();
        int pageNum = pageNum(query);
        int pageSize = pageSize(query);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_recharge WHERE user_id = ?",
            Long.class, userId);
        List<StoredValueResponses.RechargeResponse> records = jdbc.query(RECHARGE_SELECT
            + " WHERE user_id = ? ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?",
            rechargeMapper(), userId, pageSize, (pageNum - 1) * pageSize);
        return PageResult.of(records, total, pageNum, pageSize);
    }

    public StoredValueResponses.RechargeResponse myRecharge(String rechargeNo) {
        return rechargeForUser(rechargeNo, currentUserId());
    }

    public StoredValueResponses.RechargeResponse rechargeForUser(String rechargeNo, Long userId) {
        if (rechargeNo == null || rechargeNo.isBlank()) {
            throw BusinessException.badRequest("充值业务号不能为空");
        }
        List<StoredValueResponses.RechargeResponse> records = jdbc.query(RECHARGE_SELECT
            + " WHERE recharge_no = ? AND user_id = ?", rechargeMapper(), rechargeNo, userId);
        if (records.isEmpty()) {
            throw BusinessException.notFound("充值记录不存在");
        }
        return records.get(0);
    }

    public PageResult<StoredValueResponses.LedgerResponse> myLedger(PageQuery query) {
        Long userId = currentUserId();
        int pageNum = pageNum(query);
        int pageSize = pageSize(query);
        long total = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM stored_value_ledger_entry e
            JOIN stored_value_ledger_transaction t ON t.id = e.transaction_id
            JOIN stored_value_account a ON a.id = e.account_id
            WHERE a.user_id = ?
            """, Long.class, userId);
        List<StoredValueResponses.LedgerResponse> records = jdbc.query("""
            SELECT t.id AS transaction_id, t.transaction_type, t.source_type, t.source_id,
                   e.bucket, e.direction, e.amount, e.currency, t.reason, t.occurred_at
            FROM stored_value_ledger_entry e
            JOIN stored_value_ledger_transaction t ON t.id = e.transaction_id
            JOIN stored_value_account a ON a.id = e.account_id
            WHERE a.user_id = ?
            ORDER BY t.occurred_at DESC, t.id DESC, e.id DESC
            LIMIT ? OFFSET ?
            """, (rs, n) -> new StoredValueResponses.LedgerResponse(
                rs.getLong("transaction_id"), rs.getString("transaction_type"),
                rs.getString("source_type"), rs.getString("source_id"), rs.getString("bucket"),
                rs.getString("direction"), money(rs.getBigDecimal("amount")),
                rs.getString("currency"), rs.getString("reason"), dateTime(rs, "occurred_at")),
            userId, pageSize, (pageNum - 1) * pageSize);
        return PageResult.of(records, total, pageNum, pageSize);
    }

    public StoredValueResponses.OrderPaymentOptionResponse orderPaymentOption(Long orderId) {
        Long userId = currentUserId();
        OrderRow order = order(orderId, userId);
        BigDecimal amount = money(order.amount());
        List<StoredValueResponses.ProductResponse> spendable = products(true).stream()
            .filter(StoredValueResponses.ProductResponse::spendEnabled).toList();
        StoredValueResponses.ProductResponse product = spendable.isEmpty() ? null : spendable.get(0);
        if (product == null) {
            return new StoredValueResponses.OrderPaymentOptionResponse(order.id(), amount, "CNY", false,
                "当前没有启用储值消费产品", null, null, false);
        }
        StoredValueResponses.AccountResponse account = account(userId, product.id());
        boolean verified = identityVerified(userId, product.environment());
        String reason = null;
        boolean available = true;
        if (!"PENDING_PAYMENT".equals(order.status())) {
            available = false;
            reason = "订单当前不在待支付状态";
        } else if (order.paymentToolType() != null && !order.paymentToolType().isBlank()) {
            available = false;
            reason = "订单已经选择其它支付工具";
        } else if (!verified) {
            available = false;
            reason = "未完成储值身份核验";
        } else if (account == null) {
            available = false;
            reason = "尚未开通储值账户";
        } else if (!"NORMAL".equals(account.status())) {
            available = false;
            reason = "储值账户当前不可消费";
        } else if (account.availableTotal().compareTo(amount) < 0) {
            available = false;
            reason = "储值余额不足，可选择现有外部支付";
        }
        return new StoredValueResponses.OrderPaymentOptionResponse(order.id(), amount, "CNY", available,
            reason, product, account, verified);
    }

    public StoredValueResponses.OrderStoredValueStatusResponse orderStoredValueStatus(Long orderId) {
        Long userId = currentUserId();
        order(orderId, userId);
        StoredValueResponses.OrderPaymentResponse payment = jdbc.query("""
            SELECT id, payment_no, order_id, status, amount, principal_amount, bonus_amount
            FROM stored_value_order_payment WHERE order_id = ?
            """, rs -> rs.next() ? new StoredValueResponses.OrderPaymentResponse(
            rs.getLong("id"), rs.getString("payment_no"), rs.getLong("order_id"),
            rs.getString("status"), money(rs.getBigDecimal("amount")),
            money(rs.getBigDecimal("principal_amount")), money(rs.getBigDecimal("bonus_amount")), false) : null,
            orderId);
        StoredValueResponses.OrderRefundResponse refund = jdbc.query("""
            SELECT id, refund_no, order_id, status, amount, principal_amount,
                   bonus_amount, compensation_amount
            FROM stored_value_order_refund WHERE order_id = ?
            """, rs -> rs.next() ? new StoredValueResponses.OrderRefundResponse(
            rs.getLong("id"), rs.getString("refund_no"), rs.getLong("order_id"),
            rs.getString("status"), money(rs.getBigDecimal("amount")),
            money(rs.getBigDecimal("principal_amount")), money(rs.getBigDecimal("bonus_amount")),
            money(rs.getBigDecimal("compensation_amount")), false) : null, orderId);
        return new StoredValueResponses.OrderStoredValueStatusResponse(payment, refund);
    }

    public List<StoredValueResponses.ProductResponse> products(boolean enabledOnly) {
        String where = enabledOnly
            ? " WHERE p.status = 'ENABLED' AND i.status = 'ENABLED' AND policy.status = 'ACTIVE'"
            : " WHERE i.status <> 'DISABLED'";
        return jdbc.query(PRODUCT_SELECT + where + " ORDER BY p.id", productMapper());
    }

    public StoredValueResponses.ProductResponse product(Long productId) {
        List<StoredValueResponses.ProductResponse> records = jdbc.query(
            PRODUCT_SELECT + " WHERE p.id = ?", productMapper(), productId);
        if (records.isEmpty()) {
            throw BusinessException.notFound("储值产品不存在");
        }
        return records.get(0);
    }

    private StoredValueResponses.AccountResponse account(Long userId, Long productId) {
        List<AccountRow> records = jdbc.query("""
            SELECT id, user_id, product_id, status, currency,
                   principal_available, principal_frozen, version
            FROM stored_value_account WHERE user_id = ? AND product_id = ?
            """, (rs, n) -> new AccountRow(rs.getLong("id"), rs.getLong("user_id"),
                rs.getLong("product_id"), rs.getString("status"), rs.getString("currency"),
                money(rs.getBigDecimal("principal_available")), money(rs.getBigDecimal("principal_frozen")),
                rs.getLong("version")), userId, productId);
        return records.isEmpty() ? null : accountResponse(records.get(0));
    }

    private StoredValueResponses.AccountResponse accountResponse(AccountRow account) {
        BonusTotals totals = jdbc.queryForObject("""
            SELECT COALESCE(SUM(CASE WHEN status IN ('AVAILABLE','FROZEN') THEN available_amount ELSE 0 END),0.00),
                   COALESCE(SUM(frozen_amount),0.00)
            FROM stored_value_bonus_batch WHERE account_id = ?
            """, (rs, n) -> new BonusTotals(money(rs.getBigDecimal(1)), money(rs.getBigDecimal(2))), account.id());
        BigDecimal available = account.principalAvailable().add(totals.available());
        BigDecimal total = account.principalAvailable().add(account.principalFrozen())
            .add(totals.available()).add(totals.frozen());
        return new StoredValueResponses.AccountResponse(account.id(), account.userId(), account.productId(),
            account.status(), account.currency(), account.principalAvailable(), account.principalFrozen(),
            totals.available(), totals.frozen(), available, total, account.version());
    }

    private boolean identityVerified(Long userId, String environment) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM stored_value_identity_verification
            WHERE user_id = ? AND verification_status = 'VERIFIED'
              AND environment = ? AND verified_at <= CURRENT_TIMESTAMP
              AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
            """, Integer.class, userId, environment);
        return count != null && count > 0;
    }

    private OrderRow order(Long orderId, Long userId) {
        List<OrderRow> records = jdbc.query("""
            SELECT id, user_id, COALESCE(payable_amount, total_amount) AS amount,
                   status, payment_tool_type
            FROM biz_order WHERE id = ? AND user_id = ?
            """, (rs, n) -> new OrderRow(rs.getLong("id"), rs.getLong("user_id"),
                money(rs.getBigDecimal("amount")), rs.getString("status"), rs.getString("payment_tool_type")),
            orderId, userId);
        if (records.isEmpty()) {
            throw BusinessException.notFound("订单不存在");
        }
        return records.get(0);
    }

    private RowMapper<StoredValueResponses.ProductResponse> productMapper() {
        return (rs, n) -> new StoredValueResponses.ProductResponse(
            rs.getLong("id"), rs.getString("product_code"), rs.getString("product_name"),
            rs.getString("issuer_code"), rs.getString("currency"), rs.getString("environment"),
            rs.getString("status"), rs.getBoolean("recharge_enabled"),
            rs.getBoolean("stored_value_spend_enabled"), rs.getString("policy_version"),
            rs.getBigDecimal("min_recharge_amount"), rs.getBigDecimal("max_recharge_amount"),
            rs.getBigDecimal("daily_recharge_limit"), rs.getBigDecimal("principal_balance_limit"),
            rs.getBigDecimal("total_balance_limit"), rs.getBigDecimal("bonus_rate_percent"),
            rs.getBigDecimal("bonus_per_recharge_limit"), rs.getBigDecimal("monthly_bonus_limit"),
            rs.getInt("bonus_valid_days"), rs.getInt("refund_compensation_valid_days"));
    }

    private RowMapper<StoredValueResponses.RechargeResponse> rechargeMapper() {
        return (rs, n) -> new StoredValueResponses.RechargeResponse(
            rs.getLong("id"), rs.getString("recharge_no"), rs.getLong("product_id"),
            rs.getString("status"), money(rs.getBigDecimal("principal_amount")),
            money(rs.getBigDecimal("expected_bonus_amount")), money(rs.getBigDecimal("actual_bonus_amount")),
            rs.getString("currency"), rs.getString("provider_key"), rs.getString("provider_env"),
            rs.getString("provider_name"), rs.getString("provider_business_no"),
            rs.getString("external_ref"), rs.getString("failure_code"),
            dateTime(rs, "create_time"), dateTime(rs, "update_time"));
    }

    private Long currentUserId() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }

    private int pageNum(PageQuery query) {
        return (int) Math.max(1, query == null ? 1 : query.getPageNum());
    }

    private int pageSize(PageQuery query) {
        return (int) Math.min(100, Math.max(1, query == null ? 10 : query.getPageSize()));
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    private LocalDateTime dateTime(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private record AccountRow(Long id, Long userId, Long productId, String status, String currency,
                              BigDecimal principalAvailable, BigDecimal principalFrozen, long version) {
    }

    private record BonusTotals(BigDecimal available, BigDecimal frozen) {
    }

    private record OrderRow(Long id, Long userId, BigDecimal amount, String status, String paymentToolType) {
    }
}
