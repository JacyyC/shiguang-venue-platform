package com.example.venue.storedvalue.service;

import com.example.venue.common.BusinessException;
import com.example.venue.storedvalue.domain.StoredValuePolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneId;

@Service
public class StoredValueConfigService {
    private final JdbcTemplate jdbc;

    public StoredValueConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public StoredValueProductSnapshot requireProduct(Long productId) {
        if (productId == null) {
            throw BusinessException.badRequest("储值产品不能为空");
        }
        try {
            return jdbc.queryForObject("""
                SELECT p.id, p.issuer_id, i.issuer_code, i.status AS issuer_status,
                       p.product_code, p.currency, p.environment, p.status,
                       p.recharge_enabled, p.stored_value_spend_enabled,
                       policy.id AS policy_id, policy.policy_version,
                       policy.min_recharge_amount, policy.max_recharge_amount,
                       policy.daily_recharge_limit, policy.principal_balance_limit,
                       policy.total_balance_limit, policy.max_pending_recharges,
                       policy.max_daily_initiations, policy.bonus_rate_percent,
                       policy.bonus_per_recharge_limit, policy.monthly_bonus_limit,
                       policy.bonus_valid_days, policy.refund_compensation_valid_days,
                       policy.timezone_id
                FROM stored_value_product p
                JOIN stored_value_issuer i ON i.id = p.issuer_id
                JOIN stored_value_policy policy ON policy.id = p.current_policy_id
                WHERE p.id = ?
                """, (rs, rowNum) -> {
                StoredValuePolicy policy = new StoredValuePolicy(
                    rs.getString("policy_version"), rs.getBigDecimal("min_recharge_amount"),
                    rs.getBigDecimal("max_recharge_amount"), rs.getBigDecimal("daily_recharge_limit"),
                    rs.getBigDecimal("principal_balance_limit"), rs.getBigDecimal("total_balance_limit"),
                    rs.getInt("max_pending_recharges"), rs.getInt("max_daily_initiations"),
                    rs.getBigDecimal("bonus_rate_percent"), rs.getBigDecimal("bonus_per_recharge_limit"),
                    rs.getBigDecimal("monthly_bonus_limit"), rs.getInt("bonus_valid_days"),
                    rs.getInt("refund_compensation_valid_days"),
                    ZoneId.of(rs.getString("timezone_id")));
                return new StoredValueProductSnapshot(
                    rs.getLong("id"), rs.getLong("issuer_id"), rs.getString("issuer_code"),
                    rs.getString("issuer_status"), rs.getString("product_code"), rs.getString("currency"),
                    rs.getString("environment"), rs.getString("status"), rs.getBoolean("recharge_enabled"),
                    rs.getBoolean("stored_value_spend_enabled"), rs.getLong("policy_id"), policy);
            }, productId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw BusinessException.notFound("储值产品不存在或未配置有效政策");
        }
    }

    public void requireRechargeable(StoredValueProductSnapshot product) {
        if (!product.enabled() || !product.rechargeEnabled()) {
            throw BusinessException.conflict("储值产品未启用充值");
        }
        if (!"CNY".equals(product.currency()) || !"DEV".equals(product.environment())
            && !"SANDBOX".equals(product.environment())) {
            throw BusinessException.forbidden("储值产品环境或币种不允许");
        }
    }

    public void requireSpendable(StoredValueProductSnapshot product) {
        if (product == null || !product.enabled() || !product.storedValueSpendEnabled()) {
            throw BusinessException.conflict("储值消费入口当前已停用");
        }
        if (!"CNY".equals(product.currency())
            || !"DEV".equals(product.environment()) && !"SANDBOX".equals(product.environment())) {
            throw BusinessException.forbidden("储值消费环境或币种不允许");
        }
    }

    public StoredValuePolicy requirePolicy(Long productId, String policyVersion) {
        if (productId == null || policyVersion == null || policyVersion.isBlank()) {
            throw BusinessException.badRequest("储值政策快照不完整");
        }
        try {
            return jdbc.queryForObject("""
                SELECT policy_version, min_recharge_amount, max_recharge_amount,
                       daily_recharge_limit, principal_balance_limit, total_balance_limit,
                       max_pending_recharges, max_daily_initiations, bonus_rate_percent,
                       bonus_per_recharge_limit, monthly_bonus_limit, bonus_valid_days,
                       refund_compensation_valid_days, timezone_id
                FROM stored_value_policy
                WHERE product_id = ? AND policy_version = ?
                """, (rs, rowNum) -> new StoredValuePolicy(
                    rs.getString("policy_version"), rs.getBigDecimal("min_recharge_amount"),
                    rs.getBigDecimal("max_recharge_amount"), rs.getBigDecimal("daily_recharge_limit"),
                    rs.getBigDecimal("principal_balance_limit"), rs.getBigDecimal("total_balance_limit"),
                    rs.getInt("max_pending_recharges"), rs.getInt("max_daily_initiations"),
                    rs.getBigDecimal("bonus_rate_percent"), rs.getBigDecimal("bonus_per_recharge_limit"),
                    rs.getBigDecimal("monthly_bonus_limit"), rs.getInt("bonus_valid_days"),
                    rs.getInt("refund_compensation_valid_days"), ZoneId.of(rs.getString("timezone_id"))),
                productId, policyVersion);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw BusinessException.conflict("充值政策版本不存在");
        }
    }
}
