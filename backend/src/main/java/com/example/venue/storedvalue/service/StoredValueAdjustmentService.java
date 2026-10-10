package com.example.venue.storedvalue.service;

import com.example.venue.common.BusinessException;
import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.domain.StoredValueLedgerDirection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Owns the narrow, approval-backed reconciliation adjustment boundary. A
 * caller cannot change an account balance without a balanced ledger fact.
 */
@Service
public class StoredValueAdjustmentService {
    private static final String TARGET_SELECT = """
        SELECT d.id, d.difference_no, d.status, d.recharge_id, d.account_id,
               d.provider_amount, d.local_amount, d.currency, d.adjustment_transaction_id,
               a.issuer_id, a.product_id, a.version AS account_version,
               a.principal_available, COALESCE(r.policy_version, policy.policy_version) AS policy_version
        FROM stored_value_reconciliation_difference d
        JOIN stored_value_account a ON a.id = d.account_id
        LEFT JOIN stored_value_recharge r ON r.id = d.recharge_id
        LEFT JOIN stored_value_product product ON product.id = a.product_id
        LEFT JOIN stored_value_policy policy ON policy.id = product.current_policy_id
        WHERE d.id = ?
        """;

    private final JdbcTemplate jdbc;
    private final StoredValueLedgerService ledgerService;

    public StoredValueAdjustmentService(JdbcTemplate jdbc, StoredValueLedgerService ledgerService) {
        this.jdbc = jdbc;
        this.ledgerService = ledgerService;
    }

    public Target target(Long differenceId, BigDecimal amount) {
        DifferenceRow row = load(differenceId, false);
        validateAmount(amount);
        validateTarget(row, amount);
        return new Target(differenceId, amount.setScale(2, RoundingMode.UNNECESSARY), row.currency,
            row.policyVersion, snapshot(row, amount));
    }

    @Transactional
    public AdjustmentResult execute(Long differenceId, BigDecimal amount, String approvalNo,
                                    Long executorId, Long reviewerId, String reason,
                                    String expectedSnapshot) {
        if (approvalNo == null || approvalNo.isBlank() || executorId == null || reviewerId == null
            || reason == null || reason.isBlank()) {
            throw BusinessException.badRequest("人工调账执行事实不完整");
        }
        if (executorId.equals(reviewerId)) {
            throw BusinessException.forbidden("执行人与复核人必须不同");
        }
        DifferenceRow row = load(differenceId, true);
        validateAmount(amount);
        validateTarget(row, amount);
        String currentSnapshot = snapshot(row, amount);
        if (expectedSnapshot == null || !expectedSnapshot.equals(currentSnapshot)) {
            throw BusinessException.conflict("对账差异或账户快照已变化，审批不可执行");
        }
        if (row.principalAvailable.add(amount).signum() < 0) {
            throw BusinessException.conflict("人工调账会造成本金可用余额为负");
        }
        String businessKey = "ADJUSTMENT:" + approvalNo;
        BigDecimal absolute = amount.abs().setScale(2, RoundingMode.UNNECESSARY);
        List<StoredValueCommands.LedgerLine> lines = amount.signum() > 0
            ? List.of(
                new StoredValueCommands.LedgerLine(null, null, "MANUAL_ADJUSTMENT_CLEARING",
                    StoredValueLedgerDirection.DEBIT.name(), absolute),
                new StoredValueCommands.LedgerLine(row.accountId, null, "PRINCIPAL_AVAILABLE",
                    StoredValueLedgerDirection.CREDIT.name(), absolute))
            : List.of(
                new StoredValueCommands.LedgerLine(row.accountId, null, "PRINCIPAL_AVAILABLE",
                    StoredValueLedgerDirection.DEBIT.name(), absolute),
                new StoredValueCommands.LedgerLine(null, null, "MANUAL_ADJUSTMENT_CLEARING",
                    StoredValueLedgerDirection.CREDIT.name(), absolute));
        StoredValueLedgerResult ledger = ledgerService.append(new StoredValueCommands.Ledger(
            row.issuerId, row.productId, row.accountId, row.currency, "MANUAL_ADJUSTMENT",
            "RECONCILIATION_ADJUSTMENT", businessKey, businessKey, row.policyVersion, reason.trim(),
            executorId, "ADMIN", LocalDateTime.now(), lines));
        int updated = jdbc.update("""
            UPDATE stored_value_reconciliation_difference
            SET status = 'RESOLVED', adjustment_transaction_id = ?, operator_id = ?, reviewer_id = ?
            WHERE id = ? AND status IN ('OPEN','UNDER_REVIEW') AND adjustment_transaction_id IS NULL
            """, ledger.transactionId(), executorId, reviewerId, differenceId);
        if (updated != 1) {
            throw BusinessException.conflict("对账差异已被其他调整消费");
        }
        return new AdjustmentResult(ledger.transactionId(), amount.setScale(2, RoundingMode.UNNECESSARY), "RESOLVED");
    }

    public String currentSnapshot(Long differenceId, BigDecimal amount) {
        DifferenceRow row = load(differenceId, false);
        validateAmount(amount);
        return snapshot(row, amount);
    }

    private DifferenceRow load(Long differenceId, boolean forUpdate) {
        if (differenceId == null) {
            throw BusinessException.badRequest("对账差异不能为空");
        }
        List<DifferenceRow> rows = jdbc.query(TARGET_SELECT + (forUpdate ? " FOR UPDATE" : ""),
            (rs, n) -> new DifferenceRow(
                rs.getLong("id"), rs.getString("difference_no"), rs.getString("status"),
                nullableLong(rs.getObject("recharge_id")), nullableLong(rs.getObject("account_id")),
                rs.getBigDecimal("provider_amount"), rs.getBigDecimal("local_amount"),
                rs.getString("currency"), nullableLong(rs.getObject("adjustment_transaction_id")),
                rs.getLong("issuer_id"), rs.getLong("product_id"), rs.getLong("account_version"),
                rs.getBigDecimal("principal_available"), rs.getString("policy_version")), differenceId);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("对账差异不存在");
        }
        return rows.get(0);
    }

    private void validateTarget(DifferenceRow row, BigDecimal amount) {
        if (!"OPEN".equals(row.status) && !"UNDER_REVIEW".equals(row.status)) {
            throw BusinessException.conflict("当前对账差异不能人工调账");
        }
        if (row.adjustmentTransactionId != null) {
            throw BusinessException.conflict("对账差异已经完成调整");
        }
        if (!"CNY".equals(row.currency)) {
            throw BusinessException.conflict("人工调账只支持 CNY");
        }
        if (row.policyVersion == null || row.policyVersion.isBlank()) {
            throw BusinessException.conflict("对账差异缺少政策版本");
        }
        if (row.providerAmount != null && row.localAmount != null
            && amount.compareTo(row.providerAmount.subtract(row.localAmount)) != 0) {
            throw BusinessException.conflict("人工调账金额必须等于对账差额");
        }
    }

    private String snapshot(DifferenceRow row, BigDecimal amount) {
        return String.join("|", "difference=" + row.id, "differenceNo=" + value(row.differenceNo),
            "status=" + value(row.status), "recharge=" + value(row.rechargeId),
            "account=" + value(row.accountId), "provider=" + money(row.providerAmount),
            "local=" + money(row.localAmount), "currency=" + value(row.currency),
            "adjustment=" + value(row.adjustmentTransactionId), "accountVersion=" + row.accountVersion,
            "principalAvailable=" + money(row.principalAvailable), "amount=" + money(amount));
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.scale() > 2 || amount.signum() == 0) {
            throw BusinessException.badRequest("人工调账金额必须为非零的两位小数");
        }
        amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private String money(BigDecimal value) {
        return value == null ? "null" : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private String value(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    public record Target(Long differenceId, BigDecimal amount, String currency,
                         String policyVersion, String snapshot) {
    }

    public record AdjustmentResult(Long transactionId, BigDecimal amount, String status) {
    }

    private record DifferenceRow(Long id, String differenceNo, String status, Long rechargeId, Long accountId,
                                 BigDecimal providerAmount, BigDecimal localAmount, String currency,
                                 Long adjustmentTransactionId, Long issuerId, Long productId, long accountVersion,
                                 BigDecimal principalAvailable, String policyVersion) {
    }
}
