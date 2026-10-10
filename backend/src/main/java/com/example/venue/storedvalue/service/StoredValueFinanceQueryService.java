package com.example.venue.storedvalue.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.storedvalue.domain.StoredValueRechargeStatus;
import com.example.venue.storedvalue.dto.StoredValueResponses;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns finance-only reads and DTO projections. It has no write or transaction
 * ownership, so the API facade can compose it without changing domain paths.
 */
@Service
public class StoredValueFinanceQueryService {
    private final JdbcTemplate jdbc;

    public StoredValueFinanceQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public StoredValueResponses.FinanceSummaryResponse financeSummary() {
        long accounts = jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_account", Long.class);
        long pending = jdbc.queryForObject("""
            SELECT COUNT(*) FROM stored_value_recharge
            WHERE status IN ('%s','%s','%s','%s','%s','%s','%s')
            """.formatted(
                StoredValueRechargeStatus.INITIATED.name(),
                StoredValueRechargeStatus.WAITING_PROVIDER.name(),
                StoredValueRechargeStatus.PROVIDER_SUCCEEDED_PENDING_POSTING.name(),
                StoredValueRechargeStatus.POSTING.name(),
                StoredValueRechargeStatus.REFUNDING.name(),
                StoredValueRechargeStatus.DISPUTED.name(),
                StoredValueRechargeStatus.MANUAL_REVIEW.name()), Long.class);
        long differences = jdbc.queryForObject("""
            SELECT COUNT(*) FROM stored_value_reconciliation_difference
            WHERE status IN ('OPEN','UNDER_REVIEW','MANUAL_REVIEW')
            """, Long.class);
        BigDecimal principal = jdbc.queryForObject(
            "SELECT COALESCE(SUM(principal_available),0.00) FROM stored_value_account",
            BigDecimal.class);
        BigDecimal bonus = jdbc.queryForObject("""
            SELECT COALESCE(SUM(available_amount),0.00)
            FROM stored_value_bonus_batch WHERE status IN ('AVAILABLE','FROZEN')
            """, BigDecimal.class);
        return new StoredValueResponses.FinanceSummaryResponse(accounts, pending, differences,
            money(principal), money(bonus));
    }

    public PageResult<StoredValueResponses.FinanceAccountResponse> financeAccounts(PageQuery query) {
        int pageNum = pageNum(query);
        int pageSize = pageSize(query);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_account", Long.class);
        List<StoredValueResponses.FinanceAccountResponse> records = jdbc.query("""
            SELECT a.id, a.user_id, a.product_id, p.product_code, p.environment, a.status,
                   a.principal_available, a.principal_frozen,
                   COALESCE((SELECT SUM(CASE WHEN b.status IN ('AVAILABLE','FROZEN')
                                            THEN b.available_amount ELSE 0 END)
                             FROM stored_value_bonus_batch b WHERE b.account_id = a.id),0.00) AS bonus_available,
                   COALESCE((SELECT SUM(b.frozen_amount) FROM stored_value_bonus_batch b
                             WHERE b.account_id = a.id),0.00) AS bonus_frozen,
                   a.update_time
            FROM stored_value_account a
            JOIN stored_value_product p ON p.id = a.product_id
            ORDER BY a.update_time DESC, a.id DESC
            LIMIT ? OFFSET ?
            """, (rs, n) -> {
                BigDecimal principal = money(rs.getBigDecimal("principal_available"));
                BigDecimal frozenPrincipal = money(rs.getBigDecimal("principal_frozen"));
                BigDecimal bonus = money(rs.getBigDecimal("bonus_available"));
                BigDecimal frozenBonus = money(rs.getBigDecimal("bonus_frozen"));
                return new StoredValueResponses.FinanceAccountResponse(
                    StoredValueReference.account(rs.getLong("id")), userRef(rs.getLong("user_id")),
                    rs.getString("product_code"), rs.getString("environment"), rs.getString("status"),
                    principal, frozenPrincipal, bonus, frozenBonus, principal.add(bonus),
                    principal.add(frozenPrincipal).add(bonus).add(frozenBonus), dateTime(rs, "update_time"));
            }, pageSize, (pageNum - 1) * pageSize);
        return PageResult.of(records, total, pageNum, pageSize);
    }

    public PageResult<StoredValueResponses.FinanceRechargeResponse> financeRecharges(PageQuery query) {
        int pageNum = pageNum(query);
        int pageSize = pageSize(query);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_recharge", Long.class);
        List<StoredValueResponses.FinanceRechargeResponse> records = jdbc.query("""
            SELECT r.id, r.recharge_no, r.user_id, r.account_id, r.product_id,
                   p.product_code, r.status, r.principal_amount, r.expected_bonus_amount,
                   r.actual_bonus_amount, r.currency, r.provider_key, r.provider_env,
                   r.provider_name, r.external_ref, r.failure_code, r.create_time, r.update_time
            FROM stored_value_recharge r
            JOIN stored_value_product p ON p.id = r.product_id
            ORDER BY r.create_time DESC, r.id DESC
            LIMIT ? OFFSET ?
            """, (rs, n) -> new StoredValueResponses.FinanceRechargeResponse(
                StoredValueReference.recharge(rs.getString("recharge_no")),
                StoredValueReference.suffix(rs.getString("recharge_no")), userRef(rs.getLong("user_id")),
                rs.getString("product_code"), rs.getString("status"), money(rs.getBigDecimal("principal_amount")),
                money(rs.getBigDecimal("expected_bonus_amount")), money(rs.getBigDecimal("actual_bonus_amount")),
                rs.getString("currency"), rs.getString("provider_key"), rs.getString("provider_env"),
                rs.getString("provider_name"), StoredValueReference.suffix(rs.getString("external_ref")),
                rs.getString("failure_code"), dateTime(rs, "create_time"), dateTime(rs, "update_time")),
            pageSize, (pageNum - 1) * pageSize);
        return PageResult.of(records, total, pageNum, pageSize);
    }

    public PageResult<StoredValueResponses.FinanceLedgerResponse> financeLedger(PageQuery query, String accountRef) {
        int pageNum = pageNum(query);
        int pageSize = pageSize(query);
        Long accountId = resolveAccountIdByRef(accountRef);
        if (accountRef != null && !accountRef.isBlank() && accountId == null) {
            return PageResult.of(List.of(), 0, pageNum, pageSize);
        }
        boolean filtered = accountId != null;
        long total;
        if (filtered) {
            Long counted = jdbc.queryForObject("""
                SELECT COUNT(*) FROM stored_value_ledger_entry e
                JOIN stored_value_ledger_transaction t ON t.id = e.transaction_id
                WHERE e.account_id = ?
                """, Long.class, accountId);
            total = counted == null ? 0 : counted;
        } else {
            Long counted = jdbc.queryForObject("""
                SELECT COUNT(*) FROM stored_value_ledger_entry e
                JOIN stored_value_ledger_transaction t ON t.id = e.transaction_id
                WHERE e.account_id IS NOT NULL
                """, Long.class);
            total = counted == null ? 0 : counted;
        }
        List<Object> args = new ArrayList<>();
        if (filtered) {
            args.add(accountId);
        }
        args.add(pageSize);
        args.add((pageNum - 1) * pageSize);
        // B5-H24（S05 隔离栈实测）：文本块与 filterClause 直接拼接会把 `?`/`NULL` 与 `ORDER BY` 粘成
        // `?ORDER BY` / `NULLORDER BY`（BadSqlGrammar）。这里用普通字符串显式带前导空格拼接。
        String filterClause = filtered ? "WHERE e.account_id = ?" : "WHERE e.account_id IS NOT NULL";
        String ledgerSql = """
            SELECT t.id AS transaction_id, e.account_id, a.user_id,
                   t.transaction_type, t.source_type, t.source_id, e.bucket,
                   e.direction, e.amount, e.currency, t.reason, t.occurred_at
            FROM stored_value_ledger_entry e
            JOIN stored_value_ledger_transaction t ON t.id = e.transaction_id
            JOIN stored_value_account a ON a.id = e.account_id
            """ + filterClause + " ORDER BY t.occurred_at DESC, t.id DESC, e.id DESC LIMIT ? OFFSET ?";
        List<StoredValueResponses.FinanceLedgerResponse> records = jdbc.query(ledgerSql,
            (rs, n) -> new StoredValueResponses.FinanceLedgerResponse(
                StoredValueReference.transaction(rs.getLong("transaction_id")),
                StoredValueReference.account(rs.getLong("account_id")), userRef(rs.getLong("user_id")),
                rs.getString("transaction_type"), rs.getString("source_type"),
                StoredValueReference.suffix(rs.getString("source_id")), rs.getString("bucket"),
                rs.getString("direction"), money(rs.getBigDecimal("amount")), rs.getString("currency"),
                rs.getString("reason"), dateTime(rs, "occurred_at")),
            args.toArray());
        return PageResult.of(records, total, pageNum, pageSize);
    }

    /**
     * B5-H11：财务侧原桶订单分摊只读摘要。仅读 stored_value_order_payment/refund
     * 事实表，不接场馆退款审核、不改任何账务事实；出参为受控引用与金额摘要。
     */
    public StoredValueResponses.OrderStoredFactsResponse orderStoredFacts(String orderNo) {
        String normalized = orderNo == null ? "" : orderNo.trim();
        if (normalized.isEmpty()) {
            throw BusinessException.badRequest("订单号不能为空");
        }
        StoredValueResponses.OrderStoredFactPaymentResponse payment = jdbc.query("""
            SELECT payment_no, status, amount, principal_amount, bonus_amount
            FROM stored_value_order_payment WHERE order_no = ?
            """, rs -> rs.next() ? new StoredValueResponses.OrderStoredFactPaymentResponse(
            StoredValueReference.suffix(rs.getString("payment_no")), rs.getString("status"),
            money(rs.getBigDecimal("amount")), money(rs.getBigDecimal("principal_amount")),
            money(rs.getBigDecimal("bonus_amount"))) : null, normalized);
        StoredValueResponses.OrderStoredFactRefundResponse refund = jdbc.query("""
            SELECT r.refund_no, r.status, r.amount, r.principal_amount, r.bonus_amount, r.compensation_amount
            FROM stored_value_order_refund r
            JOIN biz_order o ON o.id = r.order_id
            WHERE o.order_no = ?
            """, rs -> rs.next() ? new StoredValueResponses.OrderStoredFactRefundResponse(
            StoredValueReference.suffix(rs.getString("refund_no")), rs.getString("status"),
            money(rs.getBigDecimal("amount")), money(rs.getBigDecimal("principal_amount")),
            money(rs.getBigDecimal("bonus_amount")), money(rs.getBigDecimal("compensation_amount"))) : null,
            normalized);
        if (payment == null && refund == null) {
            throw BusinessException.notFound("该订单没有储值支付或退款事实");
        }
        return new StoredValueResponses.OrderStoredFactsResponse(normalized, payment, refund);
    }

    /**
     * accountRef 是不可逆受控引用（B5-H9）：按引用反解账户主键仅做有界读取，
     * 不改变引用边界；未知引用返回 null，由调用方按空结果处理。
     */
    private Long resolveAccountIdByRef(String accountRef) {
        if (accountRef == null || accountRef.isBlank()) {
            return null;
        }
        List<Long> candidateIds = jdbc.queryForList(
            "SELECT id FROM stored_value_account ORDER BY id LIMIT 100000", Long.class);
        return candidateIds.stream()
            .filter(id -> accountRef.trim().equals(StoredValueReference.account(id)))
            .findFirst()
            .orElse(null);
    }

    public PageResult<StoredValueResponses.ReconciliationRunResponse> reconciliationRuns(PageQuery query) {
        int pageNum = pageNum(query);
        int pageSize = pageSize(query);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_reconciliation_run", Long.class);
        List<StoredValueResponses.ReconciliationRunResponse> records = jdbc.query("""
            SELECT r.id, r.run_no, r.reconcile_date, r.provider_env, r.provider_name,
                   r.scope_type, r.status,
                   (SELECT COUNT(*) FROM stored_value_reconciliation_difference_observation o WHERE o.run_id = r.id)
                     AS difference_count,
                   r.completed_at
            FROM stored_value_reconciliation_run r
            ORDER BY r.create_time DESC, r.id DESC
            LIMIT ? OFFSET ?
            """, (rs, n) -> new StoredValueResponses.ReconciliationRunResponse(
                StoredValueReference.summary(rs.getString("run_no")), rs.getDate("reconcile_date").toLocalDate(),
                rs.getString("provider_env"), rs.getString("provider_name"), rs.getString("scope_type"),
                rs.getString("status"), rs.getInt("difference_count"), dateTime(rs, "completed_at")),
            pageSize, (pageNum - 1) * pageSize);
        return PageResult.of(records, total, pageNum, pageSize);
    }

    public PageResult<StoredValueResponses.ReconciliationDifferenceResponse> reconciliationDifferences(PageQuery query) {
        int pageNum = pageNum(query);
        int pageSize = pageSize(query);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_reconciliation_difference", Long.class);
        List<StoredValueResponses.ReconciliationDifferenceResponse> records = jdbc.query("""
            SELECT d.id, d.difference_no, d.difference_type, d.provider_amount, d.local_amount,
                   d.currency, d.status, d.reason, d.ledger_transaction_id, d.create_time,
                   r.run_no
            FROM stored_value_reconciliation_difference d
            JOIN stored_value_reconciliation_run r ON r.id = d.run_id
            ORDER BY d.create_time DESC, d.id DESC
            LIMIT ? OFFSET ?
            """, (rs, n) -> new StoredValueResponses.ReconciliationDifferenceResponse(
                StoredValueReference.difference(rs.getLong("id")),
                StoredValueReference.summary(rs.getString("run_no")), rs.getString("difference_type"),
                rs.getBigDecimal("provider_amount"), rs.getBigDecimal("local_amount"),
                rs.getString("currency"), rs.getString("status"), rs.getString("reason"),
                StoredValueReference.transaction(nullableLong(rs.getObject("ledger_transaction_id"))),
                dateTime(rs, "create_time")), pageSize, (pageNum - 1) * pageSize);
        return PageResult.of(records, total, pageNum, pageSize);
    }

    public StoredValueResponses.ReconciliationRunResponse reconciliationRun(Long runId) {
        List<StoredValueResponses.ReconciliationRunResponse> records = jdbc.query("""
            SELECT r.id, r.run_no, r.reconcile_date, r.provider_env, r.provider_name,
                   r.scope_type, r.status,
                   (SELECT COUNT(*) FROM stored_value_reconciliation_difference_observation o WHERE o.run_id = r.id)
                     AS difference_count,
                   r.completed_at
            FROM stored_value_reconciliation_run r WHERE r.id = ?
            """, (rs, n) -> new StoredValueResponses.ReconciliationRunResponse(
                StoredValueReference.summary(rs.getString("run_no")), rs.getDate("reconcile_date").toLocalDate(),
                rs.getString("provider_env"), rs.getString("provider_name"), rs.getString("scope_type"),
                rs.getString("status"), rs.getInt("difference_count"), dateTime(rs, "completed_at")), runId);
        if (records.isEmpty()) {
            throw BusinessException.notFound("对账运行不存在");
        }
        return records.get(0);
    }

    private int pageNum(PageQuery query) {
        return (int) Math.max(1, query == null ? 1 : query.getPageNum());
    }

    private int pageSize(PageQuery query) {
        return (int) Math.min(100, Math.max(1, query == null ? 10 : query.getPageSize()));
    }

    private String userRef(long userId) {
        return StoredValueReference.user(userId);
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
}
