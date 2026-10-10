package com.example.venue.storedvalue.service;

import com.example.venue.common.BusinessException;
import com.example.venue.storedvalue.domain.StoredValuePolicy;
import com.example.venue.storedvalue.provider.StoredValueProviderDescriptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Explicit DEV/SANDBOX configuration path. V21 intentionally does not seed a
 * usable product or provider, so production-like data cannot appear from a migration.
 */
@Service
public class StoredValueConfigurationService {
    private static final String ISSUER_CODE = "HANGZHOU_VENUE_STORED_VALUE_DEV";
    private static final String LEGAL_NAME = "杭州场地空间预约科技有限公司";
    private static final String NOTICE = "仅用于 DEV/SANDBOX 模拟发行，不对应真实工商主体、支付资质或生产合规结论";
    private static final String DEV_PROVIDER_KEY = "stored-value.dev";
    private static final String DEV_PROVIDER_ENV = "DEV";
    private static final String DEV_PROVIDER_NAME = "DEV_STORED_VALUE";
    private static final String DEV_PROVIDER_CONFIG = "DEV_V1";
    private static final String DEV_MERCHANT_IDENTIFIER = "DEV_STORED_VALUE";

    private final JdbcTemplate jdbc;
    private final StoredValueConfigService configService;

    public StoredValueConfigurationService(JdbcTemplate jdbc, StoredValueConfigService configService) {
        this.jdbc = jdbc;
        this.configService = configService;
    }

    @Transactional
    public StoredValueProductSnapshot enableDevProduct(String productCode, String productName, Long operatorId) {
        if (productCode == null || productCode.isBlank() || operatorId == null) {
            throw BusinessException.badRequest("储值产品配置不完整");
        }
        Long issuerId = findIssuer();
        if (issuerId == null) {
            jdbc.update("""
                INSERT INTO stored_value_issuer
                  (issuer_code, legal_name_snapshot, liability_holder_snapshot, environment,
                   simulation_notice, status, created_by)
                VALUES (?, ?, ?, 'DEV', ?, 'ENABLED', ?)
                """, ISSUER_CODE, LEGAL_NAME, LEGAL_NAME, NOTICE, operatorId);
            issuerId = findIssuer();
        }
        Long productId = findProduct(issuerId, productCode);
        if (productId == null) {
            jdbc.update("""
                INSERT INTO stored_value_product
                  (issuer_id, product_code, product_name, currency, environment,
                   status, recharge_enabled, stored_value_spend_enabled, created_by)
                VALUES (?, ?, ?, 'CNY', 'DEV', 'ENABLED', 1, 0, ?)
                """, issuerId, productCode, productName == null ? productCode : productName, operatorId);
            productId = findProduct(issuerId, productCode);
        }
        Long policyId = jdbc.query("""
            SELECT id FROM stored_value_policy
            WHERE product_id = ? AND policy_version = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, productId, StoredValuePolicy.VERSION);
        if (policyId == null) {
            StoredValuePolicy policy = StoredValuePolicy.standard();
            jdbc.update("""
                INSERT INTO stored_value_policy
                  (product_id, policy_version, currency, effective_from, status,
                   min_recharge_amount, max_recharge_amount, daily_recharge_limit,
                   principal_balance_limit, total_balance_limit, max_pending_recharges,
                   max_daily_initiations, bonus_rate_percent, bonus_per_recharge_limit,
                   monthly_bonus_limit, bonus_valid_days, refund_compensation_valid_days,
                   timezone_id, policy_digest, created_by)
                VALUES (?, ?, 'CNY', ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, productId, policy.version(), LocalDateTime.now(), policy.minimumRecharge(),
                policy.maximumRecharge(), policy.dailyRechargeLimit(), policy.principalBalanceLimit(),
                policy.totalBalanceLimit(), policy.maxPendingRecharges(), policy.maxDailyInitiations(),
                policy.bonusRatePercent(), policy.bonusPerRechargeLimit(), policy.monthlyBonusLimit(),
                policy.bonusValidDays(), policy.refundCompensationValidDays(), policy.zoneId().getId(),
                digest(policy), operatorId);
            policyId = jdbc.query("""
                SELECT id FROM stored_value_policy WHERE product_id = ? AND policy_version = ?
                """, rs -> rs.next() ? rs.getLong(1) : null, productId, StoredValuePolicy.VERSION);
        }
        jdbc.update("UPDATE stored_value_product SET current_policy_id = ?, status = 'ENABLED', recharge_enabled = 1 WHERE id = ?",
            policyId, productId);
        ensureDevProviderConfig(productId, operatorId);
        return configService.requireProduct(productId);
    }

    @Transactional
    public void registerProvider(Long productId, StoredValueProviderDescriptor descriptor,
                                 String merchantIdentifierDigest, Long operatorId, boolean manualConfirmed) {
        if (productId == null || descriptor == null || operatorId == null
            || descriptor.providerKey() == null || descriptor.providerKey().isBlank()) {
            throw BusinessException.badRequest("储值 Provider 配置不完整");
        }
        if (!"DEV".equals(descriptor.providerEnv()) && !"SANDBOX".equals(descriptor.providerEnv())) {
            throw BusinessException.forbidden("PROD 储值 Provider 永久关闭");
        }
        if ("SANDBOX".equals(descriptor.providerEnv()) && (!descriptor.enabled() || !manualConfirmed)) {
            throw BusinessException.conflict("支付宝 SANDBOX 必须显式启用并人工确认");
        }
        jdbc.update("""
            INSERT INTO stored_value_provider_config
              (product_id, provider_key, provider_env, provider_name, config_version,
               enabled, manual_confirmed, merchant_identifier_digest, status, created_by,
               confirmed_by, confirmed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), manual_confirmed = VALUES(manual_confirmed),
              merchant_identifier_digest = VALUES(merchant_identifier_digest), status = VALUES(status),
              confirmed_by = VALUES(confirmed_by), confirmed_at = VALUES(confirmed_at)
            """, productId, descriptor.providerKey(), descriptor.providerEnv(), descriptor.providerName(),
            descriptor.configVersion(), descriptor.enabled() ? 1 : 0, manualConfirmed ? 1 : 0,
            merchantIdentifierDigest, descriptor.enabled() ? "ACTIVE" : "DISABLED", operatorId,
            manualConfirmed ? operatorId : null, manualConfirmed ? LocalDateTime.now() : null);
    }

    @Transactional
    public StoredValueProductSnapshot setProductFlags(Long productId, Boolean rechargeEnabled,
                                                      Boolean spendEnabled, String status, Long operatorId) {
        if (productId == null || operatorId == null
            || (rechargeEnabled == null && spendEnabled == null && (status == null || status.isBlank()))) {
            throw BusinessException.badRequest("储值产品启停参数不能为空");
        }
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_product WHERE id = ?",
            Integer.class, productId);
        if (exists == null || exists != 1) {
            throw BusinessException.notFound("储值产品不存在");
        }
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (normalizedStatus != null
            && !List.of("DRAFT", "ENABLED", "DISABLED").contains(normalizedStatus)) {
            throw BusinessException.badRequest("储值产品状态无效");
        }
        List<String> assignments = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        if (rechargeEnabled != null) {
            assignments.add("recharge_enabled = ?");
            arguments.add(rechargeEnabled ? 1 : 0);
        }
        if (spendEnabled != null) {
            assignments.add("stored_value_spend_enabled = ?");
            arguments.add(spendEnabled ? 1 : 0);
        }
        if (normalizedStatus != null) {
            assignments.add("status = ?");
            arguments.add(normalizedStatus);
        }
        assignments.add("update_time = CURRENT_TIMESTAMP");
        arguments.add(productId);
        jdbc.update("UPDATE stored_value_product SET " + String.join(", ", assignments) + " WHERE id = ?",
            arguments.toArray());
        return configService.requireProduct(productId);
    }

    private Long findIssuer() {
        return jdbc.query("SELECT id FROM stored_value_issuer WHERE issuer_code = ?",
            rs -> rs.next() ? rs.getLong(1) : null, ISSUER_CODE);
    }

    private Long findProduct(Long issuerId, String productCode) {
        return jdbc.query("SELECT id FROM stored_value_product WHERE issuer_id = ? AND product_code = ?",
            rs -> rs.next() ? rs.getLong(1) : null, issuerId, productCode);
    }

    private void ensureDevProviderConfig(Long productId, Long operatorId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_provider_config WHERE product_id = ?",
            Integer.class, productId);
        if (count != null && count > 0) {
            return;
        }
        jdbc.update("""
            INSERT INTO stored_value_provider_config
              (product_id, provider_key, provider_env, provider_name, config_version,
               enabled, manual_confirmed, merchant_identifier_digest, status, created_by,
               confirmed_by, confirmed_at)
            VALUES (?, ?, ?, ?, ?, 1, 1, ?, 'ACTIVE', ?, ?, CURRENT_TIMESTAMP)
            """, productId, DEV_PROVIDER_KEY, DEV_PROVIDER_ENV, DEV_PROVIDER_NAME, DEV_PROVIDER_CONFIG,
            digestText(DEV_MERCHANT_IDENTIFIER), operatorId, operatorId);
    }

    private String digest(StoredValuePolicy policy) {
        String value = policy.version() + "|" + policy.minimumRecharge() + "|" + policy.maximumRecharge()
            + "|" + policy.dailyRechargeLimit() + "|" + policy.principalBalanceLimit() + "|"
            + policy.totalBalanceLimit() + "|" + policy.maxPendingRecharges() + "|"
            + policy.maxDailyInitiations() + "|" + policy.bonusRatePercent() + "|"
            + policy.bonusPerRechargeLimit() + "|" + policy.monthlyBonusLimit() + "|"
            + policy.bonusValidDays() + "|" + policy.refundCompensationValidDays();
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

    private String digestText(String value) {
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
}
