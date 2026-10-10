package com.example.venue.storedvalue.service;

import com.example.venue.storedvalue.domain.StoredValueCommands;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class StoredValueBonusExpiryService {
    private final JdbcTemplate jdbc;
    private final StoredValueLedgerService ledgerService;

    public StoredValueBonusExpiryService(JdbcTemplate jdbc, StoredValueLedgerService ledgerService) {
        this.jdbc = jdbc;
        this.ledgerService = ledgerService;
    }

    @Scheduled(fixedDelayString = "${app.stored-value.bonus-expiry-fixed-delay-ms:3600000}",
               initialDelayString = "${app.stored-value.bonus-expiry-initial-delay-ms:60000}")
    @Transactional
    public void scheduledExpire() {
        expireDue(LocalDateTime.now());
    }

    @Transactional
    public int expireDue(LocalDateTime now) {
        List<BonusDue> due = jdbc.query("""
            SELECT b.id, b.account_id, a.issuer_id, a.product_id, b.available_amount,
                   b.policy_version, b.currency, b.expires_at
            FROM stored_value_bonus_batch b
            JOIN stored_value_account a ON a.id = b.account_id
            WHERE b.status IN ('AVAILABLE','FROZEN') AND b.expires_at <= ? AND b.available_amount > 0
            ORDER BY b.expires_at, b.issued_at, b.id
            LIMIT 500
            FOR UPDATE
            """, (rs, n) -> new BonusDue(rs.getLong("id"), rs.getLong("account_id"), rs.getLong("issuer_id"),
                rs.getLong("product_id"), rs.getBigDecimal("available_amount"), rs.getString("policy_version"),
                rs.getString("currency")), now);
        for (BonusDue batch : due) {
            ledgerService.append(new StoredValueCommands.Ledger(
                batch.issuerId, batch.productId, batch.accountId, batch.currency, "BONUS_EXPIRY",
                "BONUS_EXPIRY", batch.id.toString(), "BONUS_EXPIRY:" + batch.id, batch.policyVersion,
                "赠送批次到期", null, "SYSTEM", now, List.of(
                new StoredValueCommands.LedgerLine(batch.accountId, batch.id, "BONUS_AVAILABLE", "DEBIT", batch.amount),
                new StoredValueCommands.LedgerLine(null, null, "BONUS_EXPIRY", "CREDIT", batch.amount))));
            jdbc.update("""
                UPDATE stored_value_bonus_batch
                SET status = CASE WHEN frozen_amount = 0 THEN 'EXPIRED' ELSE 'FROZEN' END
                WHERE id = ? AND status IN ('AVAILABLE','FROZEN') AND available_amount = 0
                """, batch.id);
        }
        return due.size();
    }

    private record BonusDue(Long id, Long accountId, Long issuerId, Long productId, BigDecimal amount,
                            String policyVersion, String currency) {
    }
}
