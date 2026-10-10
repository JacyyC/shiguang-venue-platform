-- R4 keeps member_account_log.points_delta as the only points ledger and adds
-- stable business keys plus balance snapshots for all new writes.
ALTER TABLE `member_account_log`
  ADD COLUMN `business_key` varchar(128) DEFAULT NULL,
  ADD COLUMN `rule_version` varchar(64) DEFAULT NULL,
  ADD COLUMN `points_balance_before` int DEFAULT NULL,
  ADD COLUMN `points_balance_after` int DEFAULT NULL,
  ADD UNIQUE KEY `uk_member_account_log_business_key` (`business_key`);

-- Existing V16 databases can have a projection created before the ledger became
-- authoritative. Append exactly one non-zero bridge fact and never rewrite either side.
INSERT INTO `member_account_log` (
  `account_id`, `user_id`, `event_type`, `before_level_code`, `after_level_code`,
  `growth_delta`, `points_delta`, `consumption_delta`, `source_type`, `reason_code`,
  `reason_text`, `metadata_json`, `business_key`, `rule_version`,
  `points_balance_before`, `points_balance_after`, `create_time`
)
SELECT
  `member_account`.`id`,
  `member_account`.`user_id`,
  'MEMBER_POINTS_OPENING_BRIDGE',
  `member_account`.`level_code`,
  `member_account`.`level_code`,
  0,
  `member_account`.`points_balance` - COALESCE(ledger.`points_sum`, 0),
  0.00,
  'MIGRATION',
  'OPENING_BALANCE_BRIDGE',
  'R4 V17 期初积分投影桥接',
  JSON_OBJECT('migration', 'V17', 'accountId', `member_account`.`id`),
  CONCAT('OPENING_BRIDGE:R4_V17:ACCOUNT:', `member_account`.`id`),
  'R4_OPENING_V1',
  COALESCE(ledger.`points_sum`, 0),
  `member_account`.`points_balance`,
  CURRENT_TIMESTAMP
FROM `member_account`
LEFT JOIN (
  SELECT `account_id`, SUM(`points_delta`) AS `points_sum`
  FROM `member_account_log`
  GROUP BY `account_id`
) ledger ON ledger.`account_id` = `member_account`.`id`
WHERE `member_account`.`deleted` = 0
  AND `member_account`.`points_balance` <> COALESCE(ledger.`points_sum`, 0)
  AND NOT EXISTS (
    SELECT 1
    FROM `member_account_log` existing_bridge
    WHERE existing_bridge.`business_key`
      = CONCAT('OPENING_BRIDGE:R4_V17:ACCOUNT:', `member_account`.`id`)
  );

CREATE TABLE `member_reward_catalog_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `item_code` varchar(64) NOT NULL,
  `item_name` varchar(128) NOT NULL,
  `description_text` varchar(500) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'DRAFT',
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_reward_catalog_item_code` (`item_code`),
  KEY `idx_member_reward_catalog_item_status` (`status`,`deleted`,`id`),
  CONSTRAINT `chk_member_reward_catalog_item_status`
    CHECK (`status` IN ('DRAFT','ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `member_reward_catalog_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `catalog_item_id` bigint NOT NULL,
  `version_no` int NOT NULL,
  `points_cost` int NOT NULL,
  `minimum_level_code` varchar(32) NOT NULL,
  `minimum_level_name` varchar(64) NOT NULL,
  `minimum_growth_threshold` int NOT NULL,
  `coupon_template_id` bigint NOT NULL,
  `validity_days` int DEFAULT NULL,
  `effective_start_time` datetime DEFAULT NULL,
  `effective_end_time` datetime DEFAULT NULL,
  `config_summary_json` json NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'DRAFT',
  `published_by` bigint DEFAULT NULL,
  `published_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_reward_catalog_version` (`catalog_item_id`,`version_no`),
  KEY `idx_member_reward_catalog_version_status`
    (`catalog_item_id`,`status`,`effective_start_time`,`effective_end_time`,`id`),
  KEY `idx_member_reward_catalog_version_coupon` (`coupon_template_id`,`status`),
  CONSTRAINT `chk_member_reward_catalog_version_values`
    CHECK (`version_no` > 0 AND `points_cost` > 0
      AND `minimum_growth_threshold` >= 0
      AND (`validity_days` IS NULL OR `validity_days` > 0)),
  CONSTRAINT `chk_member_reward_catalog_version_status`
    CHECK (`status` IN ('DRAFT','PUBLISHED','RETIRED')),
  CONSTRAINT `chk_member_reward_catalog_version_window`
    CHECK (`effective_end_time` IS NULL OR `effective_start_time` IS NULL
      OR `effective_end_time` > `effective_start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `member_reward_redemption` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `redemption_no` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `account_id` bigint DEFAULT NULL,
  `catalog_item_id` bigint NOT NULL,
  `catalog_version_id` bigint NOT NULL,
  `item_code_snapshot` varchar(64) NOT NULL,
  `item_name_snapshot` varchar(128) NOT NULL,
  `catalog_version_no` int NOT NULL,
  `points_cost` int NOT NULL,
  `minimum_level_code_snapshot` varchar(32) NOT NULL,
  `minimum_level_name_snapshot` varchar(64) NOT NULL,
  `minimum_growth_threshold_snapshot` int NOT NULL,
  `coupon_template_id` bigint NOT NULL,
  `validity_days_snapshot` int DEFAULT NULL,
  `idempotency_key` varchar(128) NOT NULL,
  `request_hash` char(64) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `failure_code` varchar(64) DEFAULT NULL,
  `failure_message` varchar(255) DEFAULT NULL,
  `user_coupon_id` bigint DEFAULT NULL,
  `points_debit_log_id` bigint DEFAULT NULL,
  `points_compensation_log_id` bigint DEFAULT NULL,
  `source_type` varchar(32) NOT NULL DEFAULT 'USER',
  `reason_code` varchar(64) DEFAULT NULL,
  `reason_text` varchar(255) DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `compensated_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_reward_redemption_no` (`redemption_no`),
  UNIQUE KEY `uk_member_reward_redemption_command` (`user_id`,`idempotency_key`),
  UNIQUE KEY `uk_member_reward_redemption_coupon` (`user_coupon_id`),
  UNIQUE KEY `uk_member_reward_redemption_debit_log` (`points_debit_log_id`),
  UNIQUE KEY `uk_member_reward_redemption_compensation_log` (`points_compensation_log_id`),
  KEY `idx_member_reward_redemption_user` (`user_id`,`create_time`,`id`),
  KEY `idx_member_reward_redemption_status` (`status`,`update_time`,`id`),
  KEY `idx_member_reward_redemption_catalog` (`catalog_item_id`,`catalog_version_id`,`id`),
  CONSTRAINT `chk_member_reward_redemption_values`
    CHECK (`catalog_version_no` > 0 AND `points_cost` > 0
      AND `minimum_growth_threshold_snapshot` >= 0
      AND (`validity_days_snapshot` IS NULL OR `validity_days_snapshot` > 0)),
  CONSTRAINT `chk_member_reward_redemption_status`
    CHECK (`status` IN ('PENDING','SUCCEEDED','REJECTED','FAILED','COMPENSATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `member_reward_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_no` varchar(64) NOT NULL,
  `event_key` varchar(160) NOT NULL,
  `event_type` varchar(64) NOT NULL,
  `redemption_id` bigint DEFAULT NULL,
  `catalog_item_id` bigint DEFAULT NULL,
  `catalog_version_id` bigint DEFAULT NULL,
  `actor_user_id` bigint DEFAULT NULL,
  `source_type` varchar(32) NOT NULL,
  `reason_code` varchar(64) DEFAULT NULL,
  `reason_text` varchar(255) DEFAULT NULL,
  `from_status` varchar(32) DEFAULT NULL,
  `to_status` varchar(32) DEFAULT NULL,
  `result_status` varchar(32) NOT NULL,
  `metadata_json` json NOT NULL,
  `occurred_at` datetime NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_reward_event_no` (`event_no`),
  UNIQUE KEY `uk_member_reward_event_key` (`event_key`),
  KEY `idx_member_reward_event_redemption` (`redemption_id`,`occurred_at`,`id`),
  KEY `idx_member_reward_event_catalog` (`catalog_item_id`,`catalog_version_id`,`occurred_at`,`id`),
  KEY `idx_member_reward_event_type` (`event_type`,`occurred_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Self-service is attached to the existing membership center and granted only to
-- roles that already have that center. Administrative leaves remain ungranted.
INSERT INTO `sys_menu` (
  `menu_name`, `parent_id`, `path`, `component`, `icon`, `perms`, `menu_type`,
  `sort_order`, `visible`, `status`, `create_time`, `update_time`, `deleted`
)
SELECT
  '积分权益兑换', member_root.`id`, NULL, NULL, NULL,
  'member-reward:me', 'BUTTON', 1, 'N', 'ACTIVE',
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM (
  SELECT MIN(`id`) AS `id`
  FROM `sys_menu`
  WHERE `perms` = 'member:me'
    AND `deleted` = 0
) member_root
WHERE member_root.`id` IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `sys_menu` existing_menu
    WHERE existing_menu.`parent_id` = member_root.`id`
      AND existing_menu.`perms` = 'member-reward:me'
      AND existing_menu.`menu_type` = 'BUTTON'
      AND existing_menu.`deleted` = 0
  );

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT parent_relation.`role_id`, reward_permission.`id`
FROM `sys_role_menu` parent_relation
JOIN `sys_menu` member_root
  ON member_root.`id` = parent_relation.`menu_id`
 AND member_root.`perms` = 'member:me'
 AND member_root.`deleted` = 0
JOIN `sys_menu` reward_permission
  ON reward_permission.`parent_id` = member_root.`id`
 AND reward_permission.`perms` = 'member-reward:me'
 AND reward_permission.`deleted` = 0
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_role_menu` existing_relation
  WHERE existing_relation.`role_id` = parent_relation.`role_id`
    AND existing_relation.`menu_id` = reward_permission.`id`
);

INSERT INTO `sys_menu` (
  `menu_name`, `parent_id`, `path`, `component`, `icon`, `perms`, `menu_type`,
  `sort_order`, `visible`, `status`, `create_time`, `update_time`, `deleted`
)
SELECT
  permission_row.`menu_name`, member_admin.`id`, NULL, NULL, NULL,
  permission_row.`perms`, 'BUTTON', permission_row.`sort_order`, 'N', 'ACTIVE',
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM (
  SELECT MIN(`id`) AS `id`
  FROM `sys_menu`
  WHERE `perms` = 'member:manage'
    AND `deleted` = 0
) member_admin
JOIN (
  SELECT '积分权益目录管理' AS `menu_name`, 'member-reward:catalog:manage' AS `perms`, 10 AS `sort_order`
  UNION ALL SELECT '积分兑换异常处置', 'member-reward:exception:manage', 11
  UNION ALL SELECT '积分兑换审计查看', 'member-reward:audit:view', 12
) permission_row
WHERE member_admin.`id` IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `sys_menu` existing_menu
    WHERE existing_menu.`parent_id` = member_admin.`id`
      AND existing_menu.`perms` = permission_row.`perms`
      AND existing_menu.`menu_type` = 'BUTTON'
      AND existing_menu.`deleted` = 0
  );
