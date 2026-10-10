-- A26-P2-03：核销短码必须由数据库保证全局唯一，关闭并发“先查后写”竞态。
-- 历史重复哈希无法判断应归属哪一条预约，因此全部失效并清空；用户可通过本人鉴权接口领取新码。
CREATE TEMPORARY TABLE `v12_duplicate_checkin_hash` (
  `checkin_code_hash` varchar(128) NOT NULL,
  PRIMARY KEY (`checkin_code_hash`)
) ENGINE=MEMORY DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `v12_duplicate_checkin_hash` (`checkin_code_hash`)
SELECT `checkin_code_hash`
FROM `reservation_lifecycle`
WHERE `checkin_code_hash` IS NOT NULL
GROUP BY `checkin_code_hash`
HAVING COUNT(*) > 1;

INSERT IGNORE INTO `reservation_lifecycle_event` (
  `reservation_id`, `lifecycle_id`, `event_type`, `from_status`, `to_status`,
  `source_type`, `reason_code`, `reason_text`, `related_entity_type`, `related_entity_id`,
  `idempotency_key`, `create_time`
)
SELECT
  lifecycle.`reservation_id`, lifecycle.`id`, 'CHECKIN_CREDENTIAL_INVALIDATED',
  lifecycle.`credential_status`, 'INVALIDATED', 'SYSTEM', 'DUPLICATE_CREDENTIAL_HASH',
  '迁移检测到重复核销凭证，已失效；用户需重新领取', 'RESERVATION', lifecycle.`reservation_id`,
  CONCAT('V12_DUPLICATE_CREDENTIAL:', lifecycle.`id`), CURRENT_TIMESTAMP
FROM `reservation_lifecycle` lifecycle
JOIN `v12_duplicate_checkin_hash` duplicate_hash
  ON duplicate_hash.`checkin_code_hash` = lifecycle.`checkin_code_hash`
WHERE lifecycle.`deleted` = 0;

UPDATE `reservation_lifecycle` lifecycle
JOIN `v12_duplicate_checkin_hash` duplicate_hash
  ON duplicate_hash.`checkin_code_hash` = lifecycle.`checkin_code_hash`
SET lifecycle.`checkin_code_hash` = NULL,
    lifecycle.`checkin_code_last4` = NULL,
    lifecycle.`credential_status` = IF(lifecycle.`deleted` = 0, 'INVALIDATED', lifecycle.`credential_status`),
    lifecycle.`update_time` = CURRENT_TIMESTAMP;

DROP TEMPORARY TABLE `v12_duplicate_checkin_hash`;

ALTER TABLE `reservation_lifecycle`
  DROP INDEX `idx_reservation_lifecycle_code_hash`,
  ADD UNIQUE KEY `uk_reservation_lifecycle_code_hash` (`checkin_code_hash`);
