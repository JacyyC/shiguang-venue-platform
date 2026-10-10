-- V2-R7-ADVERTISING/S03：商业广告偏好、决策 guard、决策快照与曝光/点击事实。
-- MySQL 是频控、令牌绑定和事件幂等的最终权威；Redis 不在本迁移中建立事实。

CREATE TABLE `advertising_privacy_preference` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `commercial_enabled` tinyint NOT NULL DEFAULT 0,
  `measurement_enabled` tinyint NOT NULL DEFAULT 0,
  `policy_version` varchar(64) NOT NULL DEFAULT 'G-R7-20260803-v1',
  `changed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_advertising_privacy_user` (`user_id`),
  KEY `idx_advertising_privacy_changed` (`changed_at`,`user_id`),
  CONSTRAINT `chk_advertising_privacy_commercial`
    CHECK (`commercial_enabled` IN (0,1)),
  CONSTRAINT `chk_advertising_privacy_measurement`
    CHECK (`measurement_enabled` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `advertising_decision_guard` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `slot_code` varchar(32) NOT NULL,
  `scope_key` varchar(96) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_advertising_guard_scope` (`user_id`,`slot_code`,`scope_key`),
  CONSTRAINT `chk_advertising_guard_slot`
    CHECK (`slot_code` = 'HOME_COMMERCIAL'),
  CONSTRAINT `chk_advertising_guard_scope`
    CHECK (`scope_key` <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `advertising_decision` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `decision_no` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `slot_code` varchar(32) NOT NULL DEFAULT 'HOME_COMMERCIAL',
  `advertiser_id` bigint NOT NULL,
  `campaign_id` bigint NOT NULL,
  `creative_version_id` bigint NOT NULL,
  `token_hash` char(64) NOT NULL,
  `policy_version` varchar(64) NOT NULL,
  `rule_version_snapshot` varchar(64) NOT NULL,
  `privacy_snapshot_json` varchar(1024) NOT NULL,
  `frequency_snapshot_json` varchar(1024) NOT NULL,
  `creative_snapshot_json` varchar(4096) NOT NULL,
  `arbitration_snapshot_json` varchar(1024) NOT NULL,
  `expires_at` datetime NOT NULL,
  `consumed_at` datetime DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ISSUED',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_advertising_decision_no` (`decision_no`),
  UNIQUE KEY `uk_advertising_decision_token` (`token_hash`),
  KEY `idx_advertising_decision_user_slot_creative` (`user_id`,`slot_code`,`creative_version_id`,`status`,`expires_at`,`id`),
  KEY `idx_advertising_decision_campaign_time` (`campaign_id`,`creative_version_id`,`create_time`,`id`),
  CONSTRAINT `chk_advertising_decision_slot`
    CHECK (`slot_code` = 'HOME_COMMERCIAL'),
  CONSTRAINT `chk_advertising_decision_status`
    CHECK (`status` IN ('ISSUED','CONSUMED','EXPIRED')),
  CONSTRAINT `chk_advertising_decision_token`
    CHECK (char_length(`token_hash`) = 64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `advertising_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_no` varchar(64) NOT NULL,
  `event_key` varchar(192) NOT NULL,
  `event_type` varchar(32) NOT NULL,
  `decision_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `slot_code` varchar(32) NOT NULL DEFAULT 'HOME_COMMERCIAL',
  `advertiser_id` bigint NOT NULL,
  `campaign_id` bigint NOT NULL,
  `creative_version_id` bigint NOT NULL,
  `request_hash` char(64) NOT NULL,
  `visible_ratio` decimal(5,4) DEFAULT NULL,
  `visible_duration_ms` int DEFAULT NULL,
  `result_status` varchar(32) NOT NULL DEFAULT 'ACCEPTED',
  `rejection_code` varchar(64) DEFAULT NULL,
  `policy_version` varchar(64) NOT NULL,
  `occurred_at` datetime NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_advertising_event_no` (`event_no`),
  UNIQUE KEY `uk_advertising_event_key` (`event_key`),
  UNIQUE KEY `uk_advertising_event_decision_type` (`decision_id`,`event_type`),
  KEY `idx_advertising_event_user_slot_time` (`user_id`,`slot_code`,`event_type`,`result_status`,`occurred_at`,`id`),
  KEY `idx_advertising_event_user_creative_time` (`user_id`,`creative_version_id`,`event_type`,`result_status`,`occurred_at`,`id`),
  KEY `idx_advertising_event_decision_time` (`decision_id`,`occurred_at`,`id`),
  CONSTRAINT `chk_advertising_event_slot`
    CHECK (`slot_code` = 'HOME_COMMERCIAL'),
  CONSTRAINT `chk_advertising_event_type`
    CHECK (`event_type` IN ('CLIENT_VIEWABLE','CLICK')),
  CONSTRAINT `chk_advertising_event_result`
    CHECK (`result_status` = 'ACCEPTED'),
  CONSTRAINT `chk_advertising_event_visibility`
    CHECK (`event_type` = 'CLICK'
      OR (`visible_ratio` IS NOT NULL AND `visible_duration_ms` IS NOT NULL
        AND `visible_ratio` >= 0.5000 AND `visible_ratio` <= 1.0000
        AND `visible_duration_ms` >= 1000))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
