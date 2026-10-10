-- Store high-risk stored-value approvals as immutable facts with an explicit
-- lifecycle event stream. Permission registration is intentionally separate
-- from role assignment so deployment cannot silently expand access.
CREATE TABLE `stored_value_approval` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `approval_no` varchar(96) NOT NULL,
  `active_key` varchar(256) DEFAULT NULL,
  `action_type` varchar(64) NOT NULL,
  `target_type` varchar(64) NOT NULL,
  `target_id` varchar(128) NOT NULL,
  `request_hash` char(64) NOT NULL,
  `target_snapshot` varchar(2048) NOT NULL,
  `amount` decimal(18,2) DEFAULT NULL,
  `currency` char(3) DEFAULT NULL,
  `reason` varchar(512) NOT NULL,
  `requester_id` bigint NOT NULL,
  `review_permission` varchar(128) NOT NULL,
  `execute_permission` varchar(128) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `expires_at` datetime NOT NULL,
  `policy_version` varchar(96) NOT NULL,
  `version` bigint NOT NULL DEFAULT 0,
  `reviewer_id` bigint DEFAULT NULL,
  `reviewed_at` datetime DEFAULT NULL,
  `review_reason` varchar(512) DEFAULT NULL,
  `executor_id` bigint DEFAULT NULL,
  `executed_at` datetime DEFAULT NULL,
  `execution_result` varchar(1024) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_approval_no` (`approval_no`),
  UNIQUE KEY `uk_stored_value_approval_active` (`active_key`),
  KEY `idx_stored_value_approval_status_expiry` (`status`,`expires_at`,`id`),
  KEY `idx_stored_value_approval_target` (`action_type`,`target_type`,`target_id`,`id`),
  CONSTRAINT `chk_stored_value_approval_amount` CHECK (`amount` IS NULL OR (`amount` <> 0 AND `amount` = ROUND(`amount`,2))),
  CONSTRAINT `chk_stored_value_approval_currency` CHECK (`currency` IS NULL OR `currency` = 'CNY'),
  CONSTRAINT `chk_stored_value_approval_status` CHECK (`status` IN ('PENDING','APPROVED','REJECTED','EXECUTING','CONSUMED','EXPIRED','REVOKED','FAILED')),
  CONSTRAINT `chk_stored_value_approval_reviewer` CHECK (`reviewer_id` IS NULL OR `reviewer_id` <> `requester_id`),
  CONSTRAINT `chk_stored_value_approval_executor` CHECK (`executor_id` IS NULL OR `reviewer_id` IS NULL OR `executor_id` <> `reviewer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_approval_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `approval_id` bigint NOT NULL,
  `event_no` int NOT NULL,
  `event_type` varchar(32) NOT NULL,
  `actor_id` bigint DEFAULT NULL,
  `actor_permission` varchar(128) DEFAULT NULL,
  `from_status` varchar(32) DEFAULT NULL,
  `to_status` varchar(32) NOT NULL,
  `payload_hash` char(64) NOT NULL,
  `reason` varchar(512) DEFAULT NULL,
  `result` varchar(1024) DEFAULT NULL,
  `event_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_approval_event_no` (`approval_id`,`event_no`),
  KEY `idx_stored_value_approval_event_time` (`approval_id`,`event_time`,`id`),
  CONSTRAINT `fk_stored_value_approval_event_approval`
    FOREIGN KEY (`approval_id`) REFERENCES `stored_value_approval` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TRIGGER `trg_stored_value_approval_event_no_update`
BEFORE UPDATE ON `stored_value_approval_event`
FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stored value approval events are immutable';

CREATE TRIGGER `trg_stored_value_approval_event_no_delete`
BEFORE DELETE ON `stored_value_approval_event`
FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stored value approval events are immutable';

CREATE TRIGGER `trg_stored_value_approval_snapshot_no_update`
BEFORE UPDATE ON `stored_value_approval`
FOR EACH ROW
BEGIN
  IF OLD.approval_no <> NEW.approval_no
     OR OLD.action_type <> NEW.action_type
     OR OLD.target_type <> NEW.target_type
     OR OLD.target_id <> NEW.target_id
     OR OLD.request_hash <> NEW.request_hash
     OR OLD.target_snapshot <> NEW.target_snapshot
     OR NOT (OLD.amount <=> NEW.amount)
     OR NOT (OLD.currency <=> NEW.currency)
     OR OLD.reason <> NEW.reason
     OR OLD.requester_id <> NEW.requester_id
     OR OLD.review_permission <> NEW.review_permission
     OR OLD.execute_permission <> NEW.execute_permission
     OR NEW.expires_at > OLD.expires_at
     OR OLD.policy_version <> NEW.policy_version THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stored value approval request facts are immutable';
  END IF;
END;

-- Register only the missing leaf permissions. No role is granted access here.
INSERT INTO `sys_menu` (`menu_name`,`parent_id`,`path`,`component`,`icon`,`perms`,`menu_type`,`sort_order`,`visible`,`status`,`create_time`,`update_time`,`deleted`)
SELECT permission_row.menu_name, root_menu.id, NULL, NULL, NULL, permission_row.perms, 'BUTTON', permission_row.sort_order, 'N', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM (SELECT MIN(id) AS id FROM sys_menu WHERE parent_id = 0 AND path = '/system-admin' AND perms = 'system:view' AND menu_type = 'MENU' AND deleted = 0) root_menu
JOIN (
  SELECT '储值充值执行' AS menu_name, 'stored-value:recharge:execute' AS perms, 81 AS sort_order
  UNION ALL SELECT '储值退款复核', 'stored-value:refund:review', 82
  UNION ALL SELECT '储值账户解冻复核', 'stored-value:account:unfreeze:review', 83
) permission_row
WHERE root_menu.id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing_menu WHERE existing_menu.parent_id = root_menu.id AND existing_menu.perms = permission_row.perms AND existing_menu.deleted = 0);
