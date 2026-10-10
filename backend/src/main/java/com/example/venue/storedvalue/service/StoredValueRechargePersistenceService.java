package com.example.venue.storedvalue.service;

import com.example.venue.common.BusinessException;
import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.domain.StoredValueMoney;
import com.example.venue.storedvalue.domain.StoredValuePolicy;
import com.example.venue.storedvalue.domain.StoredValueRechargeStatus;
import com.example.venue.storedvalue.provider.StoredValueProviderDescriptor;
import com.example.venue.storedvalue.provider.StoredValueProviderRefundResult;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** All recharge/guard writes are short local transactions; provider calls never enter this class. */
@Service
public class StoredValueRechargePersistenceService {
    private static final long POSTING_LEASE_SECONDS = 300;

    private final JdbcTemplate jdbc;
    private final StoredValueConfigService configService;
    private final StoredValueEligibilityService eligibilityService;
    private final StoredValueAccountService accountService;
    private final StoredValueLedgerService ledgerService;

    public StoredValueRechargePersistenceService(JdbcTemplate jdbc, StoredValueConfigService configService,
                                                 StoredValueEligibilityService eligibilityService,
                                                 StoredValueAccountService accountService,
                                                 StoredValueLedgerService ledgerService) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.eligibilityService = eligibilityService;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
    }

    public PreparedRecharge findReplay(StoredValueCommands.CreateRecharge command) {
        if (command == null || command.userId() == null || command.productId() == null
            || command.clientRequestId() == null || command.clientRequestId().isBlank()) {
            return null;
        }
        ExistingRecharge existing = findByClientRequest(command.userId(), command.productId(), command.clientRequestId());
        return existing == null ? null : replay(command, existing);
    }

    @Transactional
    public PreparedRecharge prepare(StoredValueCommands.CreateRecharge command,
                                    StoredValueProviderDescriptor provider,
                                    LocalDateTime now) {
        if (command == null || provider == null) {
            throw BusinessException.badRequest("充值请求或 Provider 配置不完整");
        }
        StoredValueProductSnapshot product = configService.requireProduct(command.productId());
        configService.requireRechargeable(product);
        if (!product.environment().equals(provider.providerEnv())) {
            throw BusinessException.conflict("储值产品与 Provider 环境不匹配");
        }
        StoredValueMoney money = StoredValueMoney.of(command.currency(), command.principalAmount());
        StoredValuePolicy policy = product.policy();
        if (money.amount().compareTo(policy.minimumRecharge()) < 0
            || money.amount().compareTo(policy.maximumRecharge()) > 0) {
            throw BusinessException.badRequest("充值金额必须在 10.00 至 5000.00 元之间");
        }
        if (command.clientRequestId() == null || command.clientRequestId().isBlank()) {
            throw BusinessException.badRequest("充值幂等键不能为空");
        }
        ExistingRecharge existing = findByClientRequest(command.userId(), command.productId(), command.clientRequestId());
        if (existing != null) {
            return replay(command, existing);
        }
        String requestHash = requestHash(command, provider);
        eligibilityService.requireEligible(command.userId(), product);

        StoredValueAccountSnapshot account = accountService.requireNormalForRecharge(command.userId(), product);
        ExistingRecharge concurrent = findByClientRequest(command.userId(), command.productId(), command.clientRequestId());
        if (concurrent != null) {
            if (!requestHash.equals(concurrent.idempotencyHash)) {
                throw BusinessException.conflict("充值幂等键对应不同请求");
            }
            return toPrepared(concurrent, true);
        }
        LocalDate guardDate = policy.today(now);
        String guardMonth = policy.month(now);
        GuardRows guards = lockOrCreateGuards(account.id(), guardDate, guardMonth, policy.version());
        BigDecimal expectedBonus = policy.calculateBonus(money.amount(),
            policy.monthlyBonusLimit().subtract(guards.monthIssued).subtract(guards.monthReserved));
        BigDecimal dailyLimit = lowerLimit(policy.dailyRechargeLimit(), guards.riskDailyLimit);
        int maxPending = lowerCount(policy.maxPendingRecharges(), guards.riskMaxPending);
        int maxInitiations = lowerCount(policy.maxDailyInitiations(), guards.riskMaxInitiations);
        if (guards.daySuccessful.add(guards.dayReserved).add(money.amount()).compareTo(dailyLimit) > 0) {
            throw BusinessException.conflict("超过自然日充值金额限额");
        }
        if (guards.pendingCount >= maxPending) {
            throw BusinessException.conflict("同时处理中的充值已达到上限");
        }
        if (guards.initiationCount >= maxInitiations) {
            throw BusinessException.conflict("自然日充值发起次数已达到上限");
        }
        if (account.principalAvailable().add(account.principalFrozen()).add(guards.dayReserved()).add(money.amount())
            .compareTo(policy.principalBalanceLimit()) > 0) {
            throw BusinessException.conflict("超过账户本金余额上限");
        }
        BigDecimal pendingTotal = pendingRechargeTotal(account.id(), null);
        if (account.totalBalance().add(pendingTotal).add(money.amount()).add(expectedBonus)
            .compareTo(policy.totalBalanceLimit()) > 0) {
            throw BusinessException.conflict("超过账户本金与赠送金总余额上限");
        }
        if (guards.monthIssued.add(guards.monthReserved).add(expectedBonus)
            .compareTo(policy.monthlyBonusLimit()) > 0) {
            throw BusinessException.conflict("超过自然月赠送金上限");
        }

        String rechargeNo = "SVR-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String providerBusinessNo = "RECHARGE:" + rechargeNo;
        jdbc.update("""
            INSERT INTO stored_value_recharge
              (recharge_no, client_request_id, user_id, account_id, issuer_id, product_id,
               policy_id, policy_version, currency, principal_amount, expected_bonus_amount,
               actual_bonus_amount, status, provider_key, provider_env, provider_name,
               provider_config_version, provider_business_no, source_type, idempotency_hash,
               guard_date, guard_month)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0.00, '%s', ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(StoredValueRechargeStatus.INITIATED.name()), rechargeNo, command.clientRequestId(), command.userId(), account.id(), product.issuerId(),
            product.id(), product.policyId(), policy.version(), product.currency(), money.amount(), expectedBonus,
            provider.providerKey(), provider.providerEnv(), provider.providerName(), provider.configVersion(),
            providerBusinessNo, command.sourceType() == null ? "USER" : command.sourceType(), requestHash,
            guardDate, guardMonth);
        Long rechargeId = jdbc.queryForObject("SELECT id FROM stored_value_recharge WHERE recharge_no = ?", Long.class, rechargeNo);
        jdbc.update("""
            INSERT INTO stored_value_limit_reservation
              (recharge_id, account_id, guard_date, guard_month, principal_amount, bonus_amount)
            VALUES (?, ?, ?, ?, ?, ?)
            """, rechargeId, account.id(), guardDate, guardMonth, money.amount(), expectedBonus);
        int dayGuardUpdated = jdbc.update("""
            UPDATE stored_value_limit_day_guard
            SET reserved_amount = reserved_amount + ?, pending_count = pending_count + 1, initiation_count = initiation_count + 1
            WHERE account_id = ? AND guard_date = ?
            """, money.amount(), account.id(), guardDate);
        jdbc.update("""
            UPDATE stored_value_limit_month_guard
            SET reserved_bonus_amount = reserved_bonus_amount + ?
            WHERE account_id = ? AND guard_month = ?
            """, expectedBonus, account.id(), guardMonth);
        ledgerService.append(new StoredValueCommands.Ledger(
            product.issuerId(), product.id(), account.id(), product.currency(), "RECHARGE_LIMIT_RESERVE",
            "RECHARGE", rechargeNo, "RECHARGE:" + rechargeNo + ":LIMIT_RESERVE", policy.version(),
            "充值处理中额度预留", null, "SYSTEM", now,
            List.of(new StoredValueCommands.LedgerLine(null, null, "LIMIT_RESERVATION", "DEBIT", money.amount()),
                new StoredValueCommands.LedgerLine(null, null, "LIMIT_RESERVATION_OFFSET", "CREDIT", money.amount()))));
        jdbc.update("""
            INSERT INTO stored_value_recharge_attempt
              (recharge_id, attempt_no, provider_key, provider_env, provider_name, config_version,
               provider_business_no, status, request_digest, request_summary)
            VALUES (?, 1, ?, ?, ?, ?, ?, 'CREATED', ?, ?)
            """, rechargeId, provider.providerKey(), provider.providerEnv(), provider.providerName(),
            provider.configVersion(), providerBusinessNo, requestHash, "DEV/SANDBOX recharge create request");
        jdbc.update("UPDATE stored_value_recharge SET status = '" + StoredValueRechargeStatus.WAITING_PROVIDER.name()
            + "' WHERE id = ?", rechargeId);
        return new PreparedRecharge(rechargeId, rechargeNo, StoredValueRechargeStatus.WAITING_PROVIDER.name(), money.amount(), expectedBonus,
            providerBusinessNo, provider.providerKey(), account.id(), false);
    }

    @Transactional
    public void recordProviderCreate(PreparedRecharge prepared, StoredValueProviderDescriptor provider,
                                     com.example.venue.storedvalue.provider.StoredValueProviderCreateResult result) {
        if (prepared == null || provider == null || result == null) {
            throw BusinessException.badRequest("Provider 创建结果不完整");
        }
        RechargeRow recharge = lockRecharge(prepared.rechargeNo);
        if (StoredValueRechargeStatus.any(recharge.status, StoredValueRechargeStatus.SUCCEEDED,
            StoredValueRechargeStatus.FAILED)) {
            return;
        }
        jdbc.update("""
            UPDATE stored_value_recharge_attempt
            SET status = ?, external_ref = ?, request_digest = ?, request_summary = ?,
                response_digest = ?, response_summary = ?, failure_code = ?, update_time = CURRENT_TIMESTAMP
            WHERE recharge_id = ? AND attempt_no = 1
            """, result.failed() ? "FAILED" : "REQUESTED", result.externalRef(), result.requestDigest(),
            result.requestSummary(), result.responseDigest(), result.responseSummary(), result.failureCode(), recharge.id);
        insertOperationEvidence(recharge.id, null, provider, "RECHARGE_CREATE", recharge.rechargeNo,
            result.requestDigest(), result.requestSummary(), result.responseDigest(), result.responseSummary(),
            result.failed() ? "FAILED" : "REQUESTED", result.failureCode());
        if (result.failed()) {
            releaseReservation(recharge, StoredValueRechargeStatus.FAILED,
                result.failureCode() == null ? "PROVIDER_CREATE_FAILED" : result.failureCode());
        } else {
            jdbc.update("UPDATE stored_value_recharge SET status = '" + StoredValueRechargeStatus.WAITING_PROVIDER.name()
                + "', external_ref = ? WHERE id = ?", result.externalRef(), recharge.id);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordCallbackEvidence(StoredValueCommands.Callback callback, Long rechargeId, String digest) {
        String requestId = callback == null || callback.requestId() == null || callback.requestId().isBlank()
            ? "SVRCV-" + UUID.randomUUID().toString().replace("-", "")
            : callback.requestId();
        jdbc.update("""
            INSERT INTO stored_value_callback_evidence
              (request_id, route, recharge_id, provider_key, provider_env, provider_name, config_version,
               external_event_id, payload_digest, request_summary, received_at)
            VALUES (?, 'stored-value.callback', ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """, requestId, rechargeId, receiptValue(callback == null ? null : callback.providerKey(), 128),
            receiptValue(callback == null ? null : callback.providerEnv(), 16),
            receiptValue(callback == null ? null : callback.providerName(), 64),
            receiptValue(callback == null ? null : callback.configVersion(), 96),
            receiptValue(callback == null ? null : callback.externalEventId(), 128), digest,
            receiptValue(callback == null ? null : callback.minimalSummary(), 1024), LocalDateTime.now());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Transactional
    public void attachCallbackRecharge(String requestId, Long rechargeId) {
        jdbc.update("""
            UPDATE stored_value_callback_evidence
            SET recharge_id = ?
            WHERE request_id = ? AND recharge_id IS NULL
            """, rechargeId, requestId);
    }

    @Transactional
    public void markCallbackRejected(String requestId, String resultCode) {
        jdbc.update("""
            UPDATE stored_value_callback_evidence
            SET verification_status = 'REJECTED', processing_status = 'MANUAL_REVIEW', result_code = ?
            WHERE request_id = ?
            """, resultCode, requestId);
    }

    @Transactional
    public StoredValueRechargeResult recordCallbackOutcome(StoredValueCommands.Callback callback,
                                                            String digest,
                                                            com.example.venue.storedvalue.provider.StoredValueProviderVerification verification,
                                                            StoredValueProviderDescriptor descriptor) {
        RechargeRow recharge = lockRecharge(callback.rechargeNo());
        AttemptRow attempt = attemptRow(recharge.id);
        Long evidenceId = findEvidenceId(callback);
        boolean localMatch = same(descriptor.providerKey(), recharge.providerKey)
            && same(descriptor.providerEnv(), recharge.providerEnv)
            && same(descriptor.providerName(), recharge.providerName)
            && same(descriptor.configVersion(), recharge.providerConfigVersion)
            && same(callback.providerKey(), attempt.providerKey)
            && same(callback.providerEnv(), attempt.providerEnv)
            && same(callback.providerName(), attempt.providerName)
            && same(callback.configVersion(), attempt.configVersion)
            && same(callback.providerKey(), recharge.providerKey)
            && same(callback.providerEnv(), recharge.providerEnv)
            && same(callback.providerName(), recharge.providerName)
            && same(callback.configVersion(), recharge.providerConfigVersion)
            && "CNY".equals(recharge.currency)
            && "CNY".equals(callback.currency())
            && callback.amount() != null && callback.amount().compareTo(recharge.principalAmount) == 0
            && verification.amount() != null && verification.amount().compareTo(recharge.principalAmount) == 0
            && merchantMatches(recharge.productId, callback, attempt);
        boolean providerResponseMatches = verification.currency() != null
            && "CNY".equals(verification.currency()) && "CNY".equals(callback.currency());
        if (!verification.accepted() || !localMatch || !providerResponseMatches) {
            String code = !localMatch ? "CALLBACK_CONTRACT_MISMATCH" : verification.failureCode();
            if (!providerResponseMatches) {
                code = "PROVIDER_RESPONSE_CONTRACT_MISMATCH";
            }
            if (StoredValueRechargeStatus.SUCCEEDED.matches(recharge.status)) {
                jdbc.update("""
                    UPDATE stored_value_callback_evidence
                    SET verification_status = 'VERIFIED', processing_status = 'IGNORED', result_code = 'ALREADY_SUCCEEDED'
                    WHERE id = ?
                    """, evidenceId);
                return result(recharge.id);
            }
            jdbc.update("""
                UPDATE stored_value_callback_evidence
                SET verification_status = 'REJECTED', processing_status = 'MANUAL_REVIEW', result_code = ?
                WHERE id = ?
                """, code, evidenceId);
            if ("FAILED".equals(verification.status()) && localMatch
                && StoredValueRechargeStatus.any(recharge.status, StoredValueRechargeStatus.INITIATED,
                    StoredValueRechargeStatus.WAITING_PROVIDER)) {
                releaseReservation(recharge, StoredValueRechargeStatus.FAILED, code);
            } else {
                jdbc.update("UPDATE stored_value_recharge SET status = '" + StoredValueRechargeStatus.MANUAL_REVIEW.name()
                    + "', failure_code = ? WHERE id = ?",
                    code, recharge.id);
            }
            return result(recharge.id);
        }
        jdbc.update("""
            UPDATE stored_value_callback_evidence
            SET verification_status = 'VERIFIED', processing_status = 'UNPROCESSED', result_code = 'VERIFIED'
            WHERE id = ?
            """, evidenceId);
        if (StoredValueRechargeStatus.SUCCEEDED.matches(recharge.status)) {
            jdbc.update("""
                UPDATE stored_value_callback_evidence
                SET processing_status = 'IGNORED', result_code = 'ALREADY_SUCCEEDED'
                WHERE id = ?
                """, evidenceId);
            return result(recharge.id);
        }
        if (!"SUCCESS".equalsIgnoreCase(verification.status())) {
            if (StoredValueRechargeStatus.any(recharge.status, StoredValueRechargeStatus.INITIATED,
                StoredValueRechargeStatus.WAITING_PROVIDER)) {
                releaseReservation(recharge, StoredValueRechargeStatus.FAILED, "PROVIDER_NOT_SUCCESS");
            } else {
                jdbc.update("UPDATE stored_value_recharge SET status = '" + StoredValueRechargeStatus.MANUAL_REVIEW.name()
                    + "', failure_code = 'CONTRADICTORY_CALLBACK' WHERE id = ?",
                    recharge.id);
                jdbc.update("UPDATE stored_value_callback_evidence SET processing_status = 'MANUAL_REVIEW', result_code = 'CONTRADICTORY_CALLBACK' WHERE id = ?",
                    evidenceId);
            }
            return result(recharge.id);
        }
        if (StoredValueRechargeStatus.any(recharge.status, StoredValueRechargeStatus.TIMED_OUT,
            StoredValueRechargeStatus.CANCELED, StoredValueRechargeStatus.FAILED,
            StoredValueRechargeStatus.REFUNDING, StoredValueRechargeStatus.REFUNDED,
            StoredValueRechargeStatus.DISPUTED, StoredValueRechargeStatus.MANUAL_REVIEW)) {
            jdbc.update("UPDATE stored_value_recharge SET status = '" + StoredValueRechargeStatus.MANUAL_REVIEW.name()
                + "', failure_code = 'LATE_SUCCESS_REQUIRES_REVIEW' WHERE id = ?",
                recharge.id);
            jdbc.update("UPDATE stored_value_callback_evidence SET processing_status = 'MANUAL_REVIEW', result_code = 'LATE_SUCCESS' WHERE id = ?", evidenceId);
            return result(recharge.id);
        }
        if (!StoredValueRechargeStatus.any(recharge.status, StoredValueRechargeStatus.WAITING_PROVIDER,
            StoredValueRechargeStatus.PROVIDER_SUCCEEDED_PENDING_POSTING, StoredValueRechargeStatus.POSTING)) {
            jdbc.update("UPDATE stored_value_recharge SET status = '" + StoredValueRechargeStatus.MANUAL_REVIEW.name()
                + "', failure_code = 'CALLBACK_STATE_NOT_POSTABLE' WHERE id = ?",
                recharge.id);
            jdbc.update("UPDATE stored_value_callback_evidence SET processing_status = 'MANUAL_REVIEW', result_code = 'CALLBACK_STATE_NOT_POSTABLE' WHERE id = ?",
                evidenceId);
            return result(recharge.id);
        }
        String externalRef = verification.externalRef() == null ? callback.externalRef() : verification.externalRef();
        jdbc.update("""
            UPDATE stored_value_recharge
            SET status = CASE WHEN status = '%s'
                             THEN '%s' ELSE status END,
                external_ref = COALESCE(?, external_ref), failure_code = NULL
            WHERE id = ?
            """.formatted(StoredValueRechargeStatus.WAITING_PROVIDER.name(),
                StoredValueRechargeStatus.PROVIDER_SUCCEEDED_PENDING_POSTING.name()), externalRef, recharge.id);
        jdbc.update("""
            UPDATE stored_value_recharge_attempt
            SET status = 'SUCCEEDED', external_ref = ?
            WHERE recharge_id = ? AND attempt_no = 1
            """, externalRef, recharge.id);
        jdbc.update("UPDATE stored_value_callback_evidence SET result_code = 'PENDING_POSTING' WHERE id = ?", evidenceId);
        return result(recharge.id);
    }

    @Transactional
    public PostingClaim claimPosting(String rechargeNo, String sourceType, Long operatorId,
                                     String reason, boolean force) {
        RechargeRow recharge = lockRecharge(rechargeNo);
        if (StoredValueRechargeStatus.SUCCEEDED.matches(recharge.status)) {
            return new PostingClaim(recharge.id, recharge.rechargeNo, null, recharge.postingAttemptCount,
                false, recharge.status, sourceType, operatorId, reason);
        }
        if (!StoredValueRechargeStatus.any(recharge.status, StoredValueRechargeStatus.PROVIDER_SUCCEEDED_PENDING_POSTING,
            StoredValueRechargeStatus.POSTING)) {
            throw BusinessException.conflict("充值当前状态不能入账");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean stale = recharge.postingStartedAt == null
            || recharge.postingStartedAt.isBefore(now.minusSeconds(POSTING_LEASE_SECONDS));
        if (StoredValueRechargeStatus.POSTING.matches(recharge.status) && !force && !stale) {
            return new PostingClaim(recharge.id, recharge.rechargeNo, recharge.postingClaimToken,
                recharge.postingAttemptCount, false, recharge.status, sourceType, operatorId, reason);
        }
        String token = "SVPOST-" + UUID.randomUUID().toString().replace("-", "");
        int updated = jdbc.update("""
            UPDATE stored_value_recharge
            SET status = '%s', posting_attempt_count = posting_attempt_count + 1,
                posting_claim_token = ?, posting_started_at = ?,
                posting_last_failure_code = NULL, posting_last_failure_message = NULL
            WHERE id = ? AND (status = '%s'
                              OR (status = '%s' AND (? = TRUE OR posting_started_at IS NULL OR posting_started_at < ?)))
            """.formatted(StoredValueRechargeStatus.POSTING.name(),
                StoredValueRechargeStatus.PROVIDER_SUCCEEDED_PENDING_POSTING.name(),
                StoredValueRechargeStatus.POSTING.name()), token, now, recharge.id, force, now.minusSeconds(POSTING_LEASE_SECONDS));
        if (updated != 1) {
            throw BusinessException.conflict("充值入账执行权已被其他恢复任务取得");
        }
        return new PostingClaim(recharge.id, recharge.rechargeNo, token, recharge.postingAttemptCount + 1,
            true, StoredValueRechargeStatus.POSTING.name(), sourceType, operatorId, reason);
    }

    @Transactional
    public StoredValueRechargeResult completePosting(PostingClaim claim, String externalRef) {
        RechargeRow recharge = lockRecharge(claim.rechargeId);
        if (StoredValueRechargeStatus.SUCCEEDED.matches(recharge.status)) {
            return result(recharge.id);
        }
        if (!StoredValueRechargeStatus.POSTING.matches(recharge.status)
            || !same(claim.claimToken, recharge.postingClaimToken)) {
            throw BusinessException.conflict("充值入账执行权已失效");
        }
        AccountRow account = accountRow(recharge.accountId);
        if (!"NORMAL".equals(account.status)) {
            throw BusinessException.conflict("充值账户当前状态不能入账");
        }
        GuardRows guards = lockOrCreateGuards(account.id, recharge.guardDate, recharge.guardMonth, recharge.policyVersion);
        BigDecimal availablePrincipal = account.principalAvailable;
        BigDecimal availableTotal = account.principalAvailable.add(account.principalFrozen()).add(bonusTotal(account.id))
            .add(pendingRechargeTotal(account.id, recharge.id));
        StoredValuePolicy rechargePolicy = policy(recharge.productId, recharge.policyVersion);
        if (availablePrincipal.add(recharge.principalAmount).compareTo(rechargePolicy.principalBalanceLimit()) > 0
            || availableTotal.add(recharge.principalAmount).add(recharge.expectedBonusAmount).compareTo(rechargePolicy.totalBalanceLimit()) > 0) {
            throw BusinessException.conflict("充值入账后超过余额上限");
        }
        int dayGuardUpdated = jdbc.update("""
            UPDATE stored_value_limit_day_guard
            SET reserved_amount = reserved_amount - ?, pending_count = pending_count - 1,
                successful_amount = successful_amount + ?
            WHERE account_id = ? AND guard_date = ? AND reserved_amount >= ? AND pending_count > 0
            """, recharge.principalAmount, recharge.principalAmount, account.id, recharge.guardDate, recharge.principalAmount);
        int monthGuardUpdated = jdbc.update("""
            UPDATE stored_value_limit_month_guard
            SET reserved_bonus_amount = reserved_bonus_amount - ?, issued_bonus_amount = issued_bonus_amount + ?
            WHERE account_id = ? AND guard_month = ? AND reserved_bonus_amount >= ?
            """, recharge.expectedBonusAmount, recharge.expectedBonusAmount, account.id, recharge.guardMonth,
            recharge.expectedBonusAmount);
        if (dayGuardUpdated != 1 || monthGuardUpdated != 1) {
            throw BusinessException.conflict("充值额度预留状态不一致");
        }
        int reservationUpdated = jdbc.update("UPDATE stored_value_limit_reservation SET status = 'CONSUMED', released_at = CURRENT_TIMESTAMP WHERE recharge_id = ? AND status = 'RESERVED'", recharge.id);
        if (reservationUpdated != 1) {
            throw BusinessException.conflict("充值额度预留不存在或已处理");
        }
        ledgerService.append(new StoredValueCommands.Ledger(
            recharge.issuerId, recharge.productId, account.id, recharge.currency, "RECHARGE_PRINCIPAL_POSTING",
            "RECHARGE", recharge.rechargeNo, "RECHARGE:" + recharge.rechargeNo + ":PRINCIPAL", recharge.policyVersion,
            claim.reason == null || claim.reason.isBlank() ? "充值本金入账" : claim.reason,
            claim.operatorId, claim.operatorId == null ? "PROVIDER" : "MANUAL_RECOVERY", LocalDateTime.now(), List.of(
            new StoredValueCommands.LedgerLine(null, null, "CHANNEL_CLEARING", "DEBIT", recharge.principalAmount),
            new StoredValueCommands.LedgerLine(account.id, null, "PRINCIPAL_AVAILABLE", "CREDIT", recharge.principalAmount))));
        Long bonusBatchId = null;
        if (recharge.expectedBonusAmount.signum() > 0) {
            jdbc.update("""
                INSERT INTO stored_value_bonus_batch
                  (account_id, recharge_id, issuer_id, product_id, policy_version, currency, original_amount,
                   available_amount, frozen_amount, issued_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, 'CNY', ?, 0.00, 0.00, ?, ?, 'AVAILABLE')
                """, account.id, recharge.id, recharge.issuerId, recharge.productId, recharge.policyVersion,
                recharge.expectedBonusAmount,
                LocalDateTime.now(), rechargePolicy.bonusExpiry(LocalDateTime.now()));
            bonusBatchId = jdbc.queryForObject("SELECT id FROM stored_value_bonus_batch WHERE recharge_id = ? AND policy_version = ?",
                Long.class, recharge.id, recharge.policyVersion);
            StoredValueLedgerResult bonusLedger = ledgerService.append(new StoredValueCommands.Ledger(
                recharge.issuerId, recharge.productId, account.id, recharge.currency, "RECHARGE_BONUS_POSTING",
                "RECHARGE", recharge.rechargeNo, "RECHARGE:" + recharge.rechargeNo + ":BONUS", recharge.policyVersion,
                "充值赠送入账", null, "SYSTEM", LocalDateTime.now(), List.of(
                new StoredValueCommands.LedgerLine(null, null, "PROMOTION_FUNDING", "DEBIT", recharge.expectedBonusAmount),
                new StoredValueCommands.LedgerLine(account.id, bonusBatchId, "BONUS_AVAILABLE", "CREDIT", recharge.expectedBonusAmount))));
            jdbc.update("UPDATE stored_value_bonus_batch SET source_transaction_id = ?, available_amount = ? WHERE id = ?",
                bonusLedger.transactionId(), recharge.expectedBonusAmount, bonusBatchId);
        }
        ledgerService.append(new StoredValueCommands.Ledger(
            recharge.issuerId, recharge.productId, account.id, recharge.currency, "RECHARGE_LIMIT_RELEASE",
            "RECHARGE", recharge.rechargeNo, "RECHARGE:" + recharge.rechargeNo + ":LIMIT_RELEASE", recharge.policyVersion,
            "充值成功释放处理中额度", null, "SYSTEM", LocalDateTime.now(), List.of(
            new StoredValueCommands.LedgerLine(null, null, "LIMIT_RESERVATION_OFFSET", "DEBIT", recharge.principalAmount),
            new StoredValueCommands.LedgerLine(null, null, "LIMIT_RESERVATION", "CREDIT", recharge.principalAmount))));
        StoredValueProviderDescriptor descriptor = new StoredValueProviderDescriptor(
            recharge.providerKey, recharge.providerEnv, recharge.providerName, recharge.providerConfigVersion, true);
        String settledExternalRef = externalRef == null ? recharge.externalRef : externalRef;
        insertOperationEvidence(recharge.id, null, descriptor, "RECHARGE_POSTING",
            "RECHARGE:" + recharge.rechargeNo + ":POSTING:" + claim.postingAttemptCount,
            null, claim.sourceType == null ? "RECHARGE_POSTING" : claim.sourceType, null, null,
            "SUCCEEDED", null);
        int completed = jdbc.update("""
            UPDATE stored_value_recharge
            SET status = '%s', actual_bonus_amount = ?, external_ref = COALESCE(?, external_ref),
                posting_claim_token = NULL, posting_started_at = NULL,
                posting_last_failure_code = NULL, posting_last_failure_message = NULL
            WHERE id = ? AND status = '%s' AND posting_claim_token = ?
            """.formatted(StoredValueRechargeStatus.SUCCEEDED.name(), StoredValueRechargeStatus.POSTING.name()),
            recharge.expectedBonusAmount, settledExternalRef, recharge.id, claim.claimToken);
        if (completed != 1) {
            throw BusinessException.conflict("充值入账状态在提交前发生变化");
        }
        jdbc.update("UPDATE stored_value_recharge_attempt SET status = 'SUCCEEDED', external_ref = ? WHERE recharge_id = ? AND attempt_no = 1",
            settledExternalRef, recharge.id);
        jdbc.update("""
            UPDATE stored_value_callback_evidence
            SET processing_status = 'POSTED', result_code = 'POSTED'
            WHERE recharge_id = ? AND verification_status = 'VERIFIED' AND processing_status = 'UNPROCESSED'
            """, recharge.id);
        return result(recharge.id);
    }

    @Transactional
    public void recordPostingFailure(PostingClaim claim, String failureCode, String failureMessage) {
        jdbc.update("""
            UPDATE stored_value_recharge
            SET posting_last_failure_code = ?, posting_last_failure_message = ?
            WHERE id = ? AND status = '%s' AND posting_claim_token = ?
            """.formatted(StoredValueRechargeStatus.POSTING.name()),
            limitValue(failureCode, 64), limitValue(failureMessage, 512), claim.rechargeId, claim.claimToken);
    }

    @Transactional
    public void markPostingManualReview(PostingClaim claim, String failureCode, String failureMessage) {
        jdbc.update("""
            UPDATE stored_value_recharge
            SET status = '%s', failure_code = ?, posting_last_failure_code = ?,
                posting_last_failure_message = ?, posting_claim_token = NULL
            WHERE id = ? AND status = '%s' AND posting_claim_token = ?
            """.formatted(StoredValueRechargeStatus.MANUAL_REVIEW.name(), StoredValueRechargeStatus.POSTING.name()),
            limitValue(failureCode, 64), limitValue(failureCode, 64), limitValue(failureMessage, 512),
            claim.rechargeId, claim.claimToken);
        jdbc.update("""
            UPDATE stored_value_callback_evidence
            SET processing_status = 'MANUAL_REVIEW', result_code = ?
            WHERE recharge_id = ? AND verification_status = 'VERIFIED' AND processing_status = 'UNPROCESSED'
            """, limitValue(failureCode, 64), claim.rechargeId);
    }

    public List<String> findRecoverablePostingNos(int limit, LocalDateTime now) {
        int boundedLimit = Math.max(1, Math.min(200, limit));
        LocalDateTime staleBefore = now.minusSeconds(POSTING_LEASE_SECONDS);
        return jdbc.query("""
            SELECT recharge_no
            FROM stored_value_recharge
            WHERE status = '%s'
               OR (status = '%s' AND (posting_started_at IS NULL OR posting_started_at < ?))
            ORDER BY update_time, id
            LIMIT ?
            """.formatted(StoredValueRechargeStatus.PROVIDER_SUCCEEDED_PENDING_POSTING.name(),
                StoredValueRechargeStatus.POSTING.name()), (rs, n) -> rs.getString(1), staleBefore, boundedLimit);
    }

    @Transactional
    public void releaseByNo(String rechargeNo, String status, String failureCode) {
        StoredValueRechargeStatus target = StoredValueRechargeStatus.parse(status);
        if (!target.isReservationReleaseTarget()) {
            throw new IllegalArgumentException("充值状态不能用于释放处理中额度: " + target);
        }
        releaseReservation(lockRecharge(rechargeNo), target, failureCode);
    }

    @Transactional
    public void markProviderManualReview(String rechargeNo, String failureCode) {
        RechargeRow recharge = lockRecharge(rechargeNo);
        jdbc.update("UPDATE stored_value_recharge_attempt SET status = 'UNKNOWN', failure_code = ? WHERE recharge_id = ? AND attempt_no = 1",
            failureCode, recharge.id);
        jdbc.update("UPDATE stored_value_recharge SET status = '" + StoredValueRechargeStatus.MANUAL_REVIEW.name()
            + "', failure_code = ? WHERE id = ?",
            failureCode, recharge.id);
    }

    @Transactional
    public PreparedRefund prepareRefund(String rechargeNo, Long operatorId, Long reviewerId, String reason) {
        requireDifferent(operatorId, reviewerId);
        RechargeRow recharge = lockRecharge(rechargeNo);
        RefundRow existing = findRefund(recharge.id);
        if (existing != null && "SUCCEEDED".equals(existing.status)) {
            return new PreparedRefund(existing.id, existing.refundNo, recharge.rechargeNo, recharge.providerBusinessNo,
                recharge.principalAmount, recharge.providerKey, recharge.policyVersion, recharge.id, true);
        }
        if (!StoredValueRechargeStatus.SUCCEEDED.matches(recharge.status)
            && !(StoredValueRechargeStatus.REFUNDING.matches(recharge.status)
                && existing != null && "REFUNDING".equals(existing.status))) {
            throw BusinessException.conflict("只有成功充值可以申请全额退款");
        }
        jdbc.update("""
            INSERT INTO stored_value_recharge_refund
              (refund_no, recharge_id, account_id, provider_key, provider_env, provider_business_no,
               amount, currency, status, operator_id, reviewer_id, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'CNY', '%s', ?, ?, ?)
            ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """.formatted(StoredValueRechargeStatus.REFUNDING.name()), "SVRF-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(), recharge.id,
            recharge.accountId, recharge.providerKey, recharge.providerEnv, recharge.providerBusinessNo,
            recharge.principalAmount, operatorId, reviewerId, reason);
        Long refundId = jdbc.queryForObject("SELECT id FROM stored_value_recharge_refund WHERE recharge_id = ?", Long.class, recharge.id);
        String refundNo = jdbc.queryForObject("SELECT refund_no FROM stored_value_recharge_refund WHERE id = ?", String.class, refundId);
        jdbc.update("UPDATE stored_value_recharge SET status = '" + StoredValueRechargeStatus.REFUNDING.name()
            + "' WHERE id = ?", recharge.id);
        return new PreparedRefund(refundId, refundNo, recharge.rechargeNo, recharge.providerBusinessNo,
            recharge.principalAmount, recharge.providerKey, recharge.policyVersion, recharge.id, false);
    }

    @Transactional
    public StoredValueRechargeResult finalizeRefund(PreparedRefund prepared, StoredValueProviderRefundResult result) {
        if (prepared == null || result == null) {
            throw BusinessException.badRequest("充值退款结果不完整");
        }
        RefundRow refund = jdbc.queryForObject("SELECT * FROM stored_value_recharge_refund WHERE id = ? FOR UPDATE",
            (rs, n) -> new RefundRow(rs.getLong("id"), rs.getLong("recharge_id"), rs.getLong("account_id"),
            rs.getString("refund_no"), rs.getString("status"), rs.getLong("operator_id")), prepared.refundId);
        if ("SUCCEEDED".equals(refund.status)) {
            return result(refund.rechargeId);
        }
        RechargeRow recharge = lockRecharge(refund.rechargeId);
        StoredValueProviderDescriptor descriptor = new StoredValueProviderDescriptor(
            recharge.providerKey, recharge.providerEnv, recharge.providerName, recharge.providerConfigVersion, true);
        insertOperationEvidence(recharge.id, refund.id, descriptor, "RECHARGE_REFUND", refund.refundNo,
            null, "DEV/SANDBOX recharge refund request", result.responseDigest(), result.responseSummary(),
            result.succeeded() ? "SUCCEEDED" : "FAILED", result.failureCode());
        if (!result.succeeded()) {
            jdbc.update("UPDATE stored_value_recharge_refund SET status = 'REFUNDING', failure_code = ? WHERE id = ?",
                result.failureCode(), refund.id);
            return result(recharge.id);
        }
        AccountRow account = accountRow(recharge.accountId);
        BigDecimal usedBonus = bonusUsed(recharge.id);
        if (usedBonus.signum() > 0) {
            createRecoveryCase(recharge, usedBonus, "充值退款发现赠送金已消费，待追偿");
        }
        if (account.principalAvailable.compareTo(recharge.principalAmount) < 0 || usedBonus.signum() > 0) {
            jdbc.update("UPDATE stored_value_recharge_refund SET status = 'MANUAL_REVIEW', external_ref = ?, failure_code = 'RECOVERY_REQUIRED' WHERE id = ?",
                result.externalRef(), refund.id);
            jdbc.update("UPDATE stored_value_recharge SET status = '" + StoredValueRechargeStatus.MANUAL_REVIEW.name()
                + "' WHERE id = ?", recharge.id);
            return result(recharge.id);
        }
        ledgerService.append(new StoredValueCommands.Ledger(
            recharge.issuerId, recharge.productId, recharge.accountId, recharge.currency, "RECHARGE_REFUND_PRINCIPAL",
            "RECHARGE_REFUND", refund.refundNo, "RECHARGE_REFUND:" + recharge.rechargeNo, recharge.policyVersion,
            "充值本金全额退款", refund.operatorId, "SYSTEM", LocalDateTime.now(), List.of(
            new StoredValueCommands.LedgerLine(account.id, null, "PRINCIPAL_AVAILABLE", "DEBIT", recharge.principalAmount),
            new StoredValueCommands.LedgerLine(null, null, "PROVIDER_REFUND_CLEARING", "CREDIT", recharge.principalAmount))));
        List<BonusRow> batches = bonusRows(recharge.id);
        for (BonusRow batch : batches) {
            if (batch.availableAmount.signum() <= 0) {
                continue;
            }
            ledgerService.append(new StoredValueCommands.Ledger(
                recharge.issuerId, recharge.productId, recharge.accountId, recharge.currency, "RECHARGE_REFUND_BONUS_REVERSAL",
                "RECHARGE_REFUND", refund.refundNo, "RECHARGE_REFUND:" + recharge.rechargeNo + ":BONUS:" + batch.id,
                recharge.policyVersion, "充值退款冲销未使用赠送金", refund.operatorId, "SYSTEM", LocalDateTime.now(), List.of(
                new StoredValueCommands.LedgerLine(account.id, batch.id, "BONUS_AVAILABLE", "DEBIT", batch.availableAmount),
                new StoredValueCommands.LedgerLine(null, null, "PROMOTION_FUNDING", "CREDIT", batch.availableAmount))));
            jdbc.update("UPDATE stored_value_bonus_batch SET status = 'EXHAUSTED' WHERE id = ?", batch.id);
        }
        jdbc.update("UPDATE stored_value_recharge_refund SET status = 'SUCCEEDED', external_ref = ? WHERE id = ?", result.externalRef(), refund.id);
        jdbc.update("UPDATE stored_value_recharge SET status = '" + StoredValueRechargeStatus.REFUNDED.name()
            + "' WHERE id = ?", recharge.id);
        return result(recharge.id);
    }

    @Transactional
    public void openDispute(StoredValueCommands.Dispute command) {
        if (command == null || command.rechargeNo() == null || command.providerCaseId() == null
            || command.operatorId() == null || command.reason() == null || command.reason().isBlank()) {
            throw BusinessException.badRequest("争议事实不完整");
        }
        RechargeRow recharge = lockRecharge(command.rechargeNo());
        Long existingCaseId = jdbc.query("SELECT id FROM stored_value_dispute_case WHERE provider_case_id = ?",
            rs -> rs.next() ? rs.getLong(1) : null, command.providerCaseId());
        if (existingCaseId != null) {
            Long existingRechargeId = jdbc.queryForObject("SELECT recharge_id FROM stored_value_dispute_case WHERE id = ?",
                Long.class, existingCaseId);
            if (!recharge.id.equals(existingRechargeId)) {
                throw BusinessException.conflict("渠道争议编号已关联其他充值");
            }
            return;
        }
        if (!StoredValueRechargeStatus.any(recharge.status, StoredValueRechargeStatus.SUCCEEDED,
            StoredValueRechargeStatus.REFUNDING)) {
            throw BusinessException.conflict("充值当前状态不能进入争议");
        }
        BigDecimal requested = StoredValueMoney.positive(command.amount()).amount();
        AccountRow account = accountRow(recharge.accountId);
        BigDecimal remaining = requested.min(account.principalAvailable.add(bonusTotal(account.id)));
        jdbc.update("""
            INSERT INTO stored_value_dispute_case
              (case_no, recharge_id, account_id, provider_case_id, status, frozen_amount, currency, reason, operator_id)
            VALUES (?, ?, ?, ?, 'OPEN', 0.00, 'CNY', ?, ?)
            """, "SVD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(), recharge.id,
            account.id, command.providerCaseId(), command.reason(), command.operatorId());
        Long caseId = jdbc.queryForObject("SELECT id FROM stored_value_dispute_case WHERE provider_case_id = ?", Long.class, command.providerCaseId());
        BigDecimal frozen = freezeForDispute(recharge, account, remaining, caseId, command.reason());
        jdbc.update("UPDATE stored_value_dispute_case SET frozen_amount = ?, status = 'UNDER_REVIEW' WHERE id = ?", frozen, caseId);
        jdbc.update("UPDATE stored_value_recharge SET status = '" + StoredValueRechargeStatus.DISPUTED.name()
            + "' WHERE id = ?", recharge.id);
    }

    public StoredValueRechargeResult findResult(String rechargeNo) {
        return result(lockRecharge(rechargeNo).id);
    }

    public StoredValueRechargeResult findResultForUser(String rechargeNo, Long userId) {
        Long rechargeId = jdbc.query("""
            SELECT id FROM stored_value_recharge
            WHERE recharge_no = ? AND user_id = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, rechargeNo, userId);
        if (rechargeId == null) {
            throw BusinessException.notFound("充值记录不存在");
        }
        return result(rechargeId);
    }

    public String findProviderKey(String rechargeNo) {
        return jdbc.queryForObject("SELECT provider_key FROM stored_value_recharge WHERE recharge_no = ?", String.class, rechargeNo);
    }

    public ProviderSnapshot findProviderSnapshot(String rechargeNo) {
        return jdbc.queryForObject("""
            SELECT product_id, provider_key, provider_env, provider_name, provider_config_version
            FROM stored_value_recharge WHERE recharge_no = ?
            """, (rs, n) -> new ProviderSnapshot(rs.getLong("product_id"), rs.getString("provider_key"),
            rs.getString("provider_env"), rs.getString("provider_name"), rs.getString("provider_config_version")), rechargeNo);
    }

    private BigDecimal freezeForDispute(RechargeRow recharge, AccountRow account, BigDecimal requested, Long caseId, String reason) {
        BigDecimal remaining = requested;
        if (remaining.signum() > 0 && account.principalAvailable.signum() > 0) {
            BigDecimal amount = remaining.min(account.principalAvailable);
            ledgerService.append(new StoredValueCommands.Ledger(recharge.issuerId, recharge.productId, account.id, "CNY",
                "DISPUTE_FREEZE_PRINCIPAL", "DISPUTE", caseId.toString(), "DISPUTE:" + caseId + ":PRINCIPAL",
                recharge.policyVersion, reason, null, "SYSTEM", LocalDateTime.now(), List.of(
                new StoredValueCommands.LedgerLine(account.id, null, "PRINCIPAL_AVAILABLE", "DEBIT", amount),
                new StoredValueCommands.LedgerLine(account.id, null, "PRINCIPAL_FROZEN", "CREDIT", amount))));
            remaining = remaining.subtract(amount);
        }
        if (remaining.signum() > 0) {
            for (BonusRow batch : bonusRows(recharge.id)) {
                if (remaining.signum() <= 0) break;
                BigDecimal amount = remaining.min(batch.availableAmount);
                if (amount.signum() <= 0) continue;
                ledgerService.append(new StoredValueCommands.Ledger(recharge.issuerId, recharge.productId, account.id, "CNY",
                    "DISPUTE_FREEZE_BONUS", "DISPUTE", caseId.toString(), "DISPUTE:" + caseId + ":BONUS:" + batch.id,
                    recharge.policyVersion, reason, null, "SYSTEM", LocalDateTime.now(), List.of(
                new StoredValueCommands.LedgerLine(account.id, batch.id, "BONUS_AVAILABLE", "DEBIT", amount),
                new StoredValueCommands.LedgerLine(account.id, batch.id, "BONUS_FROZEN", "CREDIT", amount))));
                jdbc.update("UPDATE stored_value_bonus_batch SET status = 'FROZEN' WHERE id = ? AND available_amount = 0 AND frozen_amount > 0",
                    batch.id);
                remaining = remaining.subtract(amount);
            }
        }
        return requested.subtract(remaining);
    }

    private void releaseReservation(RechargeRow recharge, StoredValueRechargeStatus status, String failureCode) {
        if (status == null || !status.isReservationReleaseTarget()) {
            throw new IllegalArgumentException("充值状态不能用于释放处理中额度: " + status);
        }
        if (StoredValueRechargeStatus.any(recharge.status, StoredValueRechargeStatus.SUCCEEDED,
            StoredValueRechargeStatus.REFUNDED)) return;
        Integer reserved = jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_limit_reservation WHERE recharge_id = ? AND status = 'RESERVED'", Integer.class, recharge.id);
        if (reserved != null && reserved > 0) {
            jdbc.update("UPDATE stored_value_limit_reservation SET status = 'RELEASED', released_at = CURRENT_TIMESTAMP WHERE recharge_id = ? AND status = 'RESERVED'", recharge.id);
            jdbc.update("UPDATE stored_value_limit_day_guard SET reserved_amount = reserved_amount - ?, pending_count = pending_count - 1 WHERE account_id = ? AND guard_date = ?",
                recharge.principalAmount, recharge.accountId, recharge.guardDate);
            jdbc.update("UPDATE stored_value_limit_month_guard SET reserved_bonus_amount = reserved_bonus_amount - ? WHERE account_id = ? AND guard_month = ?",
                recharge.expectedBonusAmount, recharge.accountId, recharge.guardMonth);
            ledgerService.append(new StoredValueCommands.Ledger(recharge.issuerId, recharge.productId, recharge.accountId, recharge.currency,
                "RECHARGE_LIMIT_RELEASE", "RECHARGE", recharge.rechargeNo, "RECHARGE:" + recharge.rechargeNo + ":LIMIT_RELEASE",
                recharge.policyVersion, "充值终态释放处理中额度", null, "SYSTEM", LocalDateTime.now(), List.of(
                new StoredValueCommands.LedgerLine(null, null, "LIMIT_RESERVATION_OFFSET", "DEBIT", recharge.principalAmount),
                new StoredValueCommands.LedgerLine(null, null, "LIMIT_RESERVATION", "CREDIT", recharge.principalAmount))));
        }
        String attemptStatus = switch (status) {
            case FAILED -> "FAILED";
            case MANUAL_REVIEW -> "MANUAL_REVIEW";
            case CANCELED, TIMED_OUT -> "UNKNOWN";
            default -> null;
        };
        if (attemptStatus != null) {
            jdbc.update("""
                UPDATE stored_value_recharge_attempt
                SET status = ?, failure_code = ?
                WHERE recharge_id = ? AND attempt_no = 1
                """, attemptStatus, failureCode, recharge.id);
        }
        jdbc.update("UPDATE stored_value_recharge SET status = ?, failure_code = ? WHERE id = ?", status.name(), failureCode, recharge.id);
    }

    private RechargeRow lockRecharge(Long id) {
        return jdbc.queryForObject("SELECT * FROM stored_value_recharge WHERE id = ? FOR UPDATE", this::mapRecharge, id);
    }

    private RechargeRow lockRecharge(String no) {
        return jdbc.queryForObject("SELECT * FROM stored_value_recharge WHERE recharge_no = ? FOR UPDATE", this::mapRecharge, no);
    }

    private RechargeRow mapRecharge(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new RechargeRow(rs.getLong("id"), rs.getString("recharge_no"), rs.getLong("user_id"),
            rs.getLong("account_id"), rs.getLong("issuer_id"), rs.getLong("product_id"),
            rs.getString("policy_version"), rs.getString("currency"), rs.getBigDecimal("principal_amount"),
            rs.getBigDecimal("expected_bonus_amount"), rs.getString("status"), rs.getInt("posting_attempt_count"),
            rs.getString("posting_claim_token"), timestamp(rs, "posting_started_at"),
            rs.getString("posting_last_failure_code"), rs.getString("posting_last_failure_message"),
            rs.getString("provider_key"),
            rs.getString("provider_env"), rs.getString("provider_name"), rs.getString("provider_config_version"),
            rs.getString("provider_business_no"), rs.getDate("guard_date").toLocalDate(), rs.getString("guard_month"),
            rs.getString("external_ref"));
    }

    private LocalDateTime timestamp(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private AccountRow accountRow(Long id) {
        return jdbc.queryForObject("SELECT * FROM stored_value_account WHERE id = ? FOR UPDATE",
            (rs, n) -> new AccountRow(rs.getLong("id"), rs.getLong("issuer_id"), rs.getLong("product_id"),
                rs.getString("status"), rs.getBigDecimal("principal_available"), rs.getBigDecimal("principal_frozen")), id);
    }

    private GuardRows lockOrCreateGuards(Long accountId, LocalDate date, String month, String policyVersion) {
        jdbc.update("INSERT INTO stored_value_limit_day_guard(account_id, guard_date, policy_version) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE account_id = account_id",
            accountId, date, policyVersion);
        jdbc.update("INSERT INTO stored_value_limit_month_guard(account_id, guard_month, policy_version) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE account_id = account_id",
            accountId, month, policyVersion);
        GuardRows day = jdbc.queryForObject("SELECT * FROM stored_value_limit_day_guard WHERE account_id = ? AND guard_date = ? FOR UPDATE",
            (rs, n) -> new GuardRows(rs.getBigDecimal("successful_amount"), rs.getBigDecimal("reserved_amount"),
                rs.getInt("initiation_count"), rs.getInt("pending_count"), rs.getBigDecimal("risk_daily_limit"),
                (Integer) rs.getObject("risk_max_pending"), (Integer) rs.getObject("risk_max_initiations"), null, null), accountId, date);
        GuardRows monthRow = jdbc.queryForObject("SELECT * FROM stored_value_limit_month_guard WHERE account_id = ? AND guard_month = ? FOR UPDATE",
            (rs, n) -> new GuardRows(null, null, 0, 0, null, null, null,
                rs.getBigDecimal("issued_bonus_amount"), rs.getBigDecimal("reserved_bonus_amount")), accountId, month);
        return new GuardRows(day.daySuccessful, day.dayReserved, day.initiationCount, day.pendingCount,
            day.riskDailyLimit, day.riskMaxPending, day.riskMaxInitiations, monthRow.monthIssued, monthRow.monthReserved);
    }

    private BigDecimal bonusTotal(Long accountId) {
        BigDecimal value = jdbc.queryForObject("SELECT COALESCE(SUM(available_amount + frozen_amount),0.00) FROM stored_value_bonus_batch WHERE account_id = ?", BigDecimal.class, accountId);
        return value == null ? BigDecimal.ZERO.setScale(2) : value;
    }

    private BigDecimal bonusUsed(Long rechargeId) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(SUM(CASE WHEN status = 'EXPIRED' THEN 0.00
                                     ELSE original_amount - available_amount - frozen_amount END), 0.00)
            FROM stored_value_bonus_batch WHERE recharge_id = ?
            """, BigDecimal.class, rechargeId);
        return value == null ? BigDecimal.ZERO.setScale(2) : value;
    }

    private BigDecimal pendingRechargeTotal(Long accountId, Long excludedRechargeId) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(SUM(principal_amount + expected_bonus_amount), 0.00)
            FROM stored_value_recharge
            WHERE account_id = ?
              AND status IN ('%s','%s','%s','%s')
              AND (? IS NULL OR id <> ?)
            """.formatted(
                StoredValueRechargeStatus.INITIATED.name(),
                StoredValueRechargeStatus.WAITING_PROVIDER.name(),
                StoredValueRechargeStatus.PROVIDER_SUCCEEDED_PENDING_POSTING.name(),
                StoredValueRechargeStatus.POSTING.name()),
            BigDecimal.class, accountId, excludedRechargeId, excludedRechargeId);
        return value == null ? BigDecimal.ZERO.setScale(2) : value;
    }

    private List<BonusRow> bonusRows(Long rechargeId) {
        return jdbc.query("SELECT id, available_amount FROM stored_value_bonus_batch WHERE recharge_id = ? ORDER BY expires_at, issued_at, id FOR UPDATE",
            (rs, n) -> new BonusRow(rs.getLong("id"), rs.getBigDecimal("available_amount")), rechargeId);
    }

    private void createRecoveryCase(RechargeRow recharge, BigDecimal amount, String reason) {
        Integer open = jdbc.queryForObject("""
            SELECT COUNT(*) FROM stored_value_recovery_case
            WHERE recharge_id = ? AND status IN ('OPEN','IN_PROGRESS','MANUAL_REVIEW')
            """, Integer.class, recharge.id);
        if (open != null && open > 0) {
            return;
        }
        jdbc.update("""
            INSERT INTO stored_value_recovery_case
              (case_no, recharge_id, account_id, amount, currency, status, reason)
            VALUES (?, ?, ?, ?, 'CNY', 'OPEN', ?)
            """, "SVRC-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(), recharge.id,
            recharge.accountId, amount, reason);
    }

    private void insertOperationEvidence(Long rechargeId, Long refundId, StoredValueProviderDescriptor provider,
                                         String operationType, String operationKey, String requestDigest,
                                         String requestSummary, String responseDigest, String responseSummary,
                                         String status, String resultCode) {
        String eventDigest = sha256(operationType + "|" + operationKey + "|" + nullToEmpty(requestDigest)
            + "|" + nullToEmpty(responseDigest) + "|" + status + "|" + nullToEmpty(resultCode));
        jdbc.update("""
            INSERT INTO stored_value_operation_evidence
              (recharge_id, refund_id, operation_type, operation_key, provider_key, provider_env,
               provider_name, config_version, request_digest, request_summary, response_digest,
               response_summary, event_digest, status, result_code, source_type, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SYSTEM', CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """, rechargeId, refundId, operationType, operationKey, provider.providerKey(), provider.providerEnv(),
            provider.providerName(), provider.configVersion(), requestDigest, requestSummary, responseDigest,
            responseSummary, eventDigest, status, resultCode);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String limitValue(String value, int maxLength) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String receiptValue(String value, int maxLength) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) return trimmed;
        return "[OVERSIZE:" + value.length() + "]";
    }

    private boolean merchantMatches(Long productId, StoredValueCommands.Callback callback, AttemptRow attempt) {
        if (callback.merchantIdentifier() == null || callback.merchantIdentifier().isBlank()) return false;
        String digest = jdbc.query("""
            SELECT merchant_identifier_digest
            FROM stored_value_provider_config
            WHERE product_id = ? AND provider_key = ? AND provider_env = ? AND provider_name = ?
              AND config_version = ? AND enabled = 1 AND status = 'ACTIVE'
              AND (? <> 'SANDBOX' OR manual_confirmed = 1)
            ORDER BY id DESC LIMIT 1
            """, rs -> rs.next() ? rs.getString(1) : null, productId, attempt.providerKey,
            attempt.providerEnv, attempt.providerName, attempt.configVersion, attempt.providerEnv);
        return digest != null && digest.equals(sha256(callback.merchantIdentifier()));
    }

    private Long findEvidenceId(StoredValueCommands.Callback callback) {
        return jdbc.query("SELECT id FROM stored_value_callback_evidence WHERE request_id = ?",
            rs -> rs.next() ? rs.getLong(1) : null, callback.requestId());
    }

    private StoredValueRechargeResult result(Long rechargeId) {
        return jdbc.queryForObject("""
            SELECT id, recharge_no, status, principal_amount, expected_bonus_amount, actual_bonus_amount,
                   provider_key, provider_env, provider_name, provider_business_no, external_ref, failure_code
            FROM stored_value_recharge WHERE id = ?
            """, (rs, n) -> new StoredValueRechargeResult(rs.getLong("id"), rs.getString("recharge_no"),
            rs.getString("status"), rs.getBigDecimal("principal_amount"), rs.getBigDecimal("expected_bonus_amount"),
            rs.getBigDecimal("actual_bonus_amount"), rs.getString("provider_key"), rs.getString("provider_env"),
            rs.getString("provider_name"), rs.getString("provider_business_no"), rs.getString("external_ref"),
            rs.getString("failure_code")), rechargeId);
    }

    private ExistingRecharge findByClientRequest(Long userId, Long productId, String requestId) {
        return jdbc.query("""
            SELECT id, recharge_no, status, principal_amount, expected_bonus_amount, provider_business_no,
                   provider_key, provider_env, provider_name, provider_config_version, account_id, idempotency_hash
            FROM stored_value_recharge WHERE user_id = ? AND product_id = ? AND client_request_id = ?
            """,
            rs -> rs.next() ? new ExistingRecharge(rs.getLong("id"), rs.getString("recharge_no"), rs.getString("status"),
                rs.getBigDecimal("principal_amount"), rs.getBigDecimal("expected_bonus_amount"), rs.getString("provider_business_no"),
                rs.getString("provider_key"), rs.getString("provider_env"), rs.getString("provider_name"),
                rs.getString("provider_config_version"), rs.getLong("account_id"), rs.getString("idempotency_hash")) : null,
            userId, productId, requestId);
    }

    private PreparedRecharge replay(StoredValueCommands.CreateRecharge command, ExistingRecharge existing) {
        if (command.providerKey() != null && !command.providerKey().isBlank()
            && !command.providerKey().equals(existing.providerKey)) {
            throw BusinessException.conflict("充值幂等键对应不同 Provider");
        }
        StoredValueProviderDescriptor snapshot = new StoredValueProviderDescriptor(existing.providerKey,
            existing.providerEnv, existing.providerName, existing.providerConfigVersion, true);
        if (!requestHash(command, snapshot).equals(existing.idempotencyHash)) {
            throw BusinessException.conflict("充值幂等键对应不同请求");
        }
        return toPrepared(existing, true);
    }

    private PreparedRecharge toPrepared(ExistingRecharge existing, boolean replayed) {
        return new PreparedRecharge(existing.rechargeId, existing.rechargeNo, existing.status,
            existing.principalAmount, existing.expectedBonusAmount, existing.providerBusinessNo,
            existing.providerKey, existing.accountId, replayed);
    }

    private RefundRow findRefund(Long rechargeId) {
        return jdbc.query("""
            SELECT id, recharge_id, account_id, refund_no, status, operator_id
            FROM stored_value_recharge_refund WHERE recharge_id = ? FOR UPDATE
            """, rs -> rs.next() ? new RefundRow(rs.getLong("id"), rs.getLong("recharge_id"),
            rs.getLong("account_id"), rs.getString("refund_no"), rs.getString("status"),
            rs.getLong("operator_id")) : null, rechargeId);
    }

    private AttemptRow attemptRow(Long rechargeId) {
        return jdbc.queryForObject("""
            SELECT provider_key, provider_env, provider_name, config_version
            FROM stored_value_recharge_attempt WHERE recharge_id = ? ORDER BY attempt_no DESC LIMIT 1
            """, (rs, n) -> new AttemptRow(rs.getString("provider_key"), rs.getString("provider_env"),
            rs.getString("provider_name"), rs.getString("config_version")), rechargeId);
    }

    private boolean same(String left, String right) {
        return left != null && left.equals(right);
    }

    private StoredValuePolicy policy(Long productId) {
        return configService.requireProduct(productId).policy();
    }

    private StoredValuePolicy policy(Long productId, String policyVersion) {
        return configService.requirePolicy(productId, policyVersion);
    }

    private String requestHash(StoredValueCommands.CreateRecharge command, StoredValueProviderDescriptor provider) {
        return sha256(command.userId() + "|" + command.productId() + "|" + command.principalAmount() + "|"
            + command.currency() + "|" + command.clientRequestId() + "|" + provider.snapshotKey());
    }

    private String sha256(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private BigDecimal lowerLimit(BigDecimal policyLimit, BigDecimal riskLimit) {
        return riskLimit == null ? policyLimit : policyLimit.min(riskLimit);
    }

    private int lowerCount(int policyLimit, Integer riskLimit) {
        return riskLimit == null ? policyLimit : Math.min(policyLimit, riskLimit);
    }

    private void requireDifferent(Long operator, Long reviewer) {
        if (operator == null || reviewer == null || operator.equals(reviewer)) {
            throw BusinessException.forbidden("执行人与复核人必须不同");
        }
    }

    public record PreparedRecharge(Long rechargeId, String rechargeNo, String status, BigDecimal principalAmount,
                                   BigDecimal expectedBonusAmount, String providerBusinessNo, String providerKey,
                                   Long accountId, boolean replayed) {
    }

    public record PreparedRefund(Long refundId, String refundNo, String rechargeNo, String providerBusinessNo,
                                 BigDecimal amount, String providerKey, String policyVersion, Long rechargeId,
                                 boolean replayed) {
    }

    private record ExistingRecharge(Long rechargeId, String rechargeNo, String status, BigDecimal principalAmount,
                                    BigDecimal expectedBonusAmount, String providerBusinessNo, String providerKey,
                                    String providerEnv, String providerName, String providerConfigVersion,
                                    Long accountId, String idempotencyHash) {
    }

    private record RechargeRow(Long id, String rechargeNo, Long userId, Long accountId, Long issuerId, Long productId,
                               String policyVersion, String currency, BigDecimal principalAmount,
                               BigDecimal expectedBonusAmount, String status, int postingAttemptCount,
                               String postingClaimToken, LocalDateTime postingStartedAt,
                               String postingLastFailureCode, String postingLastFailureMessage,
                               String providerKey, String providerEnv,
                               String providerName, String providerConfigVersion, String providerBusinessNo,
                               LocalDate guardDate, String guardMonth, String externalRef) {
    }

    private record AccountRow(Long id, Long issuerId, Long productId, String status, BigDecimal principalAvailable,
                              BigDecimal principalFrozen) {
    }

    private record GuardRows(BigDecimal daySuccessful, BigDecimal dayReserved, int initiationCount, int pendingCount,
                             BigDecimal riskDailyLimit, Integer riskMaxPending, Integer riskMaxInitiations,
                             BigDecimal monthIssued, BigDecimal monthReserved) {
    }

    private record BonusRow(Long id, BigDecimal availableAmount) {
    }

    private record RefundRow(Long id, Long rechargeId, Long accountId, String refundNo, String status,
                             Long operatorId) {
    }

    private record AttemptRow(String providerKey, String providerEnv, String providerName, String configVersion) {
    }

    public record ProviderSnapshot(Long productId, String providerKey, String providerEnv,
                                   String providerName, String configVersion) {
    }

    public record PostingClaim(Long rechargeId, String rechargeNo, String claimToken, int postingAttemptCount,
                               boolean acquired, String status, String sourceType, Long operatorId, String reason) {
    }
}
