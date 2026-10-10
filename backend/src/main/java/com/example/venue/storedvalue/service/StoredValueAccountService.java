package com.example.venue.storedvalue.service;

import com.example.venue.common.BusinessException;
import com.example.venue.storedvalue.domain.StoredValueAccountStatus;
import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.domain.StoredValueLedgerBucket;
import com.example.venue.storedvalue.domain.StoredValueLedgerDirection;
import com.example.venue.storedvalue.domain.StoredValueRechargeStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class StoredValueAccountService {
    private final JdbcTemplate jdbc;
    private final StoredValueConfigService configService;
    private final StoredValueEligibilityService eligibilityService;
    private final StoredValueLedgerService ledgerService;

    public StoredValueAccountService(JdbcTemplate jdbc, StoredValueConfigService configService,
                                     StoredValueEligibilityService eligibilityService,
                                     StoredValueLedgerService ledgerService) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.eligibilityService = eligibilityService;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public StoredValueAccountSnapshot ensureAccount(Long userId, Long productId) {
        StoredValueProductSnapshot product = configService.requireProduct(productId);
        if (userId == null) {
            throw BusinessException.badRequest("储值账户用户不能为空");
        }
        jdbc.update("""
            INSERT INTO stored_value_account(user_id, issuer_id, product_id, currency, status)
            VALUES (?, ?, ?, ?, 'NORMAL')
            ON DUPLICATE KEY UPDATE id = id
            """, userId, product.issuerId(), product.id(), product.currency());
        return snapshotForUpdate(userId, product);
    }

    public StoredValueAccountSnapshot requireAccount(Long userId, Long productId) {
        StoredValueProductSnapshot product = configService.requireProduct(productId);
        try {
            return snapshot(userId, product);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw BusinessException.notFound("储值账户不存在");
        }
    }

    @Transactional
    public StoredValueAccountSnapshot requireNormalForRecharge(Long userId, StoredValueProductSnapshot product) {
        eligibilityService.requireEligible(userId, product);
        StoredValueAccountSnapshot account = ensureAccount(userId, product.id());
        if (!StoredValueAccountStatus.NORMAL.name().equals(account.status())) {
            throw BusinessException.forbidden("储值账户当前不允许充值");
        }
        return account;
    }

    @Transactional
    public void freezeAccount(Long accountId, Long operatorId, String reason, String businessRef) {
        if (accountId == null || operatorId == null || reason == null || reason.isBlank()
            || businessRef == null || businessRef.isBlank()) {
            throw BusinessException.badRequest("冻结事实不完整");
        }
        AccountRow account = rowForUpdate(accountId);
        if (StoredValueAccountStatus.CLOSED.name().equals(account.status)) {
            throw BusinessException.conflict("已注销账户不能冻结");
        }
        if (!StoredValueAccountStatus.FROZEN.name().equals(account.status)) {
            StoredValueProductSnapshot product = configService.requireProduct(account.productId);
            appendFreezeTransfers(account, product.policy().version(), businessRef, reason);
            jdbc.update("UPDATE stored_value_account SET status = ?, version = version + 1 WHERE id = ?",
                StoredValueAccountStatus.FROZEN.name(), accountId);
        }
        AccountRow updated = rowForUpdate(accountId);
        insertLifecycle(accountId, account.status, StoredValueAccountStatus.FROZEN.name(), reason, businessRef,
            "ADMIN", operatorId, null, updated.version);
    }

    @Transactional
    public void unfreezeAccount(Long accountId, Long operatorId, Long reviewerId, String reason, String businessRef) {
        requireAuditFields(accountId, operatorId, reason, businessRef);
        requireDifferentPeople(operatorId, reviewerId);
        AccountRow account = rowForUpdate(accountId);
        if (!StoredValueAccountStatus.FROZEN.name().equals(account.status)) {
            throw BusinessException.conflict("账户不是冻结状态");
        }
        List<FreezeTransfer> transfers = freezeTransfers(accountId);
        if (transfers.isEmpty() && hasOpenBalance(accountId)) {
            throw BusinessException.conflict("冻结账户缺少可反向恢复的账本事实");
        }
        for (FreezeTransfer transfer : transfers) {
            ledgerService.append(new StoredValueCommands.Ledger(
                transfer.issuerId, transfer.productId, accountId, transfer.currency,
                "ACCOUNT_UNFREEZE", "ACCOUNT_LIFECYCLE", businessRef,
                "ACCOUNT_UNFREEZE:" + businessRef + ":" + transfer.transferKey,
                transfer.policyVersion, reason, operatorId, "ADMIN", LocalDateTime.now(), List.of(
                new StoredValueCommands.LedgerLine(accountId, transfer.bonusBatchId,
                    transfer.frozenBucket, StoredValueLedgerDirection.DEBIT.name(), transfer.amount),
                new StoredValueCommands.LedgerLine(accountId, transfer.bonusBatchId,
                    transfer.availableBucket, StoredValueLedgerDirection.CREDIT.name(), transfer.amount))));
        }
        jdbc.update("UPDATE stored_value_account SET status = 'NORMAL', version = version + 1 WHERE id = ?", accountId);
        AccountRow updated = rowForUpdate(accountId);
        insertLifecycle(accountId, account.status, "NORMAL", reason, businessRef, "ADMIN", operatorId, reviewerId,
            updated.version);
    }

    @Transactional
    public void beginClosing(Long accountId, Long operatorId, String reason, String businessRef) {
        requireAuditFields(accountId, operatorId, reason, businessRef);
        AccountRow account = rowForUpdate(accountId);
        if (StoredValueAccountStatus.CLOSED.name().equals(account.status)
            || StoredValueAccountStatus.CLOSING.name().equals(account.status)) {
            throw BusinessException.conflict("账户已进入注销流程");
        }
        if (hasOpenFacts(accountId)) {
            throw BusinessException.conflict("账户存在余额、在途充值、退款或争议，不能注销");
        }
        jdbc.update("UPDATE stored_value_account SET status = 'CLOSING', version = version + 1 WHERE id = ?", accountId);
        insertLifecycle(accountId, account.status, "CLOSING", reason, businessRef, "ADMIN", operatorId, null,
            account.version + 1);
    }

    @Transactional
    public void close(Long accountId, Long operatorId, String reason, String businessRef) {
        requireAuditFields(accountId, operatorId, reason, businessRef);
        AccountRow account = rowForUpdate(accountId);
        if (!StoredValueAccountStatus.CLOSING.name().equals(account.status) || hasOpenFacts(accountId)) {
            throw BusinessException.conflict("账户未满足注销门禁");
        }
        jdbc.update("UPDATE stored_value_account SET status = 'CLOSED', version = version + 1 WHERE id = ?", accountId);
        insertLifecycle(accountId, account.status, "CLOSED", reason, businessRef, "ADMIN", operatorId, null,
            account.version + 1);
    }

    public StoredValueAccountSnapshot snapshotForUpdate(Long userId, StoredValueProductSnapshot product) {
        AccountRow row = jdbc.queryForObject("""
            SELECT * FROM stored_value_account
            WHERE user_id = ? AND issuer_id = ? AND product_id = ? AND currency = ?
            FOR UPDATE
            """, (rs, n) -> mapAccount(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("issuer_id"),
            rs.getLong("product_id"), rs.getString("currency"), rs.getString("status"),
            rs.getBigDecimal("principal_available"), rs.getBigDecimal("principal_frozen"), rs.getLong("version")),
            userId, product.issuerId(), product.id(), product.currency());
        return toSnapshot(row);
    }

    StoredValueAccountSnapshot snapshot(Long userId, StoredValueProductSnapshot product) {
        AccountRow row = jdbc.queryForObject("""
            SELECT * FROM stored_value_account
            WHERE user_id = ? AND issuer_id = ? AND product_id = ? AND currency = ?
            """, (rs, n) -> mapAccount(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("issuer_id"),
            rs.getLong("product_id"), rs.getString("currency"), rs.getString("status"),
            rs.getBigDecimal("principal_available"), rs.getBigDecimal("principal_frozen"), rs.getLong("version")),
            userId, product.issuerId(), product.id(), product.currency());
        return toSnapshot(row);
    }

    AccountRow rowForUpdate(Long accountId) {
        return jdbc.queryForObject("SELECT * FROM stored_value_account WHERE id = ? FOR UPDATE",
            (rs, n) -> mapAccount(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("issuer_id"),
                rs.getLong("product_id"), rs.getString("currency"), rs.getString("status"),
                rs.getBigDecimal("principal_available"), rs.getBigDecimal("principal_frozen"), rs.getLong("version")),
            accountId);
    }

    private StoredValueAccountSnapshot toSnapshot(AccountRow row) {
        BigDecimal bonusAvailable = jdbc.queryForObject("""
            SELECT COALESCE(SUM(available_amount),0.00)
            FROM stored_value_bonus_batch
            WHERE account_id = ? AND status IN ('AVAILABLE','FROZEN')
            """, BigDecimal.class, row.id);
        BigDecimal bonusFrozen = jdbc.queryForObject("""
            SELECT COALESCE(SUM(frozen_amount),0.00)
            FROM stored_value_bonus_batch WHERE account_id = ?
            """, BigDecimal.class, row.id);
        return new StoredValueAccountSnapshot(row.id, row.userId, row.issuerId, row.productId, row.currency,
            row.status, row.principalAvailable, row.principalFrozen, zero(bonusAvailable), zero(bonusFrozen), row.version);
    }

    private boolean hasOpenFacts(Long accountId) {
        BigDecimal total = jdbc.queryForObject("""
            SELECT principal_available + principal_frozen +
              COALESCE((SELECT SUM(available_amount + frozen_amount) FROM stored_value_bonus_batch
                        WHERE account_id = a.id AND status NOT IN ('EXHAUSTED','EXPIRED')), 0)
            FROM stored_value_account a WHERE a.id = ?
            """, BigDecimal.class, accountId);
        Integer pending = jdbc.queryForObject("""
            SELECT COUNT(*) FROM stored_value_recharge
            WHERE account_id = ? AND status IN ('%s','%s','%s','%s','%s','%s','%s')
            """.formatted(
                StoredValueRechargeStatus.INITIATED.name(),
                StoredValueRechargeStatus.WAITING_PROVIDER.name(),
                StoredValueRechargeStatus.PROVIDER_SUCCEEDED_PENDING_POSTING.name(),
                StoredValueRechargeStatus.POSTING.name(),
                StoredValueRechargeStatus.REFUNDING.name(),
                StoredValueRechargeStatus.DISPUTED.name(),
                StoredValueRechargeStatus.MANUAL_REVIEW.name()), Integer.class, accountId);
        Integer disputes = jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_dispute_case WHERE account_id = ? AND status NOT LIKE 'RESOLVED_%'", Integer.class, accountId);
        return (total != null && total.signum() != 0) || (pending != null && pending > 0) || (disputes != null && disputes > 0);
    }

    private boolean hasOpenBalance(Long accountId) {
        BigDecimal total = jdbc.queryForObject("""
            SELECT principal_available + principal_frozen +
              COALESCE((SELECT SUM(available_amount + frozen_amount)
                        FROM stored_value_bonus_batch WHERE account_id = a.id), 0)
            FROM stored_value_account a WHERE a.id = ?
            """, BigDecimal.class, accountId);
        return total != null && total.signum() != 0;
    }

    private void appendFreezeTransfers(AccountRow account, String policyVersion, String businessRef, String reason) {
        if (account.principalAvailable.signum() > 0) {
            appendBucketTransfer(account, null, account.principalAvailable, policyVersion, businessRef, reason,
                "ACCOUNT_FREEZE", StoredValueLedgerBucket.PRINCIPAL_AVAILABLE.name(),
                StoredValueLedgerBucket.PRINCIPAL_FROZEN.name(), "PRINCIPAL");
        }
        List<BonusAmount> bonuses = jdbc.query("""
            SELECT id, available_amount
            FROM stored_value_bonus_batch
            WHERE account_id = ? AND status IN ('AVAILABLE','FROZEN') AND available_amount > 0
            ORDER BY id
            FOR UPDATE
            """, (rs, n) -> new BonusAmount(rs.getLong("id"), rs.getBigDecimal("available_amount")), account.id);
        for (BonusAmount bonus : bonuses) {
            appendBucketTransfer(account, bonus.id, bonus.amount, policyVersion, businessRef, reason,
                "ACCOUNT_FREEZE", StoredValueLedgerBucket.BONUS_AVAILABLE.name(),
                StoredValueLedgerBucket.BONUS_FROZEN.name(), "BONUS-" + bonus.id);
        }
    }

    private void appendBucketTransfer(AccountRow account, Long bonusBatchId, BigDecimal amount,
                                      String policyVersion, String businessRef, String reason,
                                      String transactionType, String fromBucket, String toBucket,
                                      String transferKey) {
        ledgerService.append(new StoredValueCommands.Ledger(
            account.issuerId, account.productId, account.id, account.currency, transactionType,
            "ACCOUNT_LIFECYCLE", businessRef, transactionType + ":" + businessRef + ":" + transferKey,
            policyVersion, reason, null, "ADMIN", LocalDateTime.now(), List.of(
            new StoredValueCommands.LedgerLine(account.id, bonusBatchId, fromBucket,
                StoredValueLedgerDirection.DEBIT.name(), amount),
            new StoredValueCommands.LedgerLine(account.id, bonusBatchId, toBucket,
                StoredValueLedgerDirection.CREDIT.name(), amount))));
    }

    private List<FreezeTransfer> freezeTransfers(Long accountId) {
        String freezeRef = jdbc.query("""
            SELECT business_ref
            FROM stored_value_account_lifecycle_event
            WHERE account_id = ? AND to_status = 'FROZEN'
              AND (from_status IS NULL OR from_status <> 'FROZEN')
            ORDER BY id DESC
            LIMIT 1
            """, rs -> rs.next() ? rs.getString(1) : null, accountId);
        if (freezeRef == null) {
            return List.of();
        }
        return jdbc.query("""
            SELECT t.issuer_id, t.product_id, t.currency, t.policy_version,
                   e.account_id, e.bonus_batch_id, e.amount, e.bucket
            FROM stored_value_ledger_transaction t
            JOIN stored_value_ledger_entry e ON e.transaction_id = t.id
            WHERE t.transaction_type = 'ACCOUNT_FREEZE'
              AND t.source_type = 'ACCOUNT_LIFECYCLE'
              AND t.source_id = ? AND e.direction = 'CREDIT'
              AND e.bucket IN ('PRINCIPAL_FROZEN','BONUS_FROZEN')
            ORDER BY t.id, e.line_no
            """, (rs, n) -> {
            String frozenBucket = rs.getString("bucket");
            String availableBucket = StoredValueLedgerBucket.PRINCIPAL_FROZEN.name().equals(frozenBucket)
                ? StoredValueLedgerBucket.PRINCIPAL_AVAILABLE.name()
                : StoredValueLedgerBucket.BONUS_AVAILABLE.name();
            return new FreezeTransfer(rs.getLong("issuer_id"), rs.getLong("product_id"),
                rs.getString("currency"), rs.getString("policy_version"), rs.getLong("account_id"),
                rs.getObject("bonus_batch_id", Long.class), rs.getBigDecimal("amount"), frozenBucket,
                availableBucket, frozenBucket + ":" + rs.getObject("bonus_batch_id", Long.class));
        }, freezeRef);
    }

    private void insertLifecycle(Long accountId, String from, String to, String reason, String ref,
                                 String source, Long operator, Long reviewer, long version) {
        jdbc.update("""
            INSERT INTO stored_value_account_lifecycle_event
              (account_id, from_status, to_status, reason, business_ref, source_type,
               operator_id, reviewer_id, state_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, accountId, from, to, reason, ref, source, operator, reviewer, version);
    }

    private void requireDifferentPeople(Long operatorId, Long reviewerId) {
        if (operatorId == null || reviewerId == null || operatorId.equals(reviewerId)) {
            throw BusinessException.forbidden("执行人与复核人必须是不同人员");
        }
    }

    private void requireAuditFields(Long accountId, Long operatorId, String reason, String businessRef) {
        if (accountId == null || operatorId == null || reason == null || reason.isBlank()
            || businessRef == null || businessRef.isBlank()) {
            throw BusinessException.badRequest("账户生命周期审计事实不完整");
        }
    }

    private BigDecimal zero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO.setScale(2) : amount.setScale(2);
    }

    private AccountRow mapAccount(Long id, Long userId, Long issuerId, Long productId, String currency,
                                  String status, BigDecimal principalAvailable, BigDecimal principalFrozen,
                                  Long version) {
        return new AccountRow(id, userId, issuerId, productId, currency, status,
            zero(principalAvailable), zero(principalFrozen), version);
    }

    record AccountRow(Long id, Long userId, Long issuerId, Long productId, String currency, String status,
                      BigDecimal principalAvailable, BigDecimal principalFrozen, Long version) {
    }

    private record BonusAmount(Long id, BigDecimal amount) {
    }

    private record FreezeTransfer(Long issuerId, Long productId, String currency, String policyVersion,
                                  Long accountId, Long bonusBatchId, BigDecimal amount,
                                  String frozenBucket, String availableBucket, String transferKey) {
    }
}
