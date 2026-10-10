package com.example.venue.storedvalue.service;

import com.example.venue.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Login, membership, role and phone presence are intentionally not eligibility facts. */
@Service
public class StoredValueEligibilityService {
    private final JdbcTemplate jdbc;

    public StoredValueEligibilityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireEligible(Long userId, StoredValueProductSnapshot product) {
        if (userId == null || product == null) {
            throw BusinessException.forbidden("储值资格缺失");
        }
        Integer active = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE id = ? AND status = 'ACTIVE' AND deleted = 0", Integer.class, userId);
        if (active == null || active != 1) {
            throw BusinessException.forbidden("用户身份状态不正常");
        }
        Integer verified = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM stored_value_identity_verification
            WHERE user_id = ?
              AND verification_status = 'VERIFIED'
              AND environment = ?
              AND verified_at <= ?
              AND (expires_at IS NULL OR expires_at > ?)
            """, Integer.class, userId, product.environment(), LocalDateTime.now(), LocalDateTime.now());
        if (verified == null || verified == 0) {
            throw BusinessException.forbidden("未完成储值身份核验");
        }
    }

    public void recordDevVerification(Long userId, String environment, String evidenceDigest,
                                      LocalDateTime verifiedAt, LocalDateTime expiresAt, Long operatorId) {
        if (userId == null || (!"DEV".equals(environment) && !"SANDBOX".equals(environment))
            || evidenceDigest == null || evidenceDigest.length() != 64) {
            throw BusinessException.badRequest("储值身份核验事实无效");
        }
        jdbc.update("""
            INSERT INTO stored_value_identity_verification
              (user_id, verification_status, source, environment, evidence_digest,
               verified_at, expires_at, operator_id)
            VALUES (?, 'VERIFIED', 'DEV_MANUAL', ?, ?, ?, ?, ?)
            """, userId, environment, evidenceDigest, verifiedAt == null ? LocalDateTime.now() : verifiedAt,
            expiresAt, operatorId);
    }
}
