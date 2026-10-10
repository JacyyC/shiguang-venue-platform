package com.example.venue.storedvalue.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.ResultCode;
import com.example.venue.security.SecurityContext;
import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.domain.StoredValueRechargeStatus;
import com.example.venue.storedvalue.dto.StoredValueResponses;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Approval boundary for high-risk stored-value actions. The request row keeps
 * immutable facts while the event table records every lifecycle transition.
 * Provider work is deliberately performed after the execution claim commits.
 */
@Service
public class StoredValueApprovalService {
    private static final ActionDefinition RECOVERY = new ActionDefinition(
        "RECHARGE_POSTING_RECOVERY", "RECHARGE", "stored-value:recharge:review",
        "stored-value:recharge:execute");
    private static final ActionDefinition REFUND = new ActionDefinition(
        "RECHARGE_REFUND", "RECHARGE", "stored-value:refund:review",
        "stored-value:refund:manage");
    private static final ActionDefinition UNFREEZE = new ActionDefinition(
        "ACCOUNT_UNFREEZE", "ACCOUNT", "stored-value:account:unfreeze:review",
        "stored-value:account:unfreeze");
    private static final ActionDefinition ADJUSTMENT = new ActionDefinition(
        "BALANCE_ADJUSTMENT", "RECONCILIATION_DIFFERENCE", "stored-value:adjustment:review",
        "stored-value:adjustment:execute");
    private static final List<ActionDefinition> DEFINITIONS = List.of(RECOVERY, REFUND, UNFREEZE, ADJUSTMENT);
    private static final int EXPIRY_HOURS = 24;

    private final JdbcTemplate jdbc;
    private final StoredValueRechargeRecoveryService recoveryService;
    private final StoredValueRechargeService rechargeService;
    private final StoredValueAccountService accountService;
    private final StoredValueAdjustmentService adjustmentService;
    private final TransactionTemplate transactions;

    public StoredValueApprovalService(JdbcTemplate jdbc,
                                      StoredValueRechargeRecoveryService recoveryService,
                                      StoredValueRechargeService rechargeService,
                                      StoredValueAccountService accountService,
                                      StoredValueAdjustmentService adjustmentService,
                                      PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.recoveryService = recoveryService;
        this.rechargeService = rechargeService;
        this.accountService = accountService;
        this.adjustmentService = adjustmentService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public StoredValueResponses.ApprovalDetailResponse submitPostingRecovery(String rechargeNo, String reason) {
        long actor = requireActorWithPermission(RECOVERY.executePermission);
        TargetData target = rechargeTarget(RECOVERY, rechargeNo);
        return submit(RECOVERY, target, null, target.amount, reason, actor);
    }

    @Transactional
    public StoredValueResponses.ApprovalDetailResponse submitRefund(String rechargeNo, String reason) {
        long actor = requireActorWithPermission(REFUND.executePermission);
        TargetData target = rechargeTarget(REFUND, rechargeNo);
        return submit(REFUND, target, null, target.amount, reason, actor);
    }

    @Transactional
    public StoredValueResponses.ApprovalDetailResponse submitUnfreeze(Long accountId, String reason) {
        long actor = requireActorWithPermission(UNFREEZE.executePermission);
        TargetData target = accountTarget(accountId);
        return submit(UNFREEZE, target, null, null, reason, actor);
    }

    @Transactional
    public StoredValueResponses.ApprovalDetailResponse submitAdjustment(Long differenceId,
                                                                    BigDecimal amount,
                                                                    String reason) {
        long actor = requireActorWithPermission(ADJUSTMENT.executePermission);
        StoredValueAdjustmentService.Target target = adjustmentService.target(differenceId, amount);
        TargetData data = new TargetData(ADJUSTMENT, String.valueOf(differenceId), target.snapshot(),
            target.policyVersion(), target.amount(), target.currency(), "OPEN");
        return submit(ADJUSTMENT, data, amount, target.amount(), reason, actor);
    }

    public PageResult<StoredValueResponses.ApprovalListResponse> list(PageQuery query) {
        long actor = requireActiveActor();
        List<String> reviewPermissions = DEFINITIONS.stream()
            .map(item -> item.reviewPermission)
            .filter(permission -> SecurityContext.hasAuthority(permission) && hasLivePermission(actor, permission))
            .toList();
        List<String> executePermissions = DEFINITIONS.stream()
            .map(item -> item.executePermission)
            .filter(permission -> SecurityContext.hasAuthority(permission) && hasLivePermission(actor, permission))
            .toList();
        if (reviewPermissions.isEmpty() && executePermissions.isEmpty()) {
            throw BusinessException.forbidden("当前账号没有储值审批权限");
        }
        int pageNum = pageNum(query);
        int pageSize = pageSize(query);
        List<String> permissionClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (!reviewPermissions.isEmpty()) {
            permissionClauses.add("review_permission IN ("
                + String.join(",", reviewPermissions.stream().map(item -> "?").toList()) + ")");
            params.addAll(reviewPermissions);
        }
        if (!executePermissions.isEmpty()) {
            permissionClauses.add("execute_permission IN ("
                + String.join(",", executePermissions.stream().map(item -> "?").toList()) + ")");
            params.addAll(executePermissions);
        }
        String where = " WHERE (" + String.join(" OR ", permissionClauses) + ")"
            + " AND status IN ('PENDING','APPROVED','EXECUTING')";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_approval" + where,
            Long.class, params.toArray());
        params.add(pageSize);
        params.add((pageNum - 1) * pageSize);
        List<ApprovalRow> rows = jdbc.query("""
            SELECT * FROM stored_value_approval
            """ + where + " ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?", approvalMapper(),
            params.toArray());
        List<StoredValueResponses.ApprovalListResponse> records = rows.stream().map(this::listResponse).toList();
        return PageResult.of(records, total, pageNum, pageSize);
    }

    public StoredValueResponses.ApprovalDetailResponse detail(String approvalNo) {
        long actor = requireActiveActor();
        ApprovalRow row = find(approvalNo, false);
        requireAnyActionPermission(actor, row);
        return response(row);
    }

    public StoredValueResponses.ApprovalDetailResponse approve(String approvalNo, String reviewReason) {
        return transactions.execute(status -> approveInTransaction(approvalNo, reviewReason)).orThrow();
    }

    @Transactional
    protected TransitionResult<StoredValueResponses.ApprovalDetailResponse> approveInTransaction(String approvalNo,
                                                                                            String reviewReason) {
        long actor = requireActiveActor();
        ApprovalRow row = find(approvalNo, true);
        requirePermission(actor, row.reviewPermission);
        if (actor == row.requesterId) {
            throw BusinessException.forbidden("申请人不能复核自己的审批");
        }
        ensurePending(row);
        if (expired(row)) {
            expire(row, actor, "审批已过期");
            return TransitionResult.failure(BusinessException.conflict("审批已过期"));
        }
        String reason = normalizeOptionalReason(reviewReason);
        int updated = jdbc.update("""
            UPDATE stored_value_approval
            SET status = 'APPROVED', reviewer_id = ?, reviewed_at = CURRENT_TIMESTAMP,
                review_reason = ?, version = version + 1
            WHERE id = ? AND status = 'PENDING' AND version = ?
            """, actor, reason, row.id, row.version);
        if (updated != 1) {
            throw BusinessException.conflict("审批状态已被其他复核操作改变");
        }
        appendEvent(row.id, "APPROVED", actor, row.reviewPermission, row.status, "APPROVED",
            reason, null);
        return TransitionResult.success(response(find(approvalNo, false)));
    }

    public StoredValueResponses.ApprovalDetailResponse reject(String approvalNo, String reviewReason) {
        return transactions.execute(status -> rejectInTransaction(approvalNo, reviewReason)).orThrow();
    }

    @Transactional
    protected TransitionResult<StoredValueResponses.ApprovalDetailResponse> rejectInTransaction(String approvalNo,
                                                                                           String reviewReason) {
        long actor = requireActiveActor();
        ApprovalRow row = find(approvalNo, true);
        requirePermission(actor, row.reviewPermission);
        if (actor == row.requesterId) {
            throw BusinessException.forbidden("申请人不能拒绝自己的审批");
        }
        ensurePending(row);
        if (expired(row)) {
            expire(row, actor, "审批已过期");
            return TransitionResult.failure(BusinessException.conflict("审批已过期"));
        }
        String reason = requireReason(reviewReason, "拒绝原因不能为空");
        int updated = jdbc.update("""
            UPDATE stored_value_approval
            SET status = 'REJECTED', reviewer_id = ?, reviewed_at = CURRENT_TIMESTAMP,
                review_reason = ?, active_key = NULL, version = version + 1
            WHERE id = ? AND status = 'PENDING' AND version = ?
            """, actor, reason, row.id, row.version);
        if (updated != 1) {
            throw BusinessException.conflict("审批状态已被其他复核操作改变");
        }
        appendEvent(row.id, "REJECTED", actor, row.reviewPermission, row.status, "REJECTED",
            reason, null);
        return TransitionResult.success(response(find(approvalNo, false)));
    }

    @Transactional
    public StoredValueResponses.ApprovalDetailResponse revoke(String approvalNo, String reason) {
        long actor = requireActiveActor();
        ApprovalRow row = find(approvalNo, true);
        requirePermission(actor, row.executePermission);
        if (actor != row.requesterId) {
            throw BusinessException.forbidden("只有申请人可以撤回审批");
        }
        if (!"PENDING".equals(row.status) && !"APPROVED".equals(row.status)) {
            throw BusinessException.conflict("当前审批状态不能撤回");
        }
        String normalized = requireReason(reason, "撤回原因不能为空");
        int updated = jdbc.update("""
            UPDATE stored_value_approval
            SET status = 'REVOKED', active_key = NULL, version = version + 1,
                execution_result = ?
            WHERE id = ? AND status IN ('PENDING','APPROVED') AND version = ?
            """, normalized, row.id, row.version);
        if (updated != 1) {
            throw BusinessException.conflict("审批状态已被其他操作改变");
        }
        appendEvent(row.id, "REVOKED", actor, row.executePermission, row.status, "REVOKED",
            normalized, null);
        return response(find(approvalNo, false));
    }

    /** Claim is committed before the action so an external provider is never called inside this transaction. */
    public ApprovalExecutionClaim claimForExecution(String approvalNo) {
        return transactions.execute(status -> claimForExecutionInTransaction(approvalNo)).orThrow();
    }

    private TransitionResult<ApprovalExecutionClaim> claimForExecutionInTransaction(String approvalNo) {
        long actor = requireActiveActor();
        ApprovalRow row = find(approvalNo, true);
        requirePermission(actor, row.executePermission);
        if (!"APPROVED".equals(row.status)) {
            if ("EXECUTING".equals(row.status)) {
                throw BusinessException.conflict("审批正在执行，禁止重复消费");
            }
            throw BusinessException.conflict("审批尚未批准或已经消费");
        }
        if (expired(row)) {
            expire(row, actor, "审批已过期");
            return TransitionResult.failure(BusinessException.conflict("审批已过期"));
        }
        if (row.reviewerId == null || actor == row.reviewerId) {
            throw BusinessException.forbidden("执行人与复核人必须是不同的当前登录主体");
        }
        String currentSnapshot = currentSnapshot(row);
        if (!row.targetSnapshot.equals(currentSnapshot)) {
            failClaim(row, actor, "审批目标快照已变化");
            return TransitionResult.failure(BusinessException.conflict("审批目标快照已变化，禁止执行"));
        }
        int updated = jdbc.update("""
            UPDATE stored_value_approval
            SET status = 'EXECUTING', executor_id = ?, version = version + 1
            WHERE id = ? AND status = 'APPROVED' AND version = ?
            """, actor, row.id, row.version);
        if (updated != 1) {
            throw BusinessException.conflict("审批执行权已被其他主体取得");
        }
        appendEvent(row.id, "EXECUTION_CLAIMED", actor, row.executePermission, row.status, "EXECUTING",
            row.reason, null);
        return TransitionResult.success(new ApprovalExecutionClaim(row.id, row.approvalNo, row.actionType,
            row.targetType, row.targetId, row.targetSnapshot, row.amount, row.reason, row.reviewerId, actor));
    }

    public StoredValueResponses.ApprovalDetailResponse execute(String approvalNo) {
        ApprovalExecutionClaim claim = claimForExecution(approvalNo);
        try {
            String result = executeAction(claim);
            completeExecution(claim, true, result);
        } catch (RuntimeException exception) {
            completeExecution(claim, false, limit(exception.getMessage()));
            throw exception;
        }
        return response(find(approvalNo, false));
    }

    public void completeExecution(ApprovalExecutionClaim claim, boolean succeeded, String result) {
        transactions.executeWithoutResult(status -> completeExecutionInTransaction(claim, succeeded, result));
    }

    private void completeExecutionInTransaction(ApprovalExecutionClaim claim, boolean succeeded, String result) {
        ApprovalRow row = find(claim.approvalNo, true);
        if (!"EXECUTING".equals(row.status) || !same(row.executorId, claim.executorId)) {
            throw BusinessException.conflict("审批执行状态已变化");
        }
        String normalized = limit(result);
        int updated = jdbc.update("""
            UPDATE stored_value_approval
            SET status = ?, active_key = NULL, executed_at = CURRENT_TIMESTAMP,
                execution_result = ?, version = version + 1
            WHERE id = ? AND status = 'EXECUTING' AND executor_id = ? AND version = ?
            """, succeeded ? "CONSUMED" : "FAILED", normalized, row.id, claim.executorId, row.version);
        if (updated != 1) {
            throw BusinessException.conflict("审批消费状态已被其他操作改变");
        }
        appendEvent(row.id, succeeded ? "CONSUMED" : "FAILED", claim.executorId,
            row.executePermission, row.status, succeeded ? "CONSUMED" : "FAILED", null, normalized);
    }

    private StoredValueResponses.ApprovalDetailResponse submit(ActionDefinition definition, TargetData target,
                                                           BigDecimal requestedAmount, BigDecimal storedAmount,
                                                           String reason, long requesterId) {
        String normalizedReason = requireReason(reason, "审批原因不能为空");
        BigDecimal amount = requestedAmount == null ? storedAmount : requestedAmount;
        String requestHash = digest(String.join("|", definition.actionType, definition.targetType,
            target.targetId, target.snapshot, amount == null ? "null" : amount.toPlainString(),
            target.currency, normalizedReason, target.policyVersion));
        String approvalNo = "SVA-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String activeKey = definition.actionType + ":" + definition.targetType + ":" + target.targetId;
        try {
            jdbc.update("""
                INSERT INTO stored_value_approval
                  (approval_no, active_key, action_type, target_type, target_id, request_hash,
                   target_snapshot, amount, currency, reason, requester_id,
                   review_permission, execute_permission, status, expires_at, policy_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, approvalNo, activeKey, definition.actionType, definition.targetType, target.targetId,
                requestHash, target.snapshot, amount, target.currency, normalizedReason, requesterId,
                definition.reviewPermission, definition.executePermission,
                LocalDateTime.now().plusHours(EXPIRY_HOURS), target.policyVersion);
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("同一目标已有未消费的审批");
        }
        Long id = jdbc.queryForObject("SELECT id FROM stored_value_approval WHERE approval_no = ?",
            Long.class, approvalNo);
        appendEvent(id, "SUBMITTED", requesterId, definition.executePermission, null, "PENDING",
            normalizedReason, null);
        return response(find(approvalNo, false));
    }

    private String executeAction(ApprovalExecutionClaim claim) {
        return switch (claim.actionType) {
            case "RECHARGE_POSTING_RECOVERY" -> recoveryService.recoverManually(
                claim.targetId, claim.executorId, claim.reason).status();
            case "RECHARGE_REFUND" -> rechargeService.refund(new StoredValueCommands.Refund(
                claim.targetId, claim.executorId, claim.reviewerId, claim.reason)).status();
            case "ACCOUNT_UNFREEZE" -> {
                accountService.unfreezeAccount(Long.valueOf(claim.targetId), claim.executorId,
                    claim.reviewerId, claim.reason, claim.approvalNo);
                yield "NORMAL";
            }
            case "BALANCE_ADJUSTMENT" -> adjustmentService.execute(Long.valueOf(claim.targetId), claim.amount,
                claim.approvalNo, claim.executorId, claim.reviewerId, claim.reason, claim.targetSnapshot).status();
            default -> throw BusinessException.badRequest("未知审批动作");
        };
    }

    private String currentSnapshot(ApprovalRow row) {
        return switch (row.actionType) {
            case "RECHARGE_POSTING_RECOVERY" -> rechargeTarget(RECOVERY, row.targetId, false).snapshot;
            case "RECHARGE_REFUND" -> rechargeTarget(REFUND, row.targetId, false).snapshot;
            case "ACCOUNT_UNFREEZE" -> accountTarget(Long.valueOf(row.targetId), false).snapshot;
            case "BALANCE_ADJUSTMENT" -> adjustmentService.currentSnapshot(Long.valueOf(row.targetId), row.amount);
            default -> throw BusinessException.badRequest("未知审批动作");
        };
    }

    private TargetData rechargeTarget(ActionDefinition definition, String rechargeNo) {
        return rechargeTarget(definition, rechargeNo, true);
    }

    private TargetData rechargeTarget(ActionDefinition definition, String rechargeNo, boolean validateState) {
        if (rechargeNo == null || rechargeNo.isBlank()) {
            throw BusinessException.badRequest("充值编号不能为空");
        }
        List<TargetData> rows = jdbc.query("""
            SELECT id, recharge_no, status, principal_amount, expected_bonus_amount, actual_bonus_amount,
                   account_id, provider_business_no, posting_attempt_count, provider_env, policy_version
            FROM stored_value_recharge WHERE recharge_no = ?
            """, (rs, n) -> {
                String status = rs.getString("status");
                BigDecimal amount = rs.getBigDecimal("principal_amount");
                String snapshot = String.join("|", "recharge=" + rs.getString("recharge_no"),
                    "id=" + rs.getLong("id"), "status=" + status, "principal=" + money(amount),
                    "expectedBonus=" + money(rs.getBigDecimal("expected_bonus_amount")),
                    "actualBonus=" + money(rs.getBigDecimal("actual_bonus_amount")),
                    "account=" + rs.getLong("account_id"), "providerBusinessNo=" + value(rs.getString("provider_business_no")),
                    "postingAttempts=" + rs.getInt("posting_attempt_count"),
                    "providerEnv=" + value(rs.getString("provider_env")));
                return new TargetData(definition, rs.getString("recharge_no"), snapshot,
                    rs.getString("policy_version"), amount, "CNY", status);
            }, rechargeNo);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("充值不存在");
        }
        TargetData target = rows.get(0);
        if (!validateState) {
            return target;
        }
        if (RECOVERY == definition) {
            if (!StoredValueRechargeStatus.any(target.state, StoredValueRechargeStatus.PROVIDER_SUCCEEDED_PENDING_POSTING,
                StoredValueRechargeStatus.POSTING)) {
                throw BusinessException.conflict("当前充值不能申请待入账恢复");
            }
        } else if (!StoredValueRechargeStatus.SUCCEEDED.matches(target.state)) {
            throw BusinessException.conflict("只有成功充值可以申请全额退款");
        }
        return target;
    }

    private TargetData accountTarget(Long accountId) {
        return accountTarget(accountId, true);
    }

    private TargetData accountTarget(Long accountId, boolean validateState) {
        if (accountId == null) {
            throw BusinessException.badRequest("账户不能为空");
        }
        List<TargetData> rows = jdbc.query("""
            SELECT a.id, a.status, a.version, a.principal_available, a.principal_frozen,
                   a.currency, COALESCE((SELECT SUM(b.available_amount + b.frozen_amount)
                                         FROM stored_value_bonus_batch b WHERE b.account_id = a.id), 0.00) AS bonus_total,
                   policy.policy_version
            FROM stored_value_account a
            JOIN stored_value_product product ON product.id = a.product_id
            LEFT JOIN stored_value_policy policy ON policy.id = product.current_policy_id
            WHERE a.id = ?
            """, (rs, n) -> {
                String status = rs.getString("status");
                String snapshot = String.join("|", "account=" + rs.getLong("id"), "status=" + status,
                    "version=" + rs.getLong("version"), "principalAvailable=" + money(rs.getBigDecimal("principal_available")),
                    "principalFrozen=" + money(rs.getBigDecimal("principal_frozen")),
                    "bonusTotal=" + money(rs.getBigDecimal("bonus_total")), "currency=" + rs.getString("currency"));
                return new TargetData(UNFREEZE, String.valueOf(rs.getLong("id")), snapshot,
                    rs.getString("policy_version"), null, rs.getString("currency"), status);
            }, accountId);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("储值账户不存在");
        }
        TargetData target = rows.get(0);
        if (!validateState) {
            return target;
        }
        if (!"FROZEN".equals(target.state)) {
            throw BusinessException.conflict("只有冻结账户可以申请解冻");
        }
        if (target.policyVersion == null || target.policyVersion.isBlank()) {
            throw BusinessException.conflict("账户缺少政策版本");
        }
        return target;
    }

    private ApprovalRow find(String approvalNo, boolean forUpdate) {
        if (approvalNo == null || approvalNo.isBlank()) {
            throw BusinessException.badRequest("审批编号不能为空");
        }
        List<ApprovalRow> rows = jdbc.query("SELECT * FROM stored_value_approval WHERE approval_no = ?"
                + (forUpdate ? " FOR UPDATE" : ""), approvalMapper(), approvalNo);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("审批不存在");
        }
        return rows.get(0);
    }

    private org.springframework.jdbc.core.RowMapper<ApprovalRow> approvalMapper() {
        return (rs, n) -> new ApprovalRow(
            rs.getLong("id"), rs.getString("approval_no"), rs.getString("active_key"),
            rs.getString("action_type"), rs.getString("target_type"), rs.getString("target_id"),
            rs.getString("request_hash"), rs.getString("target_snapshot"), rs.getBigDecimal("amount"),
            rs.getString("currency"), rs.getString("reason"), rs.getLong("requester_id"),
            rs.getString("review_permission"), rs.getString("execute_permission"), rs.getString("status"),
            timestamp(rs, "expires_at").toLocalDateTime(), rs.getString("policy_version"), rs.getLong("version"),
            nullableLong(rs.getObject("reviewer_id")), timestampNullable(rs, "reviewed_at"),
            rs.getString("review_reason"), nullableLong(rs.getObject("executor_id")),
            timestampNullable(rs, "executed_at"), rs.getString("execution_result"),
            timestamp(rs, "create_time").toLocalDateTime(), timestamp(rs, "update_time").toLocalDateTime());
    }

    private StoredValueResponses.ApprovalListResponse listResponse(ApprovalRow row) {
        Long actorId = SecurityContext.currentUserId();
        return new StoredValueResponses.ApprovalListResponse(row.approvalNo, row.actionType, row.targetType,
            targetRef(row), userRef(row.requesterId), actorId != null && actorId.equals(row.requesterId), row.amount,
            row.currency, row.reviewPermission, row.executePermission, row.status, row.expiresAt,
            row.createTime, row.updateTime);
    }

    private StoredValueResponses.ApprovalDetailResponse response(ApprovalRow row) {
        Long actorId = SecurityContext.currentUserId();
        return new StoredValueResponses.ApprovalDetailResponse(row.approvalNo, row.actionType, row.targetType,
            targetRef(row), userRef(row.requesterId), userRef(row.reviewerId), userRef(row.executorId),
            actorId != null && actorId.equals(row.requesterId), row.amount, row.currency, row.reason,
            row.reviewPermission, row.executePermission, row.status, row.expiresAt, row.policyVersion,
            row.version, row.reviewedAt, row.reviewReason, row.executedAt, row.executionResult,
            row.createTime, row.updateTime);
    }

    private String targetRef(ApprovalRow row) {
        return switch (row.targetType) {
            case "RECHARGE" -> StoredValueReference.recharge(row.targetId);
            case "ACCOUNT" -> StoredValueReference.account(Long.valueOf(row.targetId));
            case "RECONCILIATION_DIFFERENCE" -> StoredValueReference.difference(Long.valueOf(row.targetId));
            default -> StoredValueReference.summary(row.targetId);
        };
    }

    private void requireAnyActionPermission(long actor, ApprovalRow row) {
        boolean review = SecurityContext.hasAuthority(row.reviewPermission)
            && hasLivePermission(actor, row.reviewPermission);
        boolean execute = SecurityContext.hasAuthority(row.executePermission)
            && hasLivePermission(actor, row.executePermission);
        if (!review && !execute) {
            throw BusinessException.forbidden("当前账号没有该审批动作权限");
        }
    }

    private long requireActorWithPermission(String permission) {
        long actor = requireActiveActor();
        requirePermission(actor, permission);
        return actor;
    }

    private long requireActiveActor() {
        Long actor = SecurityContext.currentUserId();
        if (actor == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_user WHERE id = ? AND status = 'ACTIVE' AND deleted = 0
            """, Integer.class, actor);
        if (count == null || count != 1) {
            throw BusinessException.forbidden("当前账号已失效");
        }
        return actor;
    }

    private void requirePermission(long actor, String permission) {
        if (!SecurityContext.hasAuthority(permission) || !hasLivePermission(actor, permission)) {
            throw BusinessException.forbidden("当前账号没有所需储值权限");
        }
    }

    private boolean hasLivePermission(long actor, String permission) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id
            JOIN sys_role_menu rm ON rm.role_id = ur.role_id
            JOIN sys_role r ON r.id = rm.role_id
            JOIN sys_menu m ON m.id = rm.menu_id
            WHERE u.id = ? AND u.status = 'ACTIVE' AND u.deleted = 0
              AND r.status = 'ACTIVE' AND r.deleted = 0
              AND m.status = 'ACTIVE' AND m.deleted = 0 AND m.perms = ?
            """, Integer.class, actor, permission);
        return count != null && count > 0;
    }

    private void ensurePending(ApprovalRow row) {
        if (!"PENDING".equals(row.status)) {
            throw BusinessException.conflict("审批不是待复核状态");
        }
    }

    private boolean expired(ApprovalRow row) {
        return !row.expiresAt.isAfter(LocalDateTime.now());
    }

    private void expire(ApprovalRow row, long actor, String reason) {
        int updated = jdbc.update("""
            UPDATE stored_value_approval
            SET status = 'EXPIRED', active_key = NULL, execution_result = ?, version = version + 1
            WHERE id = ? AND status IN ('PENDING','APPROVED') AND version = ?
            """, reason, row.id, row.version);
        if (updated == 1) {
            appendEvent(row.id, "EXPIRED", actor, null, row.status, "EXPIRED", reason, null);
        }
    }

    private void failClaim(ApprovalRow row, long actor, String reason) {
        int updated = jdbc.update("""
            UPDATE stored_value_approval
            SET status = 'FAILED', active_key = NULL, execution_result = ?, version = version + 1
            WHERE id = ? AND status = 'APPROVED' AND version = ?
            """, reason, row.id, row.version);
        if (updated == 1) {
            appendEvent(row.id, "FAILED", actor, row.executePermission, row.status, "FAILED", reason, null);
        }
    }

    private void appendEvent(Long approvalId, String eventType, Long actorId, String actorPermission,
                              String fromStatus, String toStatus, String reason, String result) {
        Integer next = jdbc.queryForObject("""
            SELECT COALESCE(MAX(event_no), 0) + 1 FROM stored_value_approval_event WHERE approval_id = ?
            """, Integer.class, approvalId);
        String payload = String.join("|", value(eventType), value(actorId), value(actorPermission),
            value(fromStatus), value(toStatus), value(reason), value(result));
        jdbc.update("""
            INSERT INTO stored_value_approval_event
              (approval_id, event_no, event_type, actor_id, actor_permission, from_status, to_status,
               payload_hash, reason, result)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, approvalId, next, eventType, actorId, actorPermission, fromStatus, toStatus,
            digest(payload), limit(reason), limit(result));
    }

    private String requireReason(String reason, String message) {
        if (reason == null || reason.isBlank() || reason.length() > 512) {
            throw BusinessException.badRequest(message);
        }
        return reason.trim();
    }

    private String normalizeOptionalReason(String reason) {
        return reason == null || reason.isBlank() ? null : requireReason(reason, "复核说明不能超过 512 个字符");
    }

    private String limit(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }

    private boolean same(Long left, Long right) {
        return left != null && left.equals(right);
    }

    private String userRef(Long userId) {
        return StoredValueReference.user(userId);
    }

    private String money(BigDecimal amount) {
        return amount == null ? "null" : amount.setScale(2).toPlainString();
    }

    private String value(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private int pageNum(PageQuery query) {
        return (int) Math.max(1, query == null ? 1 : query.getPageNum());
    }

    private int pageSize(PageQuery query) {
        return (int) Math.min(100, Math.max(1, query == null ? 10 : query.getPageSize()));
    }

    private Timestamp timestamp(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        if (value == null) {
            throw new java.sql.SQLException("required approval timestamp missing: " + column);
        }
        return value;
    }

    private LocalDateTime timestampNullable(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    public record ApprovalExecutionClaim(Long id, String approvalNo, String actionType, String targetType,
                                         String targetId, String targetSnapshot, BigDecimal amount,
                                         String reason, Long reviewerId, Long executorId) {
    }

    private record ActionDefinition(String actionType, String targetType,
                                    String reviewPermission, String executePermission) {
    }

    private record TargetData(ActionDefinition definition, String targetId, String snapshot,
                              String policyVersion, BigDecimal amount, String currency, String state) {
    }

    private record ApprovalRow(Long id, String approvalNo, String activeKey, String actionType,
                               String targetType, String targetId, String requestHash, String targetSnapshot,
                               BigDecimal amount, String currency, String reason, Long requesterId,
                               String reviewPermission, String executePermission, String status,
                               LocalDateTime expiresAt, String policyVersion, long version, Long reviewerId,
                               LocalDateTime reviewedAt, String reviewReason, Long executorId,
                               LocalDateTime executedAt, String executionResult, LocalDateTime createTime,
                               LocalDateTime updateTime) {
    }

    private record TransitionResult<T>(T value, BusinessException failure) {
        private static <T> TransitionResult<T> success(T value) {
            return new TransitionResult<>(value, null);
        }

        private static <T> TransitionResult<T> failure(BusinessException failure) {
            return new TransitionResult<>(null, failure);
        }

        private T orThrow() {
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }
}
