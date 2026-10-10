package com.example.venue.storedvalue.service;

import com.example.venue.common.BusinessException;
import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.domain.StoredValueLedgerBucket;
import com.example.venue.storedvalue.domain.StoredValueLedgerDirection;
import com.example.venue.storedvalue.domain.StoredValueMoney;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Append-only ledger boundary. No public method mutates a balance without a ledger transaction. */
@Service
public class StoredValueLedgerService {
    private final JdbcTemplate jdbc;

    public StoredValueLedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public StoredValueLedgerResult append(StoredValueCommands.Ledger command) {
        validate(command);
        String payloadHash = payloadHash(command);
        Long existingId = findTransactionId(command);
        if (existingId != null) {
            String existingHash = jdbc.queryForObject(
                "SELECT payload_hash FROM stored_value_ledger_transaction WHERE id = ?", String.class, existingId);
            if (!payloadHash.equals(existingHash)) {
                throw BusinessException.conflict("账本业务键对应不同事实");
            }
            return new StoredValueLedgerResult(existingId, true);
        }

        if (command.accountId() != null) {
            lockAccount(command.accountId());
        }
        validateLineScopes(command);
        Long transactionId;
        try {
            jdbc.update("""
                INSERT INTO stored_value_ledger_transaction
                  (issuer_id, product_id, account_id, currency, transaction_type,
                   source_type, source_id, business_key, payload_hash, policy_version,
                   status, reason, operator_id, system_source, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?)
                """, command.issuerId(), command.productId(), command.accountId(), command.currency(),
                command.transactionType(), command.sourceType(), command.sourceId(), command.businessKey(),
                payloadHash, command.policyVersion(), command.reason(), command.operatorId(), command.systemSource(),
                command.occurredAt() == null ? LocalDateTime.now() : command.occurredAt());
            transactionId = jdbc.queryForObject(
                "SELECT id FROM stored_value_ledger_transaction WHERE issuer_id = ? AND product_id = ? AND currency = ? AND business_key = ?",
                Long.class, command.issuerId(), command.productId(), command.currency(), command.businessKey());
            int lineNo = 1;
            for (StoredValueCommands.LedgerLine line : command.lines()) {
                jdbc.update("""
                    INSERT INTO stored_value_ledger_entry
                      (transaction_id, line_no, account_id, bonus_batch_id, bucket, direction,
                       amount, currency, issuer_id, product_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, transactionId, lineNo++, line.accountId(), line.bonusBatchId(),
                    line.bucket(), line.direction(), line.amount(), command.currency(), command.issuerId(), command.productId());
            }
            jdbc.update("""
                UPDATE stored_value_ledger_transaction
                SET status = 'POSTED'
                WHERE id = ? AND status = 'OPEN'
                """, transactionId);
            applyProjection(command.lines());
        } catch (DuplicateKeyException exception) {
            Long concurrentId = findTransactionId(command);
            if (concurrentId == null) {
                throw exception;
            }
            String concurrentHash = jdbc.queryForObject(
                "SELECT payload_hash FROM stored_value_ledger_transaction WHERE id = ?", String.class, concurrentId);
            if (!payloadHash.equals(concurrentHash)) {
                throw BusinessException.conflict("账本业务键对应不同事实");
            }
            return new StoredValueLedgerResult(concurrentId, true);
        }
        return new StoredValueLedgerResult(transactionId, false);
    }

    @Transactional
    public void rebuildAccountProjection(Long accountId) {
        lockAccount(accountId);
        BigDecimal principalAvailable = balance(accountId, "PRINCIPAL_AVAILABLE", null);
        BigDecimal principalFrozen = balance(accountId, "PRINCIPAL_FROZEN", null);
        jdbc.update("""
            UPDATE stored_value_account
            SET principal_available = ?, principal_frozen = ?, version = version + 1
            WHERE id = ?
            """, principalAvailable, principalFrozen, accountId);
        List<Long> batchIds = jdbc.queryForList(
            "SELECT id FROM stored_value_bonus_batch WHERE account_id = ? FOR UPDATE", Long.class, accountId);
        for (Long batchId : batchIds) {
            BigDecimal available = balance(accountId, "BONUS_AVAILABLE", batchId);
            BigDecimal frozen = balance(accountId, "BONUS_FROZEN", batchId);
            jdbc.update("UPDATE stored_value_bonus_batch SET available_amount = ?, frozen_amount = ? WHERE id = ?",
                available, frozen, batchId);
        }
    }

    public BigDecimal rebuildPrincipalAvailable(Long accountId) {
        return balance(accountId, "PRINCIPAL_AVAILABLE", null);
    }

    private void validate(StoredValueCommands.Ledger command) {
        if (command == null || command.issuerId() == null || command.productId() == null
            || command.currency() == null || !StoredValueMoney.CNY.equals(command.currency())
            || command.transactionType() == null || command.transactionType().isBlank()
            || command.sourceType() == null || command.sourceType().isBlank()
            || command.sourceId() == null || command.sourceId().isBlank()
            || command.businessKey() == null || command.businessKey().isBlank()
            || command.policyVersion() == null || command.policyVersion().isBlank()
            || command.reason() == null || command.reason().isBlank()
            || command.lines() == null || command.lines().isEmpty()) {
            throw BusinessException.badRequest("账本事实不完整");
        }
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (StoredValueCommands.LedgerLine line : command.lines()) {
            if (line == null || line.bucket() == null || line.direction() == null || line.amount() == null
                || line.amount().scale() > 2 || line.amount().signum() <= 0) {
                throw BusinessException.badRequest("账本分录金额或方向无效");
            }
            StoredValueMoney.of(line.amount());
            try {
                StoredValueLedgerBucket.valueOf(line.bucket());
            } catch (IllegalArgumentException exception) {
                throw BusinessException.badRequest("账本科目无效");
            }
            if (StoredValueLedgerDirection.DEBIT.name().equals(line.direction())) {
                debit = debit.add(line.amount());
            } else if (StoredValueLedgerDirection.CREDIT.name().equals(line.direction())) {
                credit = credit.add(line.amount());
            } else {
                throw BusinessException.badRequest("账本借贷方向无效");
            }
        }
        if (debit.compareTo(credit) != 0) {
            throw BusinessException.conflict("账本分录借贷不平衡");
        }
    }

    private void validateLineScopes(StoredValueCommands.Ledger command) {
        for (StoredValueCommands.LedgerLine line : command.lines()) {
            if (line.accountId() != null) {
                Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM stored_value_account
                    WHERE id = ? AND issuer_id = ? AND product_id = ? AND currency = ?
                    """, Integer.class, line.accountId(), command.issuerId(), command.productId(), command.currency());
                if (count == null || count != 1) {
                    throw BusinessException.conflict("账本分录跨主体、产品或币种");
                }
            }
            if (line.bonusBatchId() != null) {
                Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM stored_value_bonus_batch b
                    JOIN stored_value_account a ON a.id = b.account_id
                    WHERE b.id = ? AND a.issuer_id = ? AND a.product_id = ? AND b.currency = ?
                    """, Integer.class, line.bonusBatchId(), command.issuerId(), command.productId(), command.currency());
                if (count == null || count != 1) {
                    throw BusinessException.conflict("赠送分录跨主体、产品或币种");
                }
                if (line.accountId() != null) {
                    Integer sameAccount = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM stored_value_bonus_batch
                        WHERE id = ? AND account_id = ?
                        """, Integer.class, line.bonusBatchId(), line.accountId());
                    if (sameAccount == null || sameAccount != 1) {
                        throw BusinessException.conflict("赠送分录账户与批次不一致");
                    }
                }
            }
        }
    }

    private void applyProjection(List<StoredValueCommands.LedgerLine> lines) {
        for (StoredValueCommands.LedgerLine line : lines) {
            int sign = "CREDIT".equals(line.direction()) ? 1 : -1;
            String bucket = line.bucket();
            if ("PRINCIPAL_AVAILABLE".equals(bucket) || "PRINCIPAL_FROZEN".equals(bucket)) {
                if (line.accountId() == null) {
                    throw BusinessException.badRequest("本金分录必须绑定账户");
                }
                String column = "PRINCIPAL_AVAILABLE".equals(bucket) ? "principal_available" : "principal_frozen";
                BigDecimal current = jdbc.queryForObject("SELECT " + column + " FROM stored_value_account WHERE id = ? FOR UPDATE",
                    BigDecimal.class, line.accountId());
                BigDecimal next = current.add(line.amount().multiply(BigDecimal.valueOf(sign)));
                if (next.signum() < 0) {
                    throw BusinessException.conflict("账本投影不可用余额为负");
                }
                jdbc.update("UPDATE stored_value_account SET " + column + " = " + column + " + ?, version = version + 1 WHERE id = ?",
                    line.amount().multiply(BigDecimal.valueOf(sign)), line.accountId());
            } else if ("BONUS_AVAILABLE".equals(bucket) || "BONUS_FROZEN".equals(bucket)) {
                if (line.bonusBatchId() == null) {
                    throw BusinessException.badRequest("赠送分录必须绑定批次");
                }
                String column = "BONUS_AVAILABLE".equals(bucket) ? "available_amount" : "frozen_amount";
                BigDecimal current = jdbc.queryForObject("SELECT " + column + " FROM stored_value_bonus_batch WHERE id = ? FOR UPDATE",
                    BigDecimal.class, line.bonusBatchId());
                BigDecimal next = current.add(line.amount().multiply(BigDecimal.valueOf(sign)));
                if (next.signum() < 0) {
                    throw BusinessException.conflict("赠送投影不可为负");
                }
                jdbc.update("UPDATE stored_value_bonus_batch SET " + column + " = " + column + " + ? WHERE id = ?",
                    line.amount().multiply(BigDecimal.valueOf(sign)), line.bonusBatchId());
            }
        }
    }

    private void ensureNonNegative(Long accountId, String column) {
        BigDecimal value = jdbc.queryForObject("SELECT " + column + " FROM stored_value_account WHERE id = ?", BigDecimal.class, accountId);
        if (value == null || value.signum() < 0) {
            throw BusinessException.conflict("账本投影不可用余额为负");
        }
    }

    private void ensureBonusNonNegative(Long batchId, String column) {
        BigDecimal value = jdbc.queryForObject("SELECT " + column + " FROM stored_value_bonus_batch WHERE id = ?", BigDecimal.class, batchId);
        if (value == null || value.signum() < 0) {
            throw BusinessException.conflict("赠送投影不可为负");
        }
    }

    private BigDecimal balance(Long accountId, String bucket, Long batchId) {
        String filter = batchId == null ? "e.account_id = ? AND e.bonus_batch_id IS NULL" : "e.bonus_batch_id = ?";
        Long id = batchId == null ? accountId : batchId;
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END), 0.00)
            FROM stored_value_ledger_entry e
            WHERE e.bucket = ? AND """ + " " + filter, BigDecimal.class, bucket, id);
    }

    private void lockAccount(Long accountId) {
        jdbc.queryForObject("SELECT id FROM stored_value_account WHERE id = ? FOR UPDATE", Long.class, accountId);
    }

    private Long findTransactionId(StoredValueCommands.Ledger command) {
        return jdbc.query("""
            SELECT id FROM stored_value_ledger_transaction
            WHERE issuer_id = ? AND product_id = ? AND currency = ? AND business_key = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, command.issuerId(), command.productId(),
            command.currency(), command.businessKey());
    }

    private String payloadHash(StoredValueCommands.Ledger command) {
        StringBuilder canonical = new StringBuilder()
            .append(command.issuerId()).append('|').append(command.productId()).append('|')
            .append(command.accountId()).append('|').append(command.currency()).append('|')
            .append(command.transactionType()).append('|').append(command.sourceType()).append('|')
            .append(command.sourceId()).append('|').append(command.businessKey()).append('|')
            .append(command.policyVersion()).append('|').append(command.reason()).append('|')
            .append(command.operatorId()).append('|').append(command.systemSource()).append('|')
            .append(command.lines().stream().sorted(Comparator.comparing(StoredValueCommands.LedgerLine::bucket)
                .thenComparing(StoredValueCommands.LedgerLine::direction)
                .thenComparing(line -> line.amount().toPlainString()))
                .map(line -> line.accountId() + ":" + line.bonusBatchId() + ":" + line.bucket() + ":"
                    + line.direction() + ":" + line.amount().toPlainString()).toList());
        return sha256(canonical.toString());
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : bytes) {
                hex.append(String.format(Locale.ROOT, "%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
