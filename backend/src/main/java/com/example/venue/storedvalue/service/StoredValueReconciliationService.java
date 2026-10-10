package com.example.venue.storedvalue.service;

import com.example.venue.common.BusinessException;
import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import com.example.venue.storedvalue.provider.StoredValueProviderDescriptor;
import com.example.venue.storedvalue.provider.StoredValueStatement;
import com.example.venue.storedvalue.provider.StoredValueStatementItem;
import com.example.venue.storedvalue.provider.StoredValueStatementProvider;
import com.example.venue.storedvalue.provider.StoredValueStatementProviderRouter;
import com.example.venue.storedvalue.provider.StoredValueStatementQuery;
import com.example.venue.storedvalue.domain.StoredValueRechargeStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stored-value daily reconciliation. Provider work is completed before the
 * short transaction that writes the run and its append-only observations.
 */
@Service
public class StoredValueReconciliationService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final Set<String> TRANSIENT_RECHARGE_STATUSES = Set.of(
        StoredValueRechargeStatus.PROVIDER_SUCCEEDED_PENDING_POSTING.name(), StoredValueRechargeStatus.POSTING.name());
    private static final Set<String> TRANSIENT_REFUND_STATUSES = Set.of("REFUNDING");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final StoredValueStatementProviderRouter providerRouter;
    private final InProcessProviderRunGuard runGuard;

    public StoredValueReconciliationService(JdbcTemplate jdbc,
                                             PlatformTransactionManager transactionManager,
                                             StoredValueStatementProviderRouter providerRouter,
                                             InProcessProviderRunGuard runGuard) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.providerRouter = providerRouter;
        this.runGuard = runGuard;
    }

    public StoredValueReconciliationResult reconcile(LocalDate date, Long operatorId) {
        return run(date, operatorId);
    }

    public StoredValueReconciliationResult run(LocalDate reconcileDate, Long operatorId) {
        LocalDate date = reconcileDate == null ? LocalDate.now(SHANGHAI) : reconcileDate;
        if (!runGuard.tryAcquire(InProcessProviderRunGuard.Lane.STORED_VALUE_RECONCILIATION)) {
            throw BusinessException.conflict("储值对账正在运行，请勿重复提交");
        }
        try {
            LocalDateTime periodStart = date.atStartOfDay();
            LocalDateTime periodEnd = date.plusDays(1).atStartOfDay();
            StoredValueStatementProvider provider = null;
            ProviderContext context = null;
            try {
                provider = providerRouter.requireDefault();
                context = providerContext(provider.descriptor());
                StoredValueStatement statement = provider.statement(new StoredValueStatementQuery(
                    date, periodStart, periodEnd, provider.descriptor()));
                Snapshot snapshot = snapshot(date, periodStart, periodEnd, context, statement);
                try {
                    return transactions.execute(status -> persist(snapshot, operatorId));
                } catch (DuplicateKeyException exception) {
                    return existingSnapshot(snapshot.snapshotKey);
                }
            } catch (RuntimeException exception) {
                String code = failureCode(exception);
                String message = failureMessage(exception);
                return recordFailure(date, periodStart, periodEnd, provider == null ? null : provider.descriptor(),
                    context, code, message, operatorId);
            }
        } finally {
            runGuard.release(InProcessProviderRunGuard.Lane.STORED_VALUE_RECONCILIATION);
        }
    }

    private StoredValueReconciliationResult persist(Snapshot snapshot, Long operatorId) {
        StoredValueReconciliationResult existing = findSnapshot(snapshot.snapshotKey);
        if (existing != null) {
            return existing;
        }
        String runNo = runNo(snapshot.date, snapshot.snapshotKey);
        jdbc.update("""
            INSERT INTO stored_value_reconciliation_run
              (run_no, reconcile_date, provider_env, provider_name, provider_file_digest,
               scope_type, statement_snapshot_key, period_start, period_end,
               provider_config_version, provider_config_digest, provider_source,
               statement_item_count, statement_summary, status, operator_id)
            VALUES (?, ?, ?, ?, ?, 'STORED_VALUE_FULL', ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?)
            """, runNo, snapshot.date, snapshot.descriptor.providerEnv(), snapshot.descriptor.providerName(),
            snapshot.statementDigest, snapshot.snapshotKey, snapshot.periodStart, snapshot.periodEnd,
            snapshot.descriptor.configVersion(), snapshot.context.configDigest, snapshot.sourceType,
            snapshot.items.size(), snapshot.summary, operatorId);
        Long runId = jdbc.queryForObject(
            "SELECT id FROM stored_value_reconciliation_run WHERE statement_snapshot_key = ?",
            Long.class, snapshot.snapshotKey);

        ComparisonState state = new ComparisonState(runId, snapshot.snapshotKey);
        compareProviderStatement(snapshot, state);
        compareLocalRecharges(snapshot, state);
        compareLocalRefunds(snapshot, state);
        compareLocalDisputes(snapshot, state);
        compareOrderPayments(snapshot.date, state);
        compareOrderRefunds(snapshot.date, state);
        compareProjections(state);

        String status = state.differenceNos.isEmpty() ? "COMPLETED" : "MANUAL_REVIEW";
        jdbc.update("""
            UPDATE stored_value_reconciliation_run
            SET status = ?, completed_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status = 'RUNNING'
            """, status, runId);
        return new StoredValueReconciliationResult(runId, runNo, snapshot.date, status,
            state.differenceNos.size());
    }

    private StoredValueReconciliationResult recordFailure(LocalDate date, LocalDateTime periodStart,
                                                           LocalDateTime periodEnd,
                                                           StoredValueProviderDescriptor descriptor,
                                                           ProviderContext context, String code,
                                                           String message, Long operatorId) {
        StoredValueProviderDescriptor safeDescriptor = descriptor == null
            ? new StoredValueProviderDescriptor("stored-value.unknown", "DEV", "UNKNOWN", "UNKNOWN", false)
            : descriptor;
        String configDigest = context == null ? null : context.configDigest;
        String snapshotKey = sha256(String.join("|", DATE.format(date), safeDescriptor.snapshotKey(),
            value(configDigest), code));
        String runNo = runNo(date, snapshotKey);
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                INSERT INTO stored_value_reconciliation_run
                  (run_no, reconcile_date, provider_env, provider_name, provider_file_digest,
                   scope_type, statement_snapshot_key, period_start, period_end,
                   provider_config_version, provider_config_digest, provider_source,
                   statement_item_count, statement_summary, failure_code, failure_message,
                   status, operator_id, completed_at)
                VALUES (?, ?, ?, ?, NULL, 'STORED_VALUE_FULL', ?, ?, ?, ?, ?, 'PROVIDER', 0,
                        NULL, ?, ?, 'FAILED', ?, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
                """, runNo, date, safeDescriptor.providerEnv(), safeDescriptor.providerName(), snapshotKey,
                periodStart, periodEnd, safeDescriptor.configVersion(), configDigest, code,
                limit(message, 512), operatorId);
        });
        return existingSnapshot(snapshotKey);
    }

    private Snapshot snapshot(LocalDate date, LocalDateTime periodStart, LocalDateTime periodEnd,
                              ProviderContext context, StoredValueStatement statement) {
        if (statement == null) {
            throw new ReconciliationFailure("PROVIDER_STATEMENT_EMPTY_RESPONSE", "Provider statement 返回为空");
        }
        List<StoredValueStatementItem> items = statement.items() == null ? List.of() : statement.items();
        String canonical = items.stream()
            .map(this::itemCanonical)
            .sorted()
            .collect(Collectors.joining("\n"));
        String digest = sha256(canonical);
        String localDigest = localSnapshotDigest(periodStart, periodEnd, context.descriptor);
        String snapshotKey = sha256(String.join("|", DATE.format(date), context.descriptor.snapshotKey(),
            digest, localDigest));
        String summary = limit(value(statement.summary()) + ";sourceRef=" + value(statement.sourceRef())
            + ";itemCount=" + items.size() + ";localDigest=" + localDigest, 512);
        return new Snapshot(date, periodStart, periodEnd, context, context.descriptor,
            value(statement.sourceType(), "DEV_FIXTURE"), digest, localDigest, snapshotKey, items, summary);
    }

    /**
     * The Provider statement is only one side of a reconciliation snapshot.
     * Include every local value consumed by the comparisons so a local repair
     * creates a new observation run instead of reusing an earlier clean run.
     */
    private String localSnapshotDigest(LocalDateTime periodStart, LocalDateTime periodEnd,
                                       StoredValueProviderDescriptor descriptor) {
        String canonical = String.join("\n",
            digestQuery("recharges", """
                SELECT r.id, r.recharge_no, r.account_id, r.principal_amount, r.currency, r.status,
                       r.provider_business_no, r.external_ref, r.provider_key, r.provider_env,
                       r.provider_name, r.provider_config_version, r.create_time, r.update_time,
                       r.posting_attempt_count, r.failure_code
                FROM stored_value_recharge r
                WHERE r.provider_key = ? AND r.provider_env = ? AND r.provider_name = ?
                  AND r.provider_config_version = ?
                  AND r.create_time < ?
                   AND ((r.create_time >= ? AND r.create_time < ?)
                   OR r.status IN ('%s','%s','%s','%s'))
                ORDER BY r.id
                """.formatted(StoredValueRechargeStatus.PROVIDER_SUCCEEDED_PENDING_POSTING.name(),
                    StoredValueRechargeStatus.POSTING.name(), StoredValueRechargeStatus.REFUNDING.name(),
                    StoredValueRechargeStatus.DISPUTED.name()),
                descriptor.providerKey(), descriptor.providerEnv(), descriptor.providerName(),
                descriptor.configVersion(), periodEnd, periodStart, periodEnd),
            digestQuery("recharge_refunds", """
                SELECT f.id, f.refund_no, f.recharge_id, f.account_id, f.amount, f.currency,
                       f.status, f.external_ref, f.create_time
                FROM stored_value_recharge_refund f
                JOIN stored_value_recharge r ON r.id = f.recharge_id
                WHERE r.provider_key = ? AND r.provider_env = ? AND r.provider_name = ?
                  AND r.provider_config_version = ?
                  AND f.create_time < ?
                  AND (f.create_time >= ? AND f.create_time < ? OR f.status IN ('REFUNDING','MANUAL_REVIEW'))
                ORDER BY f.id
                """, descriptor.providerKey(), descriptor.providerEnv(), descriptor.providerName(),
                descriptor.configVersion(), periodEnd, periodStart, periodEnd),
            digestQuery("disputes", """
                SELECT d.id, d.case_no, d.recharge_id, d.account_id, d.provider_case_id,
                       d.frozen_amount, d.currency, d.status, d.create_time
                FROM stored_value_dispute_case d
                JOIN stored_value_recharge r ON r.id = d.recharge_id
                WHERE r.provider_key = ? AND r.provider_env = ? AND r.provider_name = ?
                  AND r.provider_config_version = ?
                  AND d.create_time < ?
                  AND (d.create_time >= ? AND d.create_time < ? OR d.status IN ('OPEN','UNDER_REVIEW'))
                ORDER BY d.id
                """, descriptor.providerKey(), descriptor.providerEnv(), descriptor.providerName(),
                descriptor.configVersion(), periodEnd, periodStart, periodEnd),
            digestQuery("order_payments", """
                SELECT p.id, p.payment_no, p.account_id, p.amount, p.status, p.paid_at
                FROM stored_value_order_payment p
                WHERE p.status IN ('SUCCEEDED','REFUNDING','REFUNDED')
                  AND p.paid_at >= ? AND p.paid_at < ?
                ORDER BY p.id
                """, periodStart, periodEnd),
            digestQuery("order_payment_allocations", """
                SELECT a.id, a.payment_id, a.bucket, a.amount, a.bonus_batch_id, a.expires_at_snapshot
                FROM stored_value_order_payment_allocation a
                JOIN stored_value_order_payment p ON p.id = a.payment_id
                WHERE p.status IN ('SUCCEEDED','REFUNDING','REFUNDED')
                  AND p.paid_at >= ? AND p.paid_at < ?
                ORDER BY a.id
                """, periodStart, periodEnd),
            digestQuery("order_refunds", """
                SELECT r.id, r.refund_no, r.order_payment_id, p.account_id, r.amount, r.status,
                       r.confirmation_time
                FROM stored_value_order_refund r
                JOIN stored_value_order_payment p ON p.id = r.order_payment_id
                WHERE r.status = 'SUCCEEDED'
                  AND r.confirmation_time >= ? AND r.confirmation_time < ?
                ORDER BY r.id
                """, periodStart, periodEnd),
            digestQuery("order_refund_allocations", """
                SELECT a.id, a.refund_id, a.payment_allocation_id, a.source_bonus_batch_id,
                       a.restored_bonus_batch_id, a.bucket, a.amount
                FROM stored_value_order_refund_allocation a
                JOIN stored_value_order_refund r ON r.id = a.refund_id
                WHERE r.status = 'SUCCEEDED'
                  AND r.confirmation_time >= ? AND r.confirmation_time < ?
                ORDER BY a.id
                """, periodStart, periodEnd),
            digestQuery("accounts", """
                SELECT id, principal_available, principal_frozen, status, version, update_time
                FROM stored_value_account
                ORDER BY id
                """),
            digestQuery("bonus_batches", """
                SELECT id, account_id, available_amount, frozen_amount, status, expires_at
                FROM stored_value_bonus_batch
                ORDER BY id
                """),
            digestQuery("ledger_facts", """
                SELECT t.source_type, t.source_id, t.status, e.account_id, e.bonus_batch_id,
                       e.bucket, e.direction, SUM(e.amount) AS amount, MAX(t.id) AS ledger_transaction_id
                FROM stored_value_ledger_transaction t
                JOIN stored_value_ledger_entry e ON e.transaction_id = t.id
                WHERE e.bucket IN ('PRINCIPAL_AVAILABLE','PRINCIPAL_FROZEN','BONUS_AVAILABLE',
                                   'BONUS_FROZEN','ORDER_SETTLEMENT')
                GROUP BY t.source_type, t.source_id, t.status, e.account_id, e.bonus_batch_id,
                         e.bucket, e.direction
                ORDER BY t.source_type, t.source_id, e.account_id, e.bonus_batch_id, e.bucket, e.direction
                """));
        return sha256(canonical);
    }

    private String digestQuery(String label, String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        String values = rows.stream()
            .map(row -> row.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + digestValue(entry.getValue()))
                .collect(Collectors.joining("|")))
            .collect(Collectors.joining("\n"));
        return label + "\n" + values;
    }

    private String digestValue(Object value) {
        if (value == null) {
            return "<NULL>";
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private ProviderContext providerContext(StoredValueProviderDescriptor descriptor) {
        List<ProviderConfigRow> rows = jdbc.query("""
            SELECT id, product_id, provider_key, provider_env, provider_name, config_version,
                   enabled, manual_confirmed, merchant_identifier_digest, status
            FROM stored_value_provider_config
            WHERE provider_key = ? AND provider_env = ? AND provider_name = ?
              AND config_version = ? AND enabled = 1 AND status = 'ACTIVE'
            ORDER BY product_id, id
            """, (rs, n) -> new ProviderConfigRow(rs.getLong("id"), rs.getLong("product_id"),
            rs.getString("provider_key"), rs.getString("provider_env"), rs.getString("provider_name"),
            rs.getString("config_version"), rs.getBoolean("enabled"), rs.getBoolean("manual_confirmed"),
            rs.getString("merchant_identifier_digest"), rs.getString("status")), descriptor.providerKey(),
            descriptor.providerEnv(), descriptor.providerName(), descriptor.configVersion());
        if (rows.isEmpty()) {
            throw new ReconciliationFailure("STORED_VALUE_PROVIDER_CONFIG_NOT_FOUND",
                "储值对账 Provider 没有可用配置");
        }
        String canonical = rows.stream().map(row -> String.join("|", String.valueOf(row.id),
            String.valueOf(row.productId), value(row.providerKey), value(row.providerEnv),
            value(row.providerName), value(row.configVersion), String.valueOf(row.enabled),
            String.valueOf(row.manualConfirmed), value(row.merchantIdentifierDigest), value(row.status)))
            .collect(Collectors.joining("\n"));
        return new ProviderContext(descriptor, sha256(canonical), rows);
    }

    private void compareProviderStatement(Snapshot snapshot, ComparisonState state) {
        Map<String, List<StoredValueStatementItem>> indexed = new HashMap<>();
        for (StoredValueStatementItem item : snapshot.items) {
            String key = providerKey(item);
            indexed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
            if (item == null || !Set.of("RECHARGE", "REFUND", "DISPUTE").contains(normalize(item.recordType()))) {
                state.add("PROVIDER_UNSUPPORTED_RECORD", key, null, null, null, null, null,
                    null, null, "Provider statement 记录类型不可识别");
            }
            if (item != null && item.dataGap() != null && !item.dataGap().isBlank()) {
                state.add("PROVIDER_STATEMENT_DATA_GAP", key, null, null, null, null, null,
                    item.amount(), null, "Provider statement 数据缺口: " + limit(item.dataGap(), 256));
            }
        }
        for (Map.Entry<String, List<StoredValueStatementItem>> entry : indexed.entrySet()) {
            if (entry.getValue().size() > 1) {
                BigDecimal amount = entry.getValue().stream().map(StoredValueStatementItem::amount)
                    .filter(value -> value != null).reduce(ZERO, BigDecimal::add);
                state.add("PROVIDER_DUPLICATE_RECORD", entry.getKey(), null, null, null, null, null,
                    amount, null, "Provider statement 出现重复业务记录");
            }
        }
    }

    private void compareLocalRecharges(Snapshot snapshot, ComparisonState state) {
        List<RechargeRow> rows = jdbc.query("""
            SELECT r.id, r.recharge_no, r.account_id, r.principal_amount, r.currency, r.status,
                   r.provider_business_no, r.external_ref, r.provider_key, r.provider_env,
                   r.provider_name, r.provider_config_version, r.create_time
            FROM stored_value_recharge r
            WHERE r.provider_key = ? AND r.provider_env = ? AND r.provider_name = ?
              AND r.provider_config_version = ?
              AND r.create_time < ?
              AND ((r.create_time >= ? AND r.create_time < ?)
                   OR r.status IN ('%s','%s','%s','%s'))
            ORDER BY r.id
            """.formatted(StoredValueRechargeStatus.PROVIDER_SUCCEEDED_PENDING_POSTING.name(),
                StoredValueRechargeStatus.POSTING.name(), StoredValueRechargeStatus.REFUNDING.name(),
                StoredValueRechargeStatus.DISPUTED.name()),
            (rs, n) -> new RechargeRow(rs.getLong("id"), rs.getString("recharge_no"),
            rs.getLong("account_id"), rs.getBigDecimal("principal_amount"), rs.getString("currency"),
            rs.getString("status"), rs.getString("provider_business_no"), rs.getString("external_ref"),
            rs.getString("provider_key"), rs.getString("provider_env"), rs.getString("provider_name"),
            rs.getString("provider_config_version"), timestamp(rs, "create_time")),
            snapshot.descriptor.providerKey(), snapshot.descriptor.providerEnv(), snapshot.descriptor.providerName(),
            snapshot.descriptor.configVersion(), snapshot.periodEnd, snapshot.periodStart, snapshot.periodEnd);
        for (RechargeRow row : rows) {
            List<StoredValueStatementItem> matches = snapshot.items.stream()
                .filter(item -> "RECHARGE".equalsIgnoreCase(value(item.recordType())))
                .filter(item -> matchesRecharge(item, row))
                .toList();
            if (matches.isEmpty()) {
                if (TRANSIENT_RECHARGE_STATUSES.contains(row.status)) {
                    state.add("RECHARGE_PENDING_PROVIDER", row.rechargeNo, row.id, null, null, row.accountId,
                        row.externalRef, null, row.principalAmount, "渠道成功或入账处理中，但 statement 尚无对应记录");
                } else if (StoredValueRechargeStatus.any(row.status, StoredValueRechargeStatus.SUCCEEDED,
                    StoredValueRechargeStatus.REFUNDED, StoredValueRechargeStatus.DISPUTED)) {
                    state.add("LOCAL_RECHARGE_MISSING_PROVIDER", row.rechargeNo, row.id, null, null, row.accountId,
                        row.externalRef, null, row.principalAmount, "本地充值事实缺少 Provider statement");
                } else if (StoredValueRechargeStatus.any(row.status, StoredValueRechargeStatus.INITIATED,
                    StoredValueRechargeStatus.WAITING_PROVIDER)) {
                    state.add("RECHARGE_IN_FLIGHT", row.rechargeNo, row.id, null, null, row.accountId,
                        row.externalRef, null, row.principalAmount, "充值仍在渠道处理中");
                }
                continue;
            }
            StoredValueStatementItem providerItem = matches.get(0);
            if (TRANSIENT_RECHARGE_STATUSES.contains(row.status)) {
                state.add("RECHARGE_PENDING_POSTING", row.rechargeNo, row.id, null, null, row.accountId,
                    row.externalRef, providerItem.amount(), row.principalAmount,
                    "Provider 已有成功事实，但本地充值尚未完成入账");
            } else if (StoredValueRechargeStatus.any(row.status, StoredValueRechargeStatus.INITIATED,
                StoredValueRechargeStatus.WAITING_PROVIDER)) {
                state.add("RECHARGE_PROVIDER_SUCCESS_NOT_APPLIED", row.rechargeNo, row.id, null, null,
                    row.accountId, row.externalRef, providerItem.amount(), row.principalAmount,
                    "Provider statement 成功，但本地充值状态仍未进入待入账");
            } else {
                resolveTransient(snapshot.snapshotKey, "RECHARGE_PENDING_POSTING", row.rechargeNo);
                resolveTransient(snapshot.snapshotKey, "RECHARGE_PENDING_PROVIDER", row.rechargeNo);
                resolveTransient(snapshot.snapshotKey, "RECHARGE_PROVIDER_SUCCESS_NOT_APPLIED", row.rechargeNo);
            }
            if (money(row.principalAmount).compareTo(money(providerItem.amount())) != 0) {
                state.add("PROVIDER_RECHARGE_AMOUNT_DIFFERENCE", row.rechargeNo, row.id, null, null, row.accountId,
                    row.externalRef, providerItem.amount(), row.principalAmount, "Provider 充值金额与本地本金不一致");
            }
            if (StoredValueRechargeStatus.any(row.status, StoredValueRechargeStatus.SUCCEEDED,
                StoredValueRechargeStatus.REFUNDED, StoredValueRechargeStatus.DISPUTED)) {
                BigDecimal ledger = ledgerNet("RECHARGE", row.rechargeNo, "PRINCIPAL_AVAILABLE");
                if (ledger.compareTo(money(row.principalAmount)) != 0) {
                    state.add("RECHARGE_LEDGER_DIFFERENCE", row.rechargeNo, row.id, null,
                        latestLedgerId("RECHARGE", row.rechargeNo), row.accountId, row.externalRef,
                        row.principalAmount, ledger, "充值本金与账本可用本金分录不一致");
                }
            }
        }
        compareProviderOnlyRecharges(snapshot, rows, state);
    }

    private void compareProviderOnlyRecharges(Snapshot snapshot, List<RechargeRow> local,
                                              ComparisonState state) {
        for (StoredValueStatementItem item : snapshot.items) {
            if (!"RECHARGE".equalsIgnoreCase(value(item.recordType()))) {
                continue;
            }
            boolean found = local.stream().anyMatch(row -> matchesRecharge(item, row));
            if (!found) {
                state.add("PROVIDER_RECHARGE_MISSING_LOCAL", providerKey(item), null, null, null, null,
                    item.externalRef(), item.amount(), null, "Provider 充值事实缺少本地充值");
            }
            if (!"SUCCEEDED".equalsIgnoreCase(value(item.status()))) {
                state.add("PROVIDER_RECHARGE_PENDING", providerKey(item), null, null, null, null,
                    item.externalRef(), item.amount(), null, "Provider 充值仍处于待结算状态");
            }
        }
    }

    private void compareLocalRefunds(Snapshot snapshot, ComparisonState state) {
        List<RefundRow> rows = jdbc.query("""
            SELECT f.id, f.refund_no, f.recharge_id, f.account_id, f.amount, f.currency,
                   f.status, f.external_ref, f.create_time
            FROM stored_value_recharge_refund f
            JOIN stored_value_recharge r ON r.id = f.recharge_id
            WHERE r.provider_key = ? AND r.provider_env = ? AND r.provider_name = ?
              AND r.provider_config_version = ?
              AND f.create_time < ?
              AND (f.create_time >= ? AND f.create_time < ? OR f.status IN ('REFUNDING','MANUAL_REVIEW'))
            ORDER BY f.id
            """, (rs, n) -> new RefundRow(rs.getLong("id"), rs.getString("refund_no"),
            rs.getLong("recharge_id"), rs.getLong("account_id"), rs.getBigDecimal("amount"),
            rs.getString("currency"), rs.getString("status"), rs.getString("external_ref"),
            timestamp(rs, "create_time")), snapshot.descriptor.providerKey(), snapshot.descriptor.providerEnv(),
            snapshot.descriptor.providerName(), snapshot.descriptor.configVersion(), snapshot.periodEnd,
            snapshot.periodStart, snapshot.periodEnd);
        for (RefundRow row : rows) {
            List<StoredValueStatementItem> matches = snapshot.items.stream()
                .filter(item -> "REFUND".equalsIgnoreCase(value(item.recordType())))
                .filter(item -> matchesRefund(item, row))
                .toList();
            if (matches.isEmpty()) {
                if ("SUCCEEDED".equals(row.status)) {
                    state.add("LOCAL_REFUND_MISSING_PROVIDER", row.refundNo, null, row.rechargeId, null, row.accountId,
                        row.externalRef, null, row.amount, "本地充值退款事实缺少 Provider statement");
                } else if (TRANSIENT_REFUND_STATUSES.contains(row.status)) {
                    state.add("REFUND_IN_FLIGHT", row.refundNo, null, row.rechargeId, null, row.accountId,
                        row.externalRef, null, row.amount, "充值退款仍在渠道处理中");
                } else {
                    state.add("PROVIDER_REFUND_FAILED", row.refundNo, null, row.rechargeId, null, row.accountId,
                        row.externalRef, null, row.amount, "本地充值退款未成功收敛: " + row.status);
                }
                continue;
            }
            StoredValueStatementItem providerItem = matches.get(0);
            if ("SUCCEEDED".equals(row.status)) {
                resolveTransient(snapshot.snapshotKey, "REFUND_IN_FLIGHT", row.refundNo);
            } else if (TRANSIENT_REFUND_STATUSES.contains(row.status)) {
                state.add("REFUND_IN_FLIGHT", row.refundNo, null, row.rechargeId, null, row.accountId,
                    row.externalRef, providerItem.amount(), row.amount, "充值退款待本地收口");
            } else {
                state.add("PROVIDER_REFUND_FAILED", row.refundNo, null, row.rechargeId, null, row.accountId,
                    row.externalRef, providerItem.amount(), row.amount, "Provider 或本地充值退款失败");
            }
            if (money(row.amount).compareTo(money(providerItem.amount())) != 0) {
                state.add("PROVIDER_REFUND_AMOUNT_DIFFERENCE", row.refundNo, null, row.rechargeId, null, row.accountId,
                    row.externalRef, providerItem.amount(), row.amount, "Provider 退款金额与本地退款金额不一致");
            }
            if ("SUCCEEDED".equals(row.status)) {
                BigDecimal ledger = ledgerNet("RECHARGE_REFUND", row.refundNo, "PRINCIPAL_AVAILABLE");
                if (ledger.compareTo(money(row.amount).negate()) != 0) {
                    state.add("REFUND_LEDGER_DIFFERENCE", row.refundNo, null, row.rechargeId,
                        latestLedgerId("RECHARGE_REFUND", row.refundNo), row.accountId, row.externalRef,
                        row.amount.negate(), ledger, "充值退款与本金冲销分录不一致");
                }
            }
        }
        for (StoredValueStatementItem item : snapshot.items) {
            if (!"REFUND".equalsIgnoreCase(value(item.recordType()))) {
                continue;
            }
            boolean found = rows.stream().anyMatch(row -> matchesRefund(item, row));
            if (!found) {
                state.add("PROVIDER_REFUND_MISSING_LOCAL", providerKey(item), null, null, null, null,
                    item.externalRef(), item.amount(), null, "Provider 退款事实缺少本地充值退款");
            }
            if (Set.of("FAILED", "MANUAL_REVIEW").contains(normalize(item.status()))) {
                state.add("PROVIDER_REFUND_FAILED", providerKey(item), null, null, null, null,
                    item.externalRef(), item.amount(), null, "Provider statement 标记退款失败");
            }
        }
    }

    private void compareLocalDisputes(Snapshot snapshot, ComparisonState state) {
        List<DisputeRow> rows = jdbc.query("""
            SELECT d.id, d.case_no, d.recharge_id, d.account_id, d.provider_case_id,
                   d.frozen_amount, d.currency, d.status, d.create_time
            FROM stored_value_dispute_case d
            JOIN stored_value_recharge r ON r.id = d.recharge_id
            WHERE r.provider_key = ? AND r.provider_env = ? AND r.provider_name = ?
              AND r.provider_config_version = ?
              AND d.create_time < ?
              AND (d.create_time >= ? AND d.create_time < ? OR d.status IN ('OPEN','UNDER_REVIEW'))
            ORDER BY d.id
            """, (rs, n) -> new DisputeRow(rs.getLong("id"), rs.getString("case_no"),
            rs.getLong("recharge_id"), rs.getLong("account_id"), rs.getString("provider_case_id"),
            rs.getBigDecimal("frozen_amount"), rs.getString("currency"), rs.getString("status")),
            snapshot.descriptor.providerKey(), snapshot.descriptor.providerEnv(), snapshot.descriptor.providerName(),
            snapshot.descriptor.configVersion(), snapshot.periodEnd, snapshot.periodStart, snapshot.periodEnd);
        for (DisputeRow row : rows) {
            List<StoredValueStatementItem> matches = snapshot.items.stream()
                .filter(item -> "DISPUTE".equalsIgnoreCase(value(item.recordType())))
                .filter(item -> matchesDispute(item, row))
                .toList();
            if (matches.isEmpty()) {
                state.add("DISPUTE_MISSING_PROVIDER", row.caseNo, row.rechargeId, null, null, row.accountId,
                    row.providerCaseId, null, row.frozenAmount, "本地拒付/争议事实缺少 Provider statement");
                continue;
            }
            StoredValueStatementItem providerItem = matches.get(0);
            if (money(row.frozenAmount).compareTo(money(providerItem.amount())) != 0) {
                state.add("DISPUTE_AMOUNT_DIFFERENCE", row.caseNo, row.rechargeId, null, null, row.accountId,
                    row.providerCaseId, providerItem.amount(), row.frozenAmount, "争议冻结金额与 Provider 不一致");
            }
            if (Set.of("OPEN", "UNDER_REVIEW").contains(row.status)) {
                BigDecimal ledger = ledgerNet("DISPUTE", String.valueOf(row.id), "PRINCIPAL_FROZEN")
                    .add(ledgerNet("DISPUTE", String.valueOf(row.id), "BONUS_FROZEN"));
                if (ledger.compareTo(money(row.frozenAmount)) != 0) {
                    state.add("DISPUTE_LEDGER_DIFFERENCE", row.caseNo, row.rechargeId, null,
                        latestLedgerId("DISPUTE", String.valueOf(row.id)), row.accountId, row.providerCaseId,
                        row.frozenAmount, ledger, "争议冻结金额与冻结账本不一致");
                }
            }
        }
        for (StoredValueStatementItem item : snapshot.items) {
            if ("DISPUTE".equalsIgnoreCase(value(item.recordType()))
                && rows.stream().noneMatch(row -> matchesDispute(item, row))) {
                state.add("PROVIDER_DISPUTE_MISSING_LOCAL", providerKey(item), null, null, null, null,
                    item.providerCaseId(), item.amount(), null, "Provider 争议事实缺少本地案件");
            }
        }
    }

    private void compareOrderPayments(LocalDate date, ComparisonState state) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        List<PaymentCheck> payments = jdbc.query("""
            SELECT p.id, p.account_id, p.amount,
                   COALESCE((SELECT SUM(a.amount) FROM stored_value_order_payment_allocation a
                             WHERE a.payment_id = p.id), 0.00) AS allocation_amount,
                   COALESCE((SELECT SUM(e.amount) FROM stored_value_ledger_transaction t
                             JOIN stored_value_ledger_entry e ON e.transaction_id = t.id
                             WHERE t.source_type = 'ORDER_PAYMENT' AND t.source_id = p.payment_no
                               AND e.bucket = 'ORDER_SETTLEMENT' AND e.direction = 'CREDIT'), 0.00) AS ledger_amount,
                   (SELECT MAX(t.id) FROM stored_value_ledger_transaction t
                    WHERE t.source_type = 'ORDER_PAYMENT' AND t.source_id = p.payment_no) AS ledger_transaction_id
            FROM stored_value_order_payment p
            WHERE p.status IN ('SUCCEEDED','REFUNDING','REFUNDED')
              AND p.paid_at >= ? AND p.paid_at < ?
            """, (rs, n) -> new PaymentCheck(rs.getLong("id"), rs.getLong("account_id"),
            rs.getBigDecimal("amount"), rs.getBigDecimal("allocation_amount"),
            rs.getBigDecimal("ledger_amount"), nullableLong(rs.getObject("ledger_transaction_id"))), start, end);
        for (PaymentCheck payment : payments) {
            if (money(payment.amount).compareTo(money(payment.allocationAmount)) != 0) {
                state.add("ORDER_PAYMENT_ALLOCATION_DIFFERENCE", String.valueOf(payment.id), payment.id,
                    null, payment.ledgerTransactionId, payment.accountId, null, payment.amount,
                    payment.allocationAmount, "订单储值支付与不可变分摊快照不一致");
            }
            if (money(payment.amount).compareTo(money(payment.ledgerAmount)) != 0) {
                state.add("ORDER_PAYMENT_LEDGER_DIFFERENCE", String.valueOf(payment.id), payment.id,
                    null, payment.ledgerTransactionId, payment.accountId, null, payment.amount,
                    payment.ledgerAmount, "订单储值支付与储值账本分录不一致");
            }
        }
    }

    private void compareOrderRefunds(LocalDate date, ComparisonState state) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        List<RefundCheck> refunds = jdbc.query("""
            SELECT r.id, p.account_id, r.amount,
                   COALESCE((SELECT SUM(a.amount) FROM stored_value_order_refund_allocation a
                             WHERE a.refund_id = r.id), 0.00) AS allocation_amount,
                   COALESCE((SELECT SUM(e.amount) FROM stored_value_ledger_transaction t
                             JOIN stored_value_ledger_entry e ON e.transaction_id = t.id
                             WHERE t.source_type = 'ORDER_REFUND' AND t.source_id = r.refund_no
                               AND e.bucket = 'ORDER_SETTLEMENT' AND e.direction = 'DEBIT'), 0.00) AS ledger_amount,
                   (SELECT MAX(t.id) FROM stored_value_ledger_transaction t
                    WHERE t.source_type = 'ORDER_REFUND' AND t.source_id = r.refund_no) AS ledger_transaction_id
            FROM stored_value_order_refund r
            JOIN stored_value_order_payment p ON p.id = r.order_payment_id
            WHERE r.status = 'SUCCEEDED'
              AND r.confirmation_time >= ? AND r.confirmation_time < ?
            """, (rs, n) -> new RefundCheck(rs.getLong("id"), rs.getLong("account_id"),
            rs.getBigDecimal("amount"), rs.getBigDecimal("allocation_amount"),
            rs.getBigDecimal("ledger_amount"), nullableLong(rs.getObject("ledger_transaction_id"))), start, end);
        for (RefundCheck refund : refunds) {
            if (money(refund.amount).compareTo(money(refund.allocationAmount)) != 0) {
                state.add("ORDER_REFUND_ALLOCATION_DIFFERENCE", String.valueOf(refund.id), null, refund.id,
                    refund.ledgerTransactionId, refund.accountId, null, refund.amount,
                    refund.allocationAmount, "订单退款与不可变退款分摊快照不一致");
            }
            if (money(refund.amount).compareTo(money(refund.ledgerAmount)) != 0) {
                state.add("ORDER_REFUND_LEDGER_DIFFERENCE", String.valueOf(refund.id), null, refund.id,
                    refund.ledgerTransactionId, refund.accountId, null, refund.amount,
                    refund.ledgerAmount, "订单退款与储值原桶/补偿分录不一致");
            }
        }
    }

    private void compareProjections(ComparisonState state) {
        List<ProjectionCheck> projections = jdbc.query("""
            SELECT a.id AS account_id, 'PRINCIPAL_AVAILABLE' AS bucket, CAST(NULL AS UNSIGNED) AS bonus_batch_id,
                   a.principal_available AS projected_amount,
                   COALESCE((SELECT SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END)
                             FROM stored_value_ledger_entry e WHERE e.account_id = a.id
                               AND e.bonus_batch_id IS NULL AND e.bucket = 'PRINCIPAL_AVAILABLE'), 0.00) AS rebuilt_amount,
                   (SELECT MAX(t.id) FROM stored_value_ledger_entry e JOIN stored_value_ledger_transaction t
                    ON t.id = e.transaction_id WHERE e.account_id = a.id AND e.bonus_batch_id IS NULL
                      AND e.bucket = 'PRINCIPAL_AVAILABLE') AS ledger_transaction_id
            FROM stored_value_account a
            UNION ALL
            SELECT a.id, 'PRINCIPAL_FROZEN', CAST(NULL AS UNSIGNED), a.principal_frozen,
                   COALESCE((SELECT SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END)
                             FROM stored_value_ledger_entry e WHERE e.account_id = a.id
                               AND e.bonus_batch_id IS NULL AND e.bucket = 'PRINCIPAL_FROZEN'), 0.00),
                   (SELECT MAX(t.id) FROM stored_value_ledger_entry e JOIN stored_value_ledger_transaction t
                    ON t.id = e.transaction_id WHERE e.account_id = a.id AND e.bonus_batch_id IS NULL
                      AND e.bucket = 'PRINCIPAL_FROZEN')
            FROM stored_value_account a
            UNION ALL
            SELECT b.account_id, 'BONUS_AVAILABLE', b.id, b.available_amount,
                   COALESCE((SELECT SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END)
                             FROM stored_value_ledger_entry e WHERE e.bonus_batch_id = b.id
                               AND e.bucket = 'BONUS_AVAILABLE'), 0.00),
                   (SELECT MAX(t.id) FROM stored_value_ledger_entry e JOIN stored_value_ledger_transaction t
                    ON t.id = e.transaction_id WHERE e.bonus_batch_id = b.id AND e.bucket = 'BONUS_AVAILABLE')
            FROM stored_value_bonus_batch b
            UNION ALL
            SELECT b.account_id, 'BONUS_FROZEN', b.id, b.frozen_amount,
                   COALESCE((SELECT SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END)
                             FROM stored_value_ledger_entry e WHERE e.bonus_batch_id = b.id
                               AND e.bucket = 'BONUS_FROZEN'), 0.00),
                   (SELECT MAX(t.id) FROM stored_value_ledger_entry e JOIN stored_value_ledger_transaction t
                    ON t.id = e.transaction_id WHERE e.bonus_batch_id = b.id AND e.bucket = 'BONUS_FROZEN')
            FROM stored_value_bonus_batch b
            """, (rs, n) -> new ProjectionCheck(rs.getLong("account_id"), rs.getString("bucket"),
            nullableLong(rs.getObject("bonus_batch_id")), rs.getBigDecimal("projected_amount"),
            rs.getBigDecimal("rebuilt_amount"), nullableLong(rs.getObject("ledger_transaction_id"))));
        for (ProjectionCheck projection : projections) {
            if (money(projection.projected).compareTo(money(projection.rebuilt)) != 0) {
                String subject = projection.accountId + ":" + projection.bucket + ":"
                    + (projection.bonusBatchId == null ? "ACCOUNT" : projection.bonusBatchId);
                state.add("ACCOUNT_PROJECTION_DIFFERENCE", subject, null, null, projection.ledgerTransactionId,
                    projection.accountId, null, projection.rebuilt, projection.projected,
                    "账户投影与账本重建余额不一致: " + projection.bucket);
            }
        }
    }

    private void resolveTransient(String snapshotKey, String type, String subject) {
        String differenceNo = differenceNo(snapshotKey, type, subject);
        jdbc.update("UPDATE stored_value_reconciliation_difference SET status = 'RESOLVED' "
            + "WHERE difference_no = ? AND status = 'OPEN'", differenceNo);
    }

    private void insertDifference(ComparisonState state, String type, String subject, Long paymentId,
                                  Long refundId, Long rechargeId, Long ledgerTransactionId, Long accountId,
                                  String externalRef, BigDecimal providerAmount, BigDecimal localAmount,
                                  String reason) {
        String normalizedType = limit(normalize(type), 48);
        Long targetId = paymentId != null ? paymentId : refundId;
        Long storedPaymentId = normalizedType.startsWith("ORDER_PAYMENT") ? paymentId : null;
        Long storedOrderRefundId = normalizedType.startsWith("ORDER_REFUND") ? refundId : null;
        Long storedRechargeId = rechargeId;
        if (storedRechargeId == null && targetId != null
            && !normalizedType.startsWith("ORDER_PAYMENT")
            && !normalizedType.startsWith("ORDER_REFUND")
            && !normalizedType.startsWith("ACCOUNT_")) {
            storedRechargeId = targetId;
        }
        String differenceNo = differenceNo(state.snapshotKey, normalizedType, subject);
        if (!state.differenceNos.add(differenceNo)) {
            return;
        }
        jdbc.update("""
            INSERT INTO stored_value_reconciliation_difference
              (run_id, difference_no, difference_type, order_payment_id, order_refund_id, recharge_id,
               external_ref, provider_amount, local_amount, currency, status, reason,
               adjustment_transaction_id, operator_id, reviewer_id, ledger_transaction_id, account_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CNY', 'OPEN', ?, NULL, NULL, NULL, ?, ?)
            ON DUPLICATE KEY UPDATE id = id
            """, state.runId, differenceNo, normalizedType, storedPaymentId, storedOrderRefundId, storedRechargeId, externalRef,
            nullableMoney(providerAmount), nullableMoney(localAmount), limit(reason, 512),
            ledgerTransactionId, accountId);
        Long differenceId = jdbc.queryForObject(
            "SELECT id FROM stored_value_reconciliation_difference WHERE difference_no = ?",
            Long.class, differenceNo);
        jdbc.update("""
            INSERT INTO stored_value_reconciliation_difference_observation
              (difference_id, run_id, provider_amount, local_amount, status, reason)
            VALUES (?, ?, ?, ?, 'OPEN', ?)
            ON DUPLICATE KEY UPDATE id = id
            """, differenceId, state.runId, nullableMoney(providerAmount), nullableMoney(localAmount),
            limit(reason, 512));
    }

    private StoredValueReconciliationResult existingSnapshot(String snapshotKey) {
        StoredValueReconciliationResult result = findSnapshot(snapshotKey);
        if (result == null) {
            throw new IllegalStateException("reconciliation snapshot disappeared: " + snapshotKey);
        }
        return result;
    }

    private StoredValueReconciliationResult findSnapshot(String snapshotKey) {
        List<StoredValueReconciliationResult> results = jdbc.query("""
            SELECT r.id, r.run_no, r.reconcile_date, r.status,
                   (SELECT COUNT(*) FROM stored_value_reconciliation_difference_observation o
                    WHERE o.run_id = r.id) AS difference_count
            FROM stored_value_reconciliation_run r
            WHERE r.statement_snapshot_key = ?
            """, (rs, n) -> new StoredValueReconciliationResult(rs.getLong("id"), rs.getString("run_no"),
            rs.getDate("reconcile_date").toLocalDate(), rs.getString("status"), rs.getInt("difference_count")),
            snapshotKey);
        return results.isEmpty() ? null : results.get(0);
    }

    private String failureCode(RuntimeException exception) {
        if (exception instanceof ReconciliationFailure failure) {
            return failure.code;
        }
        String message = exception.getMessage();
        return message != null && message.contains("SANDBOX")
            ? "STORED_VALUE_SANDBOX_NOT_CONFIRMED" : "STORED_VALUE_STATEMENT_FAILED";
    }

    private String failureMessage(RuntimeException exception) {
        if (exception instanceof ReconciliationFailure failure) {
            return failure.getMessage();
        }
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private boolean matchesRecharge(StoredValueStatementItem item, RechargeRow row) {
        return same(item.businessRef(), row.providerBusinessNo) || same(item.businessRef(), row.rechargeNo)
            || same(item.externalRef(), row.externalRef);
    }

    private boolean matchesRefund(StoredValueStatementItem item, RefundRow row) {
        return same(item.businessRef(), row.refundNo) || same(item.externalRef(), row.externalRef);
    }

    private boolean matchesDispute(StoredValueStatementItem item, DisputeRow row) {
        return same(item.providerCaseId(), row.providerCaseId) || same(item.businessRef(), row.caseNo);
    }

    private String providerKey(StoredValueStatementItem item) {
        if (item == null) {
            return "NULL";
        }
        String key = firstNonBlank(item.recordType(), "UNKNOWN") + ":"
            + firstNonBlank(item.businessRef(), firstNonBlank(item.providerCaseId(),
            firstNonBlank(item.externalRef(), item.recordId())));
        return limit(key, 256);
    }

    private String itemCanonical(StoredValueStatementItem item) {
        if (item == null) {
            return "NULL";
        }
        return String.join("|", value(item.recordType()), value(item.recordId()), value(item.businessRef()),
            value(item.externalRef()), value(item.amount() == null ? null : money(item.amount()).toPlainString()),
            value(item.currency()), value(item.status()), value(item.providerCaseId()),
            value(item.occurredAt() == null ? null : item.occurredAt().toString()), value(item.dataGap()));
    }

    private BigDecimal ledgerNet(String sourceType, String sourceId, String bucket) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END), 0.00)
            FROM stored_value_ledger_transaction t
            JOIN stored_value_ledger_entry e ON e.transaction_id = t.id
            WHERE t.source_type = ? AND t.source_id = ? AND e.bucket = ?
            """, BigDecimal.class, sourceType, sourceId, bucket);
        return money(value);
    }

    private Long latestLedgerId(String sourceType, String sourceId) {
        return jdbc.query("""
            SELECT id FROM stored_value_ledger_transaction
            WHERE source_type = ? AND source_id = ? ORDER BY id DESC LIMIT 1
            """, rs -> rs.next() ? rs.getLong(1) : null, sourceType, sourceId);
    }

    private String runNo(LocalDate date, String snapshotKey) {
        return "SVR-RECON-" + DATE.format(date) + "-" + snapshotKey.substring(0, 24).toUpperCase(Locale.ROOT);
    }

    private String differenceNo(String snapshotKey, String type, String subject) {
        return "SVD-" + sha256(String.join("|", snapshotKey, type, value(subject)));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean same(String left, String right) {
        return left != null && right != null && left.equals(right);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal nullableMoney(BigDecimal value) {
        return value == null ? null : money(value);
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private LocalDateTime timestamp(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private String limit(String value, int length) {
        if (value == null) {
            return null;
        }
        return value.length() <= length ? value : value.substring(0, length);
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes) {
                result.append(String.format(Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private class ComparisonState {
        private final Long runId;
        private final String snapshotKey;
        private final Set<String> differenceNos = new HashSet<>();

        private ComparisonState(Long runId, String snapshotKey) {
            this.runId = runId;
            this.snapshotKey = snapshotKey;
        }

        private void add(String type, String subject, Long paymentId, Long refundId, Long ledgerId,
                         Long accountId, String externalRef, BigDecimal providerAmount,
                         BigDecimal localAmount, String reason) {
            insertDifference(this, type, subject, paymentId, refundId, null, ledgerId, accountId,
                externalRef, providerAmount, localAmount, reason);
        }
    }

    private record ProviderContext(StoredValueProviderDescriptor descriptor, String configDigest,
                                   List<ProviderConfigRow> rows) {
    }

    private record ProviderConfigRow(Long id, Long productId, String providerKey, String providerEnv,
                                     String providerName, String configVersion, boolean enabled,
                                     boolean manualConfirmed, String merchantIdentifierDigest, String status) {
    }

    private record Snapshot(LocalDate date, LocalDateTime periodStart, LocalDateTime periodEnd,
                            ProviderContext context, StoredValueProviderDescriptor descriptor,
                            String sourceType, String statementDigest, String localDigest, String snapshotKey,
                            List<StoredValueStatementItem> items, String summary) {
    }

    private record RechargeRow(Long id, String rechargeNo, Long accountId, BigDecimal principalAmount,
                               String currency, String status, String providerBusinessNo, String externalRef,
                               String providerKey, String providerEnv, String providerName,
                               String providerConfigVersion, LocalDateTime createTime) {
    }

    private record RefundRow(Long id, String refundNo, Long rechargeId, Long accountId, BigDecimal amount,
                             String currency, String status, String externalRef, LocalDateTime createTime) {
    }

    private record DisputeRow(Long id, String caseNo, Long rechargeId, Long accountId, String providerCaseId,
                              BigDecimal frozenAmount, String currency, String status) {
    }

    private record PaymentCheck(Long id, Long accountId, BigDecimal amount, BigDecimal allocationAmount,
                                BigDecimal ledgerAmount, Long ledgerTransactionId) {
    }

    private record RefundCheck(Long id, Long accountId, BigDecimal amount, BigDecimal allocationAmount,
                               BigDecimal ledgerAmount, Long ledgerTransactionId) {
    }

    private record ProjectionCheck(Long accountId, String bucket, Long bonusBatchId,
                                   BigDecimal projected, BigDecimal rebuilt, Long ledgerTransactionId) {
    }

    private static final class ReconciliationFailure extends RuntimeException {
        private final String code;

        private ReconciliationFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
