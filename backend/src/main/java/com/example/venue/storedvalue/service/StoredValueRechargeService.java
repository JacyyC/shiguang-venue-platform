package com.example.venue.storedvalue.service;

import com.example.venue.common.BusinessException;
import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.domain.StoredValueMoney;
import com.example.venue.storedvalue.domain.StoredValueRechargeStatus;
import com.example.venue.storedvalue.provider.StoredValueProviderDescriptor;
import com.example.venue.storedvalue.provider.StoredValueProviderRefundResult;
import com.example.venue.storedvalue.provider.StoredValueProviderVerification;
import com.example.venue.storedvalue.provider.StoredValueRechargeProvider;
import com.example.venue.storedvalue.provider.StoredValueProviderCreateResult;
import com.example.venue.storedvalue.provider.StoredValueProviderCreateRequest;
import com.example.venue.storedvalue.provider.StoredValueProviderRefundRequest;
import com.example.venue.storedvalue.provider.StoredValueProviderRouter;
import org.springframework.stereotype.Service;
import org.springframework.dao.EmptyResultDataAccessException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Provider orchestration boundary. Its public create/callback/refund methods
 * intentionally have no transaction: external work is followed by a short local transaction.
 */
@Service
public class StoredValueRechargeService {
    private final StoredValueConfigService configService;
    private final StoredValueProviderRouter providerRouter;
    private final StoredValueRechargePersistenceService persistence;

    public StoredValueRechargeService(StoredValueConfigService configService,
                                      StoredValueProviderRouter providerRouter,
                                      StoredValueRechargePersistenceService persistence) {
        this.configService = configService;
        this.providerRouter = providerRouter;
        this.persistence = persistence;
    }

    public StoredValueRechargeResult create(StoredValueCommands.CreateRecharge command) {
        if (command == null || command.userId() == null || command.productId() == null) {
            throw BusinessException.badRequest("充值请求不完整");
        }
        StoredValueRechargePersistenceService.PreparedRecharge replay = persistence.findReplay(command);
        if (replay != null) {
            return persistence.findResult(replay.rechargeNo());
        }
        StoredValueProductSnapshot product = configService.requireProduct(command.productId());
        StoredValueRechargeProvider provider = providerRouter.require(command.providerKey(), product.id());
        StoredValueProviderDescriptor descriptor = provider.descriptor();
        StoredValueRechargePersistenceService.PreparedRecharge prepared = persistence.prepare(
            command, descriptor, LocalDateTime.now());
        if (prepared.replayed()) {
            return persistence.findResult(prepared.rechargeNo());
        }
        StoredValueProviderCreateResult result;
        try {
            result = provider.create(new StoredValueProviderCreateRequest(
                prepared.rechargeNo(), prepared.providerBusinessNo(), prepared.principalAmount(),
                product.currency(), product.policy().version()));
        } catch (RuntimeException exception) {
            persistence.markProviderManualReview(prepared.rechargeNo(), "PROVIDER_CREATE_EXCEPTION");
            throw exception;
        }
        persistence.recordProviderCreate(prepared, descriptor, result);
        return persistence.findResult(prepared.rechargeNo());
    }

    public StoredValueRechargeResult handleCallback(StoredValueCommands.Callback callback) {
        StoredValueCommands.Callback received = withRequestId(callback);
        String digest = digest(received);
        persistence.recordCallbackEvidence(received, null, digest);
        try {
            validateCallbackEnvelope(received);
        } catch (RuntimeException exception) {
            persistence.markCallbackRejected(received.requestId(), "INVALID_CALLBACK_ENVELOPE");
            throw exception;
        }
        Long rechargeId = null;
        try {
            rechargeId = persistence.findResult(received.rechargeNo()).id();
        } catch (EmptyResultDataAccessException ignored) {
            // The reception evidence is retained for an unknown recharge reference.
        }
        if (rechargeId == null) {
            persistence.markCallbackRejected(received.requestId(), "UNKNOWN_RECHARGE");
            throw BusinessException.notFound("充值业务不存在，回调已进入人工处置");
        }
        persistence.attachCallbackRecharge(received.requestId(), rechargeId);
        if (!"DEV".equals(received.providerEnv()) && !"SANDBOX".equals(received.providerEnv())) {
            persistence.markCallbackRejected(received.requestId(), "STORED_VALUE_PROD_CALLBACK_DISABLED");
            throw BusinessException.forbidden("生产储值回调永久关闭");
        }
        StoredValueRechargePersistenceService.ProviderSnapshot snapshot = persistence.findProviderSnapshot(received.rechargeNo());
        StoredValueRechargeProvider provider;
        try {
            provider = providerRouter.require(snapshot.providerKey(), snapshot.productId());
        } catch (RuntimeException exception) {
            persistence.markCallbackRejected(received.requestId(), "PROVIDER_NOT_AVAILABLE");
            throw exception;
        }
        StoredValueProviderDescriptor descriptor = provider.descriptor();
        StoredValueProviderVerification verification;
        try {
            verification = provider.verify(received);
        } catch (RuntimeException exception) {
            persistence.markProviderManualReview(received.rechargeNo(), "PROVIDER_VERIFY_EXCEPTION");
            persistence.markCallbackRejected(received.requestId(), "PROVIDER_VERIFY_EXCEPTION");
            throw exception;
        }
        if (verification == null) {
            persistence.markProviderManualReview(received.rechargeNo(), "PROVIDER_VERIFY_NULL");
            persistence.markCallbackRejected(received.requestId(), "PROVIDER_VERIFY_NULL");
            throw BusinessException.conflict("Provider 回调验证未返回结果");
        }
        StoredValueRechargeResult outcome = persistence.recordCallbackOutcome(received, digest, verification, descriptor);
        if (StoredValueRechargeStatus.any(outcome.status(), StoredValueRechargeStatus.PROVIDER_SUCCEEDED_PENDING_POSTING,
            StoredValueRechargeStatus.POSTING)) {
            return postPending(outcome.rechargeNo(), outcome.externalRef(), "CALLBACK", null, null, false);
        }
        return outcome;
    }

    public StoredValueRechargeResult recoverPosting(String rechargeNo, Long operatorId, String reason) {
        if (operatorId == null) {
            throw BusinessException.forbidden("人工恢复必须绑定当前操作主体");
        }
        if (reason == null || reason.isBlank() || reason.length() > 512) {
            throw BusinessException.badRequest("人工恢复原因不能为空且不得超过 512 个字符");
        }
        return postPending(rechargeNo, null, "MANUAL_RECOVERY", operatorId, reason.trim(), true);
    }

    public StoredValueRechargeResult postPending(String rechargeNo, String externalRef,
                                                  String sourceType, Long operatorId,
                                                  String reason, boolean force) {
        StoredValueRechargePersistenceService.PostingClaim claim = persistence.claimPosting(
            rechargeNo, sourceType, operatorId, reason, force);
        if (!claim.acquired()) {
            return persistence.findResult(claim.rechargeNo());
        }
        try {
            return persistence.completePosting(claim, externalRef);
        } catch (BusinessException exception) {
            persistence.markPostingManualReview(claim, "POSTING_DOMAIN_GATE", exception.getMessage());
            return persistence.findResult(claim.rechargeNo());
        } catch (RuntimeException exception) {
            persistence.recordPostingFailure(claim, "POSTING_TRANSIENT_FAILURE",
                exception.getClass().getSimpleName());
            throw exception;
        }
    }

    public StoredValueRechargeResult confirmDevCallback(Long userId, String rechargeNo, String status) {
        if (userId == null) {
            throw BusinessException.forbidden("储值充值归属用户缺失");
        }
        if (rechargeNo == null || rechargeNo.isBlank()) {
            throw BusinessException.badRequest("充值编号不能为空");
        }
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!"SUCCESS".equals(normalizedStatus) && !"FAILED".equals(normalizedStatus)) {
            throw BusinessException.badRequest("DEV 回调状态只能是 SUCCESS 或 FAILED");
        }
        StoredValueRechargeResult recharge = persistence.findResultForUser(rechargeNo, userId);
        StoredValueRechargePersistenceService.ProviderSnapshot snapshot = persistence.findProviderSnapshot(rechargeNo);
        if (!"DEV".equals(snapshot.providerEnv())) {
            throw BusinessException.forbidden("只有 DEV 储值充值可以在页面确认回调");
        }
        StoredValueRechargeProvider provider = providerRouter.require(snapshot.providerKey(), snapshot.productId());
        StoredValueCommands.Callback unsigned = new StoredValueCommands.Callback(
            snapshot.providerKey(), snapshot.providerEnv(), snapshot.providerName(), snapshot.configVersion(),
            rechargeNo, "DEV:" + rechargeNo + ":" + normalizedStatus, "DEV-" + rechargeNo,
            com.example.venue.storedvalue.provider.DevStoredValueProvider.MERCHANT_IDENTIFIER,
            normalizedStatus, StoredValueMoney.CNY, recharge.principalAmount(), null,
            "DEV callback confirmation");
        String signature = provider.signCallback(unsigned);
        if (signature == null || signature.isBlank()) {
            throw BusinessException.conflict("当前 DEV Provider 不支持受控回调签名");
        }
        return handleCallback(withSignature(unsigned, signature));
    }

    public StoredValueRechargeResult refund(StoredValueCommands.Refund command) {
        if (command == null) {
            throw BusinessException.badRequest("退款请求不能为空");
        }
        // Reject disabled environments before reserving a local refund state.
        StoredValueRechargePersistenceService.ProviderSnapshot snapshot =
            persistence.findProviderSnapshot(command.rechargeNo());
        StoredValueRechargeProvider provider = providerRouter.requireForRefund(snapshot.providerKey(), snapshot.productId());
        StoredValueRechargePersistenceService.PreparedRefund prepared = persistence.prepareRefund(
            command.rechargeNo(), command.operatorId(), command.reviewerId(), command.reason());
        if (prepared.replayed()) {
            return persistence.findResult(prepared.rechargeNo());
        }
        StoredValueProviderRefundResult result;
        try {
            result = provider.refund(new StoredValueProviderRefundRequest(
                prepared.rechargeNo(), prepared.refundNo(), prepared.providerBusinessNo(),
                prepared.amount(), StoredValueMoney.CNY, prepared.policyVersion()));
        } catch (RuntimeException exception) {
            return persistence.finalizeRefund(prepared,
                new StoredValueProviderRefundResult("FAILED", null, null, null, "PROVIDER_REFUND_EXCEPTION"));
        }
        return persistence.finalizeRefund(prepared, result);
    }

    public void dispute(StoredValueCommands.Dispute command) {
        persistence.openDispute(command);
    }

    private void validateCallbackEnvelope(StoredValueCommands.Callback callback) {
        if (callback == null || callback.providerKey() == null || callback.providerEnv() == null
            || callback.providerName() == null || callback.configVersion() == null
            || callback.rechargeNo() == null || callback.externalEventId() == null
            || callback.currency() == null || callback.amount() == null
            || callback.signatureDigest() == null || callback.signatureDigest().isBlank()
            || callback.minimalSummary() == null || callback.minimalSummary().length() > 1024
            || tooLong(callback.providerKey(), 128) || tooLong(callback.providerEnv(), 16)
            || tooLong(callback.providerName(), 64) || tooLong(callback.configVersion(), 96)
            || tooLong(callback.rechargeNo(), 128) || tooLong(callback.externalEventId(), 128)
            || tooLong(callback.externalRef(), 128) || tooLong(callback.merchantIdentifier(), 128)
            || tooLong(callback.status(), 32) || tooLong(callback.currency(), 3)
            || tooLong(callback.signatureDigest(), 128)) {
            throw BusinessException.badRequest("充值回调最小证据不完整");
        }
        StoredValueMoney.of(callback.currency(), callback.amount());
    }

    private String digest(StoredValueCommands.Callback callback) {
        String canonical = String.join("|", value(callback == null ? null : callback.providerKey(), 256),
            value(callback == null ? null : callback.providerEnv(), 64),
            value(callback == null ? null : callback.providerName(), 128),
            value(callback == null ? null : callback.configVersion(), 128),
            value(callback == null ? null : callback.rechargeNo(), 256),
            value(callback == null ? null : callback.externalEventId(), 256),
            value(callback == null ? null : callback.externalRef(), 256),
            value(callback == null ? null : callback.merchantIdentifier(), 256),
            value(callback == null ? null : callback.currency(), 16),
            value(callback == null || callback.amount() == null ? null : callback.amount().toPlainString(), 64),
            value(callback == null ? null : callback.status(), 64),
            value(callback == null ? null : callback.signatureDigest(), 256),
            value(callback == null ? null : callback.minimalSummary(), 2048));
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private StoredValueCommands.Callback withRequestId(StoredValueCommands.Callback callback) {
        String requestId = callback == null || callback.requestId() == null || callback.requestId().isBlank()
            ? "SVRCV-" + UUID.randomUUID().toString().replace("-", "")
            : callback.requestId().trim();
        if (callback == null) {
            return new StoredValueCommands.Callback(null, null, null, null, null, null, null, null,
                null, null, null, null, null, requestId);
        }
        if (requestId.length() > 64) {
            requestId = "SVRCV-" + UUID.randomUUID().toString().replace("-", "");
        }
        return new StoredValueCommands.Callback(callback.providerKey(), callback.providerEnv(), callback.providerName(),
            callback.configVersion(), callback.rechargeNo(), callback.externalEventId(), callback.externalRef(),
            callback.merchantIdentifier(), callback.status(), callback.currency(), callback.amount(),
            callback.signatureDigest(), callback.minimalSummary(), requestId);
    }

    private StoredValueCommands.Callback withSignature(StoredValueCommands.Callback callback, String signature) {
        return new StoredValueCommands.Callback(callback.providerKey(), callback.providerEnv(), callback.providerName(),
            callback.configVersion(), callback.rechargeNo(), callback.externalEventId(), callback.externalRef(),
            callback.merchantIdentifier(), callback.status(), callback.currency(), callback.amount(), signature,
            callback.minimalSummary(), callback.requestId());
    }

    private boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private String value(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return "[OVERSIZE:" + value.length() + "]";
    }
}
