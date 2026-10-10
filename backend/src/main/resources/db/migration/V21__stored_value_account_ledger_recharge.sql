-- V2-R5/S05：DEV/SANDBOX 闭环储值资金事实。
-- 本迁移只建立空的配置、账户、充值、账本和处置结构；不创建用户余额、充值、赠送或演示业务数据。
-- 充值不复用 order/reservation/venue/payment_attempt，余额不是资金最终事实。

CREATE TABLE `stored_value_issuer` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `issuer_code` varchar(64) NOT NULL,
  `legal_name_snapshot` varchar(128) NOT NULL,
  `liability_holder_snapshot` varchar(255) NOT NULL,
  `environment` varchar(16) NOT NULL,
  `simulation_notice` varchar(512) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT',
  `created_by` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_issuer_code` (`issuer_code`),
  CONSTRAINT `chk_stored_value_issuer_environment` CHECK (`environment` IN ('DEV','SANDBOX')),
  CONSTRAINT `chk_stored_value_issuer_status` CHECK (`status` IN ('DRAFT','ENABLED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `issuer_id` bigint NOT NULL,
  `product_code` varchar(64) NOT NULL,
  `product_name` varchar(128) NOT NULL,
  `currency` char(3) NOT NULL,
  `environment` varchar(16) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT',
  `recharge_enabled` tinyint NOT NULL DEFAULT '0',
  `stored_value_spend_enabled` tinyint NOT NULL DEFAULT '0',
  `current_policy_id` bigint DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_product_code` (`issuer_id`,`product_code`),
  KEY `idx_stored_value_product_status` (`status`,`environment`),
  CONSTRAINT `fk_stored_value_product_issuer` FOREIGN KEY (`issuer_id`) REFERENCES `stored_value_issuer` (`id`),
  CONSTRAINT `chk_stored_value_product_currency` CHECK (`currency` = 'CNY'),
  CONSTRAINT `chk_stored_value_product_environment` CHECK (`environment` IN ('DEV','SANDBOX')),
  CONSTRAINT `chk_stored_value_product_status` CHECK (`status` IN ('DRAFT','ENABLED','DISABLED')),
  CONSTRAINT `chk_stored_value_product_recharge_flag` CHECK (`recharge_enabled` IN (0,1)),
  CONSTRAINT `chk_stored_value_product_spend_flag` CHECK (`stored_value_spend_enabled` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_policy` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `policy_version` varchar(96) NOT NULL,
  `currency` char(3) NOT NULL,
  `effective_from` datetime NOT NULL,
  `effective_until` datetime DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT',
  `min_recharge_amount` decimal(18,2) NOT NULL,
  `max_recharge_amount` decimal(18,2) NOT NULL,
  `daily_recharge_limit` decimal(18,2) NOT NULL,
  `principal_balance_limit` decimal(18,2) NOT NULL,
  `total_balance_limit` decimal(18,2) NOT NULL,
  `max_pending_recharges` int NOT NULL,
  `max_daily_initiations` int NOT NULL,
  `bonus_rate_percent` decimal(8,4) NOT NULL,
  `bonus_per_recharge_limit` decimal(18,2) NOT NULL,
  `monthly_bonus_limit` decimal(18,2) NOT NULL,
  `bonus_valid_days` int NOT NULL,
  `refund_compensation_valid_days` int NOT NULL,
  `timezone_id` varchar(64) NOT NULL,
  `policy_digest` char(64) NOT NULL,
  `created_by` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_policy_version` (`product_id`,`policy_version`),
  KEY `idx_stored_value_policy_effective` (`product_id`,`status`,`effective_from`,`effective_until`),
  CONSTRAINT `fk_stored_value_policy_product` FOREIGN KEY (`product_id`) REFERENCES `stored_value_product` (`id`),
  CONSTRAINT `chk_stored_value_policy_currency` CHECK (`currency` = 'CNY'),
  CONSTRAINT `chk_stored_value_policy_status` CHECK (`status` IN ('DRAFT','ACTIVE','RETIRED')),
  CONSTRAINT `chk_stored_value_policy_window` CHECK (`effective_until` IS NULL OR `effective_until` > `effective_from`),
  CONSTRAINT `chk_stored_value_policy_amounts` CHECK (`min_recharge_amount` >= 0 AND `max_recharge_amount` >= `min_recharge_amount` AND `daily_recharge_limit` >= 0 AND `principal_balance_limit` >= 0 AND `total_balance_limit` >= 0),
  CONSTRAINT `chk_stored_value_policy_counts` CHECK (`max_pending_recharges` > 0 AND `max_daily_initiations` > 0 AND `bonus_valid_days` > 0 AND `refund_compensation_valid_days` > 0),
  CONSTRAINT `chk_stored_value_policy_bonus` CHECK (`bonus_rate_percent` >= 0 AND `bonus_per_recharge_limit` >= 0 AND `monthly_bonus_limit` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `stored_value_product`
  ADD CONSTRAINT `fk_stored_value_product_policy`
  FOREIGN KEY (`current_policy_id`) REFERENCES `stored_value_policy` (`id`);

CREATE TABLE `stored_value_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `issuer_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `currency` char(3) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'NORMAL',
  `principal_available` decimal(18,2) NOT NULL DEFAULT '0.00',
  `principal_frozen` decimal(18,2) NOT NULL DEFAULT '0.00',
  `version` bigint NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_account_scope` (`user_id`,`issuer_id`,`product_id`,`currency`),
  UNIQUE KEY `uk_stored_value_account_id_scope` (`id`,`issuer_id`,`product_id`,`currency`),
  KEY `idx_stored_value_account_user` (`user_id`,`status`,`id`),
  CONSTRAINT `fk_stored_value_account_issuer` FOREIGN KEY (`issuer_id`) REFERENCES `stored_value_issuer` (`id`),
  CONSTRAINT `fk_stored_value_account_product` FOREIGN KEY (`product_id`) REFERENCES `stored_value_product` (`id`),
  CONSTRAINT `chk_stored_value_account_currency` CHECK (`currency` = 'CNY'),
  CONSTRAINT `chk_stored_value_account_status` CHECK (`status` IN ('NORMAL','FROZEN','CLOSING','CLOSED')),
  CONSTRAINT `chk_stored_value_account_principal` CHECK (`principal_available` >= 0 AND `principal_frozen` >= 0 AND `principal_available` = ROUND(`principal_available`,2) AND `principal_frozen` = ROUND(`principal_frozen`,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_account_lifecycle_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_id` bigint NOT NULL,
  `from_status` varchar(16) DEFAULT NULL,
  `to_status` varchar(16) NOT NULL,
  `reason` varchar(512) NOT NULL,
  `business_ref` varchar(128) NOT NULL,
  `source_type` varchar(32) NOT NULL,
  `operator_id` bigint DEFAULT NULL,
  `reviewer_id` bigint DEFAULT NULL,
  `policy_version` varchar(96) DEFAULT NULL,
  `state_version` bigint NOT NULL,
  `event_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_lifecycle_business_ref` (`account_id`,`business_ref`),
  KEY `idx_stored_value_lifecycle_account_time` (`account_id`,`event_time`,`id`),
  CONSTRAINT `fk_stored_value_lifecycle_account` FOREIGN KEY (`account_id`) REFERENCES `stored_value_account` (`id`),
  CONSTRAINT `chk_stored_value_lifecycle_reviewer` CHECK (`reviewer_id` IS NULL OR `operator_id` IS NULL OR `reviewer_id` <> `operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_ledger_transaction` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `issuer_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `account_id` bigint DEFAULT NULL,
  `currency` char(3) NOT NULL,
  `transaction_type` varchar(48) NOT NULL,
  `source_type` varchar(48) NOT NULL,
  `source_id` varchar(128) NOT NULL,
  `business_key` varchar(192) NOT NULL,
  `payload_hash` char(64) NOT NULL,
  `policy_version` varchar(96) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'OPEN',
  `reason` varchar(512) NOT NULL,
  `operator_id` bigint DEFAULT NULL,
  `system_source` varchar(64) DEFAULT NULL,
  `occurred_at` datetime NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_ledger_transaction_id_scope` (`id`,`issuer_id`,`product_id`,`currency`),
  UNIQUE KEY `uk_stored_value_ledger_business_key` (`issuer_id`,`product_id`,`currency`,`business_key`),
  KEY `idx_stored_value_ledger_account_time` (`account_id`,`occurred_at`,`id`),
  KEY `idx_stored_value_ledger_source` (`source_type`,`source_id`,`id`),
  CONSTRAINT `fk_stored_value_ledger_issuer` FOREIGN KEY (`issuer_id`) REFERENCES `stored_value_issuer` (`id`),
  CONSTRAINT `fk_stored_value_ledger_product` FOREIGN KEY (`product_id`) REFERENCES `stored_value_product` (`id`),
  CONSTRAINT `fk_stored_value_ledger_account` FOREIGN KEY (`account_id`) REFERENCES `stored_value_account` (`id`),
  CONSTRAINT `fk_stored_value_ledger_account_scope` FOREIGN KEY (`account_id`,`issuer_id`,`product_id`,`currency`)
    REFERENCES `stored_value_account` (`id`,`issuer_id`,`product_id`,`currency`),
  CONSTRAINT `chk_stored_value_ledger_currency` CHECK (`currency` = 'CNY'),
  CONSTRAINT `chk_stored_value_ledger_status` CHECK (`status` IN ('OPEN','POSTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_ledger_entry` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `transaction_id` bigint NOT NULL,
  `line_no` int NOT NULL,
  `account_id` bigint DEFAULT NULL,
  `bonus_batch_id` bigint DEFAULT NULL,
  `bucket` varchar(48) NOT NULL,
  `direction` varchar(8) NOT NULL,
  `amount` decimal(18,2) NOT NULL,
  `currency` char(3) NOT NULL,
  `issuer_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_ledger_entry_line` (`transaction_id`,`line_no`),
  KEY `idx_stored_value_ledger_entry_account_bucket` (`account_id`,`bucket`,`direction`,`id`),
  KEY `idx_stored_value_ledger_entry_bonus` (`bonus_batch_id`,`bucket`,`direction`,`id`),
  CONSTRAINT `fk_stored_value_ledger_entry_account` FOREIGN KEY (`account_id`) REFERENCES `stored_value_account` (`id`),
  CONSTRAINT `fk_stored_value_ledger_entry_issuer` FOREIGN KEY (`issuer_id`) REFERENCES `stored_value_issuer` (`id`),
  CONSTRAINT `fk_stored_value_ledger_entry_product` FOREIGN KEY (`product_id`) REFERENCES `stored_value_product` (`id`),
  CONSTRAINT `chk_stored_value_ledger_entry_line` CHECK (`line_no` > 0),
  CONSTRAINT `chk_stored_value_ledger_entry_direction` CHECK (`direction` IN ('DEBIT','CREDIT')),
  CONSTRAINT `chk_stored_value_ledger_entry_amount` CHECK (`amount` > 0 AND `amount` = ROUND(`amount`,2)),
  CONSTRAINT `chk_stored_value_ledger_entry_currency` CHECK (`currency` = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_bonus_batch` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_id` bigint NOT NULL,
  `recharge_id` bigint NOT NULL,
  `issuer_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `source_transaction_id` bigint DEFAULT NULL,
  `compensation_ref` varchar(128) NOT NULL DEFAULT '',
  `policy_version` varchar(96) NOT NULL,
  `currency` char(3) NOT NULL,
  `original_amount` decimal(18,2) NOT NULL,
  `available_amount` decimal(18,2) NOT NULL,
  `frozen_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `issued_at` datetime NOT NULL,
  `expires_at` datetime NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'AVAILABLE',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_bonus_source` (`account_id`,`recharge_id`,`policy_version`,`compensation_ref`),
  UNIQUE KEY `uk_stored_value_bonus_id_scope` (`id`,`issuer_id`,`product_id`,`currency`),
  KEY `idx_stored_value_bonus_fefo` (`account_id`,`status`,`expires_at`,`issued_at`,`id`),
  CONSTRAINT `fk_stored_value_bonus_account` FOREIGN KEY (`account_id`) REFERENCES `stored_value_account` (`id`),
  CONSTRAINT `fk_stored_value_bonus_account_scope` FOREIGN KEY (`account_id`,`issuer_id`,`product_id`,`currency`)
    REFERENCES `stored_value_account` (`id`,`issuer_id`,`product_id`,`currency`),
  CONSTRAINT `fk_stored_value_bonus_issuer` FOREIGN KEY (`issuer_id`) REFERENCES `stored_value_issuer` (`id`),
  CONSTRAINT `fk_stored_value_bonus_product` FOREIGN KEY (`product_id`) REFERENCES `stored_value_product` (`id`),
  CONSTRAINT `chk_stored_value_bonus_amount` CHECK (`original_amount` >= 0 AND `available_amount` >= 0 AND `frozen_amount` >= 0 AND `available_amount` + `frozen_amount` <= `original_amount` AND `original_amount` = ROUND(`original_amount`,2) AND `available_amount` = ROUND(`available_amount`,2) AND `frozen_amount` = ROUND(`frozen_amount`,2)),
  CONSTRAINT `chk_stored_value_bonus_status` CHECK (`status` IN ('AVAILABLE','EXPIRED','EXHAUSTED','FROZEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- CNY is enforced by CHECK constraints on each money-bearing table; no mutable currency catalog exists.

CREATE TABLE `stored_value_limit_day_guard` (
  `account_id` bigint NOT NULL,
  `guard_date` date NOT NULL,
  `successful_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `reserved_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `initiation_count` int NOT NULL DEFAULT '0',
  `pending_count` int NOT NULL DEFAULT '0',
  `risk_daily_limit` decimal(18,2) DEFAULT NULL,
  `risk_max_pending` int DEFAULT NULL,
  `risk_max_initiations` int DEFAULT NULL,
  `policy_version` varchar(96) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`account_id`,`guard_date`),
  CONSTRAINT `fk_stored_value_limit_day_account` FOREIGN KEY (`account_id`) REFERENCES `stored_value_account` (`id`),
  CONSTRAINT `chk_stored_value_limit_day_amount` CHECK (`successful_amount` >= 0 AND `reserved_amount` >= 0 AND `successful_amount` = ROUND(`successful_amount`,2) AND `reserved_amount` = ROUND(`reserved_amount`,2)),
  CONSTRAINT `chk_stored_value_limit_day_counts` CHECK (`initiation_count` >= 0 AND `pending_count` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_limit_month_guard` (
  `account_id` bigint NOT NULL,
  `guard_month` char(7) NOT NULL,
  `issued_bonus_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `reserved_bonus_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `policy_version` varchar(96) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`account_id`,`guard_month`),
  CONSTRAINT `fk_stored_value_limit_month_account` FOREIGN KEY (`account_id`) REFERENCES `stored_value_account` (`id`),
  CONSTRAINT `chk_stored_value_limit_month_amount` CHECK (`issued_bonus_amount` >= 0 AND `reserved_bonus_amount` >= 0 AND `issued_bonus_amount` = ROUND(`issued_bonus_amount`,2) AND `reserved_bonus_amount` = ROUND(`reserved_bonus_amount`,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_identity_verification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `verification_status` varchar(16) NOT NULL,
  `source` varchar(48) NOT NULL,
  `environment` varchar(16) NOT NULL,
  `evidence_digest` char(64) NOT NULL,
  `verified_at` datetime NOT NULL,
  `expires_at` datetime DEFAULT NULL,
  `operator_id` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_stored_value_identity_user_status` (`user_id`,`verification_status`,`expires_at`,`id`),
  CONSTRAINT `chk_stored_value_identity_status` CHECK (`verification_status` IN ('VERIFIED','REVOKED','EXPIRED')),
  CONSTRAINT `chk_stored_value_identity_environment` CHECK (`environment` IN ('DEV','SANDBOX'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_recharge` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `recharge_no` varchar(96) NOT NULL,
  `client_request_id` varchar(128) NOT NULL,
  `user_id` bigint NOT NULL,
  `account_id` bigint NOT NULL,
  `issuer_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `policy_id` bigint NOT NULL,
  `policy_version` varchar(96) NOT NULL,
  `currency` char(3) NOT NULL,
  `principal_amount` decimal(18,2) NOT NULL,
  `expected_bonus_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `actual_bonus_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `status` varchar(48) NOT NULL,
  `provider_key` varchar(128) DEFAULT NULL,
  `provider_env` varchar(16) DEFAULT NULL,
  `provider_name` varchar(64) DEFAULT NULL,
  `provider_config_version` varchar(96) DEFAULT NULL,
  `provider_business_no` varchar(128) DEFAULT NULL,
  `external_ref` varchar(128) DEFAULT NULL,
  `failure_code` varchar(64) DEFAULT NULL,
  `failure_message` varchar(512) DEFAULT NULL,
  `source_type` varchar(32) NOT NULL,
  `idempotency_hash` char(64) NOT NULL,
  `guard_date` date NOT NULL,
  `guard_month` char(7) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_recharge_no` (`recharge_no`),
  UNIQUE KEY `uk_stored_value_recharge_client_request` (`product_id`,`user_id`,`client_request_id`),
  UNIQUE KEY `uk_stored_value_recharge_id_scope` (`id`,`issuer_id`,`product_id`,`currency`),
  KEY `idx_stored_value_recharge_account_status` (`account_id`,`status`,`id`),
  KEY `idx_stored_value_recharge_provider_ref` (`provider_env`,`provider_name`,`external_ref`),
  CONSTRAINT `fk_stored_value_recharge_account` FOREIGN KEY (`account_id`) REFERENCES `stored_value_account` (`id`),
  CONSTRAINT `fk_stored_value_recharge_account_scope` FOREIGN KEY (`account_id`,`issuer_id`,`product_id`,`currency`)
    REFERENCES `stored_value_account` (`id`,`issuer_id`,`product_id`,`currency`),
  CONSTRAINT `fk_stored_value_recharge_issuer` FOREIGN KEY (`issuer_id`) REFERENCES `stored_value_issuer` (`id`),
  CONSTRAINT `fk_stored_value_recharge_product` FOREIGN KEY (`product_id`) REFERENCES `stored_value_product` (`id`),
  CONSTRAINT `fk_stored_value_recharge_policy` FOREIGN KEY (`policy_id`) REFERENCES `stored_value_policy` (`id`),
  CONSTRAINT `chk_stored_value_recharge_currency` CHECK (`currency` = 'CNY'),
  CONSTRAINT `chk_stored_value_recharge_amount` CHECK (`principal_amount` > 0 AND `principal_amount` = ROUND(`principal_amount`,2) AND `expected_bonus_amount` >= 0 AND `actual_bonus_amount` >= 0),
  CONSTRAINT `chk_stored_value_recharge_status` CHECK (`status` IN ('INITIATED','WAITING_PROVIDER','PROVIDER_SUCCEEDED_PENDING_POSTING','POSTING','SUCCEEDED','FAILED','CANCELED','TIMED_OUT','REFUNDING','REFUNDED','DISPUTED','MANUAL_REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_limit_reservation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `recharge_id` bigint NOT NULL,
  `account_id` bigint NOT NULL,
  `guard_date` date NOT NULL,
  `guard_month` char(7) NOT NULL,
  `principal_amount` decimal(18,2) NOT NULL,
  `bonus_amount` decimal(18,2) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'RESERVED',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `released_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_limit_reservation_recharge` (`recharge_id`),
  KEY `idx_stored_value_limit_reservation_guard` (`account_id`,`guard_date`,`status`,`id`),
  CONSTRAINT `fk_stored_value_limit_reservation_recharge` FOREIGN KEY (`recharge_id`) REFERENCES `stored_value_recharge` (`id`),
  CONSTRAINT `fk_stored_value_limit_reservation_account` FOREIGN KEY (`account_id`) REFERENCES `stored_value_account` (`id`),
  CONSTRAINT `chk_stored_value_limit_reservation_amount` CHECK (`principal_amount` > 0 AND `principal_amount` = ROUND(`principal_amount`,2) AND `bonus_amount` >= 0 AND `bonus_amount` = ROUND(`bonus_amount`,2)),
  CONSTRAINT `chk_stored_value_limit_reservation_status` CHECK (`status` IN ('RESERVED','CONSUMED','RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_recharge_attempt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `recharge_id` bigint NOT NULL,
  `attempt_no` int NOT NULL,
  `provider_key` varchar(128) NOT NULL,
  `provider_env` varchar(16) NOT NULL,
  `provider_name` varchar(64) NOT NULL,
  `config_version` varchar(96) NOT NULL,
  `provider_business_no` varchar(128) NOT NULL,
  `status` varchar(32) NOT NULL,
  `request_digest` char(64) DEFAULT NULL,
  `request_summary` varchar(1024) DEFAULT NULL,
  `response_digest` char(64) DEFAULT NULL,
  `response_summary` varchar(1024) DEFAULT NULL,
  `external_ref` varchar(128) DEFAULT NULL,
  `failure_code` varchar(64) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_attempt_no` (`recharge_id`,`attempt_no`),
  UNIQUE KEY `uk_stored_value_attempt_business_no` (`provider_env`,`provider_name`,`provider_business_no`),
  KEY `idx_stored_value_attempt_status` (`status`,`update_time`),
  CONSTRAINT `fk_stored_value_attempt_recharge` FOREIGN KEY (`recharge_id`) REFERENCES `stored_value_recharge` (`id`),
  CONSTRAINT `chk_stored_value_attempt_environment` CHECK (`provider_env` IN ('DEV','SANDBOX')),
  CONSTRAINT `chk_stored_value_attempt_status` CHECK (`status` IN ('CREATED','REQUESTED','SUCCEEDED','FAILED','UNKNOWN','MANUAL_REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_callback_evidence` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `recharge_id` bigint DEFAULT NULL,
  `provider_key` varchar(128) NOT NULL,
  `provider_env` varchar(16) NOT NULL,
  `provider_name` varchar(64) NOT NULL,
  `config_version` varchar(96) DEFAULT NULL,
  `external_event_id` varchar(128) DEFAULT NULL,
  `payload_digest` char(64) NOT NULL,
  `request_summary` varchar(1024) DEFAULT NULL,
  `received_at` datetime NOT NULL,
  `verification_status` varchar(32) NOT NULL DEFAULT 'RECEIVED',
  `processing_status` varchar(32) NOT NULL DEFAULT 'UNPROCESSED',
  `result_code` varchar(64) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_callback_event` (`provider_env`,`provider_name`,`external_event_id`,`payload_digest`),
  KEY `idx_stored_value_callback_recharge` (`recharge_id`,`received_at`,`id`),
  CONSTRAINT `fk_stored_value_callback_recharge` FOREIGN KEY (`recharge_id`) REFERENCES `stored_value_recharge` (`id`),
  CONSTRAINT `chk_stored_value_callback_environment` CHECK (`provider_env` IN ('DEV','SANDBOX','PROD')),
  CONSTRAINT `chk_stored_value_callback_verification` CHECK (`verification_status` IN ('RECEIVED','VERIFIED','REJECTED','MANUAL_REVIEW')),
  CONSTRAINT `chk_stored_value_callback_processing` CHECK (`processing_status` IN ('UNPROCESSED','POSTED','IGNORED','MANUAL_REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_recharge_refund` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `refund_no` varchar(96) NOT NULL,
  `recharge_id` bigint NOT NULL,
  `account_id` bigint NOT NULL,
  `provider_key` varchar(128) NOT NULL,
  `provider_env` varchar(16) NOT NULL,
  `provider_business_no` varchar(128) NOT NULL,
  `amount` decimal(18,2) NOT NULL,
  `currency` char(3) NOT NULL,
  `status` varchar(32) NOT NULL,
  `operator_id` bigint NOT NULL,
  `reviewer_id` bigint NOT NULL,
  `reason` varchar(512) NOT NULL,
  `external_ref` varchar(128) DEFAULT NULL,
  `failure_code` varchar(64) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_refund_no` (`refund_no`),
  UNIQUE KEY `uk_stored_value_refund_recharge` (`recharge_id`),
  CONSTRAINT `fk_stored_value_refund_recharge` FOREIGN KEY (`recharge_id`) REFERENCES `stored_value_recharge` (`id`),
  CONSTRAINT `fk_stored_value_refund_account` FOREIGN KEY (`account_id`) REFERENCES `stored_value_account` (`id`),
  CONSTRAINT `chk_stored_value_refund_amount` CHECK (`amount` > 0 AND `amount` = ROUND(`amount`,2) AND `currency` = 'CNY'),
  CONSTRAINT `chk_stored_value_refund_environment` CHECK (`provider_env` IN ('DEV','SANDBOX')),
  CONSTRAINT `chk_stored_value_refund_status` CHECK (`status` IN ('REQUESTED','REFUNDING','SUCCEEDED','FAILED','MANUAL_REVIEW')),
  CONSTRAINT `chk_stored_value_refund_reviewer` CHECK (`operator_id` <> `reviewer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_dispute_case` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `case_no` varchar(96) NOT NULL,
  `recharge_id` bigint NOT NULL,
  `account_id` bigint NOT NULL,
  `provider_case_id` varchar(128) NOT NULL,
  `status` varchar(32) NOT NULL,
  `frozen_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `currency` char(3) NOT NULL,
  `reason` varchar(512) NOT NULL,
  `operator_id` bigint DEFAULT NULL,
  `reviewer_id` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_dispute_case_no` (`case_no`),
  UNIQUE KEY `uk_stored_value_dispute_provider_case` (`provider_case_id`),
  CONSTRAINT `fk_stored_value_dispute_recharge` FOREIGN KEY (`recharge_id`) REFERENCES `stored_value_recharge` (`id`),
  CONSTRAINT `fk_stored_value_dispute_account` FOREIGN KEY (`account_id`) REFERENCES `stored_value_account` (`id`),
  CONSTRAINT `chk_stored_value_dispute_amount` CHECK (`frozen_amount` >= 0 AND `frozen_amount` = ROUND(`frozen_amount`,2) AND `currency` = 'CNY'),
  CONSTRAINT `chk_stored_value_dispute_status` CHECK (`status` IN ('OPEN','UNDER_REVIEW','RESOLVED_RECOVERED','RESOLVED_RELEASED','MANUAL_REVIEW')),
  CONSTRAINT `chk_stored_value_dispute_reviewer` CHECK (`reviewer_id` IS NULL OR `operator_id` IS NULL OR `reviewer_id` <> `operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_recovery_case` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `case_no` varchar(96) NOT NULL,
  `recharge_id` bigint NOT NULL,
  `dispute_id` bigint DEFAULT NULL,
  `account_id` bigint NOT NULL,
  `amount` decimal(18,2) NOT NULL,
  `currency` char(3) NOT NULL,
  `status` varchar(32) NOT NULL,
  `reason` varchar(512) NOT NULL,
  `operator_id` bigint DEFAULT NULL,
  `reviewer_id` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_recovery_case_no` (`case_no`),
  CONSTRAINT `fk_stored_value_recovery_recharge` FOREIGN KEY (`recharge_id`) REFERENCES `stored_value_recharge` (`id`),
  CONSTRAINT `fk_stored_value_recovery_dispute` FOREIGN KEY (`dispute_id`) REFERENCES `stored_value_dispute_case` (`id`),
  CONSTRAINT `fk_stored_value_recovery_account` FOREIGN KEY (`account_id`) REFERENCES `stored_value_account` (`id`),
  CONSTRAINT `chk_stored_value_recovery_amount` CHECK (`amount` > 0 AND `amount` = ROUND(`amount`,2) AND `currency` = 'CNY'),
  CONSTRAINT `chk_stored_value_recovery_status` CHECK (`status` IN ('OPEN','IN_PROGRESS','RECOVERED','WRITTEN_OFF','MANUAL_REVIEW')),
  CONSTRAINT `chk_stored_value_recovery_reviewer` CHECK (`reviewer_id` IS NULL OR `operator_id` IS NULL OR `reviewer_id` <> `operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_reconciliation_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_no` varchar(96) NOT NULL,
  `reconcile_date` date NOT NULL,
  `provider_env` varchar(16) NOT NULL,
  `provider_name` varchar(64) NOT NULL,
  `provider_file_digest` char(64) DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `operator_id` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `completed_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_reconciliation_run_no` (`run_no`),
  KEY `idx_stored_value_reconciliation_date` (`reconcile_date`,`provider_env`,`provider_name`),
  CONSTRAINT `chk_stored_value_reconciliation_environment` CHECK (`provider_env` IN ('DEV','SANDBOX')),
  CONSTRAINT `chk_stored_value_reconciliation_status` CHECK (`status` IN ('RUNNING','COMPLETED','FAILED','MANUAL_REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_reconciliation_difference` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_id` bigint NOT NULL,
  `difference_no` varchar(96) NOT NULL,
  `difference_type` varchar(48) NOT NULL,
  `recharge_id` bigint DEFAULT NULL,
  `external_ref` varchar(128) DEFAULT NULL,
  `provider_amount` decimal(18,2) DEFAULT NULL,
  `local_amount` decimal(18,2) DEFAULT NULL,
  `currency` char(3) DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `reason` varchar(512) DEFAULT NULL,
  `adjustment_transaction_id` bigint DEFAULT NULL,
  `operator_id` bigint DEFAULT NULL,
  `reviewer_id` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_reconciliation_difference_no` (`difference_no`),
  KEY `idx_stored_value_reconciliation_difference_run` (`run_id`,`status`,`id`),
  CONSTRAINT `fk_stored_value_reconciliation_difference_run` FOREIGN KEY (`run_id`) REFERENCES `stored_value_reconciliation_run` (`id`),
  CONSTRAINT `fk_stored_value_reconciliation_difference_recharge` FOREIGN KEY (`recharge_id`) REFERENCES `stored_value_recharge` (`id`),
  CONSTRAINT `chk_stored_value_reconciliation_difference_status` CHECK (`status` IN ('OPEN','UNDER_REVIEW','RESOLVED','MANUAL_REVIEW')),
  CONSTRAINT `chk_stored_value_reconciliation_difference_reviewer` CHECK (`reviewer_id` IS NULL OR `operator_id` IS NULL OR `reviewer_id` <> `operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_provider_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `provider_key` varchar(128) NOT NULL,
  `provider_env` varchar(16) NOT NULL,
  `provider_name` varchar(64) NOT NULL,
  `config_version` varchar(96) NOT NULL,
  `enabled` tinyint NOT NULL DEFAULT '0',
  `manual_confirmed` tinyint NOT NULL DEFAULT '0',
  `merchant_identifier_digest` char(64) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT',
  `created_by` bigint DEFAULT NULL,
  `confirmed_by` bigint DEFAULT NULL,
  `confirmed_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_provider_config` (`product_id`,`provider_key`,`config_version`),
  CONSTRAINT `fk_stored_value_provider_config_product` FOREIGN KEY (`product_id`) REFERENCES `stored_value_product` (`id`),
  CONSTRAINT `chk_stored_value_provider_config_environment` CHECK (`provider_env` IN ('DEV','SANDBOX')),
  CONSTRAINT `chk_stored_value_provider_config_flags` CHECK (`enabled` IN (0,1) AND `manual_confirmed` IN (0,1)),
  CONSTRAINT `chk_stored_value_provider_config_status` CHECK (`status` IN ('DRAFT','ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_operation_evidence` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `recharge_id` bigint DEFAULT NULL,
  `refund_id` bigint DEFAULT NULL,
  `operation_type` varchar(32) NOT NULL,
  `operation_key` varchar(192) NOT NULL,
  `provider_key` varchar(128) NOT NULL,
  `provider_env` varchar(16) NOT NULL,
  `provider_name` varchar(64) NOT NULL,
  `config_version` varchar(96) NOT NULL,
  `request_digest` char(64) DEFAULT NULL,
  `request_summary` varchar(1024) DEFAULT NULL,
  `response_digest` char(64) DEFAULT NULL,
  `response_summary` varchar(1024) DEFAULT NULL,
  `event_digest` char(64) NOT NULL,
  `status` varchar(32) NOT NULL,
  `result_code` varchar(64) DEFAULT NULL,
  `source_type` varchar(32) NOT NULL,
  `occurred_at` datetime NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_operation_evidence_event` (`operation_type`,`operation_key`,`event_digest`),
  KEY `idx_stored_value_operation_evidence_recharge` (`recharge_id`,`occurred_at`,`id`),
  KEY `idx_stored_value_operation_evidence_refund` (`refund_id`,`occurred_at`,`id`),
  CONSTRAINT `fk_stored_value_operation_evidence_recharge` FOREIGN KEY (`recharge_id`) REFERENCES `stored_value_recharge` (`id`),
  CONSTRAINT `fk_stored_value_operation_evidence_refund` FOREIGN KEY (`refund_id`) REFERENCES `stored_value_recharge_refund` (`id`),
  CONSTRAINT `chk_stored_value_operation_evidence_environment` CHECK (`provider_env` IN ('DEV','SANDBOX')),
  CONSTRAINT `chk_stored_value_operation_evidence_status` CHECK (`status` IN ('REQUESTED','SUCCEEDED','FAILED','MANUAL_REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `stored_value_ledger_entry`
  ADD CONSTRAINT `fk_stored_value_ledger_entry_transaction`
  FOREIGN KEY (`transaction_id`,`issuer_id`,`product_id`,`currency`)
  REFERENCES `stored_value_ledger_transaction` (`id`,`issuer_id`,`product_id`,`currency`),
  ADD CONSTRAINT `fk_stored_value_ledger_entry_account_scope`
  FOREIGN KEY (`account_id`,`issuer_id`,`product_id`,`currency`)
  REFERENCES `stored_value_account` (`id`,`issuer_id`,`product_id`,`currency`),
  ADD CONSTRAINT `fk_stored_value_ledger_entry_bonus`
  FOREIGN KEY (`bonus_batch_id`,`issuer_id`,`product_id`,`currency`)
  REFERENCES `stored_value_bonus_batch` (`id`,`issuer_id`,`product_id`,`currency`);

ALTER TABLE `stored_value_bonus_batch`
  ADD CONSTRAINT `fk_stored_value_bonus_recharge`
  FOREIGN KEY (`recharge_id`) REFERENCES `stored_value_recharge` (`id`),
  ADD CONSTRAINT `fk_stored_value_bonus_recharge_scope`
  FOREIGN KEY (`recharge_id`,`issuer_id`,`product_id`,`currency`)
  REFERENCES `stored_value_recharge` (`id`,`issuer_id`,`product_id`,`currency`),
  ADD CONSTRAINT `fk_stored_value_bonus_source_transaction`
  FOREIGN KEY (`source_transaction_id`) REFERENCES `stored_value_ledger_transaction` (`id`);

CREATE TRIGGER `trg_stored_value_ledger_transaction_no_update`
BEFORE UPDATE ON `stored_value_ledger_transaction`
FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stored value ledger transactions are immutable';

CREATE TRIGGER `trg_stored_value_ledger_transaction_no_delete`
BEFORE DELETE ON `stored_value_ledger_transaction`
FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stored value ledger transactions are immutable';

CREATE TRIGGER `trg_stored_value_ledger_entry_no_update`
BEFORE UPDATE ON `stored_value_ledger_entry`
FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stored value ledger entries are immutable';

CREATE TRIGGER `trg_stored_value_ledger_entry_no_delete`
BEFORE DELETE ON `stored_value_ledger_entry`
FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stored value ledger entries are immutable';

-- 只注册独立叶子权限，不把高风险储值动作默认授予任何角色。
INSERT INTO `sys_menu` (`menu_name`,`parent_id`,`path`,`component`,`icon`,`perms`,`menu_type`,`sort_order`,`visible`,`status`,`create_time`,`update_time`,`deleted`)
SELECT permission_row.menu_name, root_menu.id, NULL, NULL, NULL, permission_row.perms, 'BUTTON', permission_row.sort_order, 'N', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM (SELECT MIN(id) AS id FROM sys_menu WHERE parent_id = 0 AND path = '/system-admin' AND perms = 'system:view' AND menu_type = 'MENU' AND deleted = 0) root_menu
JOIN (
  SELECT '储值本人查询' AS menu_name, 'stored-value:me' AS perms, 70 AS sort_order
  UNION ALL SELECT '储值财务查看', 'stored-value:finance:view', 71
  UNION ALL SELECT '储值充值复核', 'stored-value:recharge:review', 72
  UNION ALL SELECT '储值退款管理', 'stored-value:refund:manage', 73
  UNION ALL SELECT '储值账户冻结', 'stored-value:account:freeze', 74
  UNION ALL SELECT '储值账户解冻', 'stored-value:account:unfreeze', 75
  UNION ALL SELECT '储值争议管理', 'stored-value:dispute:manage', 76
  UNION ALL SELECT '储值对账执行', 'stored-value:reconciliation:run', 77
  UNION ALL SELECT '储值调整执行', 'stored-value:adjustment:execute', 78
  UNION ALL SELECT '储值调整复核', 'stored-value:adjustment:review', 79
  UNION ALL SELECT '储值政策管理', 'stored-value:policy:manage', 80
) permission_row
WHERE root_menu.id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing_menu WHERE existing_menu.parent_id = root_menu.id AND existing_menu.perms = permission_row.perms AND existing_menu.deleted = 0);
