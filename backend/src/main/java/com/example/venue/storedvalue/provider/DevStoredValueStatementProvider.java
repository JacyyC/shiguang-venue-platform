package com.example.venue.storedvalue.provider;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DEV-only statement fixture. It reads the persisted DEV provider facts and
 * never represents SANDBOX or production settlement evidence.
 */
@Component
public class DevStoredValueStatementProvider implements StoredValueStatementProvider {
    private static final StoredValueProviderDescriptor DESCRIPTOR =
        new StoredValueProviderDescriptor("stored-value.dev", "DEV", "DEV_STORED_VALUE", "DEV_V1", true);

    private final JdbcTemplate jdbc;

    public DevStoredValueStatementProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StoredValueProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public StoredValueStatement statement(StoredValueStatementQuery query) {
        if (query == null || query.periodStart() == null || query.periodEnd() == null) {
            throw new IllegalArgumentException("stored value statement query is incomplete");
        }
        List<StoredValueStatementItem> items = new ArrayList<>();
        items.addAll(jdbc.query("""
            SELECT a.id, r.recharge_no, r.provider_business_no, r.external_ref,
                   r.principal_amount, r.currency, r.status, a.update_time
            FROM stored_value_recharge_attempt a
            JOIN stored_value_recharge r ON r.id = a.recharge_id
            WHERE a.provider_key = ? AND a.provider_env = ? AND a.provider_name = ?
              AND a.config_version = ? AND a.status = 'SUCCEEDED'
              AND a.update_time >= ? AND a.update_time < ?
            ORDER BY a.id
            """, (rs, n) -> new StoredValueStatementItem(
                "RECHARGE", "ATTEMPT:" + rs.getLong("id"), rs.getString("provider_business_no"),
                rs.getString("external_ref"), rs.getBigDecimal("principal_amount"),
                rs.getString("currency"), "SUCCEEDED", null, timestamp(rs, "update_time"), null),
            DESCRIPTOR.providerKey(), DESCRIPTOR.providerEnv(), DESCRIPTOR.providerName(),
            DESCRIPTOR.configVersion(), query.periodStart(), query.periodEnd()));
        items.addAll(jdbc.query("""
            SELECT e.id, r.refund_no, r.external_ref, r.amount, r.currency,
                   e.status, e.occurred_at
            FROM stored_value_operation_evidence e
            JOIN stored_value_recharge_refund r ON r.id = e.refund_id
            WHERE e.operation_type = 'RECHARGE_REFUND'
              AND e.provider_key = ? AND e.provider_env = ? AND e.provider_name = ?
              AND e.config_version = ? AND e.occurred_at >= ? AND e.occurred_at < ?
            ORDER BY e.id
            """, (rs, n) -> new StoredValueStatementItem(
                "REFUND", "REFUND_EVIDENCE:" + rs.getLong("id"), rs.getString("refund_no"),
                rs.getString("external_ref"), rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getString("status"), null, timestamp(rs, "occurred_at"), null),
            DESCRIPTOR.providerKey(), DESCRIPTOR.providerEnv(), DESCRIPTOR.providerName(),
            DESCRIPTOR.configVersion(), query.periodStart(), query.periodEnd()));
        items.addAll(jdbc.query("""
            SELECT d.id, d.case_no, d.provider_case_id, d.frozen_amount, d.currency,
                   d.status, d.update_time
            FROM stored_value_dispute_case d
            JOIN stored_value_recharge r ON r.id = d.recharge_id
            WHERE r.provider_key = ? AND r.provider_env = ? AND r.provider_name = ?
              AND r.provider_config_version = ? AND d.update_time >= ? AND d.update_time < ?
            ORDER BY d.id
            """, (rs, n) -> new StoredValueStatementItem(
                "DISPUTE", "DISPUTE:" + rs.getLong("id"), rs.getString("case_no"),
                null, rs.getBigDecimal("frozen_amount"), rs.getString("currency"),
                rs.getString("status"), rs.getString("provider_case_id"), timestamp(rs, "update_time"), null),
            DESCRIPTOR.providerKey(), DESCRIPTOR.providerEnv(), DESCRIPTOR.providerName(),
            DESCRIPTOR.configVersion(), query.periodStart(), query.periodEnd()));
        return new StoredValueStatement("DEV_FIXTURE", DESCRIPTOR.providerName(), items,
            "DEV persisted provider facts; itemCount=" + items.size());
    }

    private LocalDateTime timestamp(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
