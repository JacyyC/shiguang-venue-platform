-- S06: keep order stored-value facts separate from provider payment facts.
-- This migration is append-only. Existing V1-V21 objects remain unchanged.

ALTER TABLE `biz_order`
  ADD COLUMN `payment_tool_type` varchar(32) DEFAULT NULL AFTER `pay_type`,
  ADD COLUMN `payment_fact_id` varchar(128) DEFAULT NULL AFTER `payment_tool_type`,
  ADD COLUMN `payment_currency` varchar(16) DEFAULT NULL AFTER `payment_fact_id`,
  ADD UNIQUE KEY `uk_biz_order_payment_fact` (`payment_tool_type`,`payment_fact_id`);

CREATE TABLE `stored_value_order_payment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `payment_no` varchar(96) NOT NULL,
  `order_id` bigint NOT NULL,
  `order_no` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `account_id` bigint NOT NULL,
  `issuer_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `policy_version` varchar(96) NOT NULL,
  `currency` char(3) NOT NULL,
  `amount` decimal(18,2) NOT NULL,
  `principal_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `bonus_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `idempotency_key` varchar(192) NOT NULL,
  `status` varchar(24) NOT NULL DEFAULT 'POSTING',
  `source_type` varchar(32) NOT NULL,
  `failure_code` varchar(64) DEFAULT NULL,
  `failure_message` varchar(512) DEFAULT NULL,
  `paid_at` datetime DEFAULT NULL,
  `refunded_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_order_payment_no` (`payment_no`),
  UNIQUE KEY `uk_stored_value_order_payment_order` (`order_id`),
  UNIQUE KEY `uk_stored_value_order_payment_idempotency` (`order_id`,`idempotency_key`),
  UNIQUE KEY `uk_stored_value_order_payment_fact` (`id`,`order_id`),
  KEY `idx_stored_value_order_payment_account` (`account_id`,`status`,`id`),
  KEY `idx_stored_value_order_payment_date` (`paid_at`,`status`,`id`),
  CONSTRAINT `fk_stored_value_order_payment_order` FOREIGN KEY (`order_id`) REFERENCES `biz_order` (`id`),
  CONSTRAINT `fk_stored_value_order_payment_account` FOREIGN KEY (`account_id`) REFERENCES `stored_value_account` (`id`),
  CONSTRAINT `fk_stored_value_order_payment_account_scope`
    FOREIGN KEY (`account_id`,`issuer_id`,`product_id`,`currency`)
    REFERENCES `stored_value_account` (`id`,`issuer_id`,`product_id`,`currency`),
  CONSTRAINT `fk_stored_value_order_payment_issuer` FOREIGN KEY (`issuer_id`) REFERENCES `stored_value_issuer` (`id`),
  CONSTRAINT `fk_stored_value_order_payment_product` FOREIGN KEY (`product_id`) REFERENCES `stored_value_product` (`id`),
  CONSTRAINT `chk_stored_value_order_payment_currency` CHECK (`currency` = 'CNY'),
  CONSTRAINT `chk_stored_value_order_payment_amount`
    CHECK (`amount` > 0 AND `amount` = ROUND(`amount`,2)
      AND `principal_amount` >= 0 AND `principal_amount` = ROUND(`principal_amount`,2)
      AND `bonus_amount` >= 0 AND `bonus_amount` = ROUND(`bonus_amount`,2)
      AND `principal_amount` + `bonus_amount` = `amount`),
  CONSTRAINT `chk_stored_value_order_payment_status`
    CHECK (`status` IN ('POSTING','SUCCEEDED','REFUNDING','REFUNDED','MANUAL_REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_order_payment_allocation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `payment_id` bigint NOT NULL,
  `allocation_no` int NOT NULL,
  `bucket` varchar(32) NOT NULL,
  `bonus_batch_id` bigint DEFAULT NULL,
  `amount` decimal(18,2) NOT NULL,
  `refunded_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `expires_at_snapshot` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_order_payment_allocation_no` (`payment_id`,`allocation_no`),
  KEY `idx_stored_value_order_payment_allocation_batch` (`bonus_batch_id`,`bucket`,`id`),
  CONSTRAINT `fk_stored_value_order_payment_allocation_payment`
    FOREIGN KEY (`payment_id`) REFERENCES `stored_value_order_payment` (`id`),
  CONSTRAINT `fk_stored_value_order_payment_allocation_batch`
    FOREIGN KEY (`bonus_batch_id`) REFERENCES `stored_value_bonus_batch` (`id`),
  CONSTRAINT `chk_stored_value_order_payment_allocation_bucket`
    CHECK (`bucket` IN ('PRINCIPAL_AVAILABLE','BONUS_AVAILABLE')),
  CONSTRAINT `chk_stored_value_order_payment_allocation_amount`
    CHECK (`amount` > 0 AND `amount` = ROUND(`amount`,2)
      AND `refunded_amount` >= 0 AND `refunded_amount` = ROUND(`refunded_amount`,2)
      AND `refunded_amount` <= `amount`),
  CONSTRAINT `chk_stored_value_order_payment_allocation_batch`
    CHECK ((`bucket` = 'PRINCIPAL_AVAILABLE' AND `bonus_batch_id` IS NULL)
      OR (`bucket` = 'BONUS_AVAILABLE' AND `bonus_batch_id` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `stored_value_bonus_batch`
  MODIFY COLUMN `recharge_id` bigint DEFAULT NULL,
  ADD COLUMN `source_order_payment_id` bigint DEFAULT NULL AFTER `source_transaction_id`,
  ADD KEY `idx_stored_value_bonus_order_payment` (`source_order_payment_id`,`id`),
  ADD CONSTRAINT `fk_stored_value_bonus_order_payment`
    FOREIGN KEY (`source_order_payment_id`) REFERENCES `stored_value_order_payment` (`id`),
  ADD CONSTRAINT `chk_stored_value_bonus_source`
    CHECK (`recharge_id` IS NOT NULL OR `source_order_payment_id` IS NOT NULL);

CREATE TABLE `stored_value_order_refund` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `refund_no` varchar(96) NOT NULL,
  `order_payment_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `refund_record_id` bigint DEFAULT NULL,
  `idempotency_key` varchar(192) NOT NULL,
  `amount` decimal(18,2) NOT NULL,
  `principal_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `bonus_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `compensation_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `currency` char(3) NOT NULL,
  `policy_version` varchar(96) NOT NULL,
  `status` varchar(24) NOT NULL DEFAULT 'POSTING',
  `reason` varchar(512) NOT NULL,
  `confirmation_time` datetime DEFAULT NULL,
  `failure_code` varchar(64) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_order_refund_no` (`refund_no`),
  UNIQUE KEY `uk_stored_value_order_refund_payment` (`order_payment_id`),
  UNIQUE KEY `uk_stored_value_order_refund_record` (`refund_record_id`),
  UNIQUE KEY `uk_stored_value_order_refund_idempotency` (`order_payment_id`,`idempotency_key`),
  KEY `idx_stored_value_order_refund_order` (`order_id`,`status`,`id`),
  CONSTRAINT `fk_stored_value_order_refund_payment`
    FOREIGN KEY (`order_payment_id`) REFERENCES `stored_value_order_payment` (`id`),
  CONSTRAINT `fk_stored_value_order_refund_order` FOREIGN KEY (`order_id`) REFERENCES `biz_order` (`id`),
  CONSTRAINT `fk_stored_value_order_refund_record` FOREIGN KEY (`refund_record_id`) REFERENCES `refund_record` (`id`),
  CONSTRAINT `chk_stored_value_order_refund_currency` CHECK (`currency` = 'CNY'),
  CONSTRAINT `chk_stored_value_order_refund_amount`
    CHECK (`amount` > 0 AND `amount` = ROUND(`amount`,2)
      AND `principal_amount` >= 0 AND `principal_amount` = ROUND(`principal_amount`,2)
      AND `bonus_amount` >= 0 AND `bonus_amount` = ROUND(`bonus_amount`,2)
      AND `compensation_amount` >= 0 AND `compensation_amount` = ROUND(`compensation_amount`,2)
      AND `principal_amount` + `bonus_amount` = `amount`),
  CONSTRAINT `chk_stored_value_order_refund_status`
    CHECK (`status` IN ('POSTING','SUCCEEDED','MANUAL_REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `stored_value_order_refund_allocation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `refund_id` bigint NOT NULL,
  `payment_allocation_id` bigint NOT NULL,
  `source_bonus_batch_id` bigint DEFAULT NULL,
  `restored_bonus_batch_id` bigint DEFAULT NULL,
  `bucket` varchar(32) NOT NULL,
  `amount` decimal(18,2) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_order_refund_allocation` (`refund_id`,`payment_allocation_id`),
  CONSTRAINT `fk_stored_value_order_refund_allocation_refund`
    FOREIGN KEY (`refund_id`) REFERENCES `stored_value_order_refund` (`id`),
  CONSTRAINT `fk_stored_value_order_refund_allocation_payment_allocation`
    FOREIGN KEY (`payment_allocation_id`) REFERENCES `stored_value_order_payment_allocation` (`id`),
  CONSTRAINT `fk_stored_value_order_refund_allocation_source_batch`
    FOREIGN KEY (`source_bonus_batch_id`) REFERENCES `stored_value_bonus_batch` (`id`),
  CONSTRAINT `fk_stored_value_order_refund_allocation_restored_batch`
    FOREIGN KEY (`restored_bonus_batch_id`) REFERENCES `stored_value_bonus_batch` (`id`),
  CONSTRAINT `chk_stored_value_order_refund_allocation_bucket`
    CHECK (`bucket` IN ('PRINCIPAL_AVAILABLE','BONUS_AVAILABLE')),
  CONSTRAINT `chk_stored_value_order_refund_allocation_amount`
    CHECK (`amount` > 0 AND `amount` = ROUND(`amount`,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `stored_value_reconciliation_run`
  ADD COLUMN `scope_type` varchar(32) NOT NULL DEFAULT 'PROVIDER' AFTER `provider_name`;

ALTER TABLE `stored_value_reconciliation_difference`
  ADD COLUMN `order_payment_id` bigint DEFAULT NULL AFTER `recharge_id`,
  ADD COLUMN `order_refund_id` bigint DEFAULT NULL AFTER `order_payment_id`,
  ADD COLUMN `ledger_transaction_id` bigint DEFAULT NULL AFTER `order_refund_id`,
  ADD COLUMN `account_id` bigint DEFAULT NULL AFTER `ledger_transaction_id`,
  ADD KEY `idx_stored_value_reconciliation_difference_order_payment` (`order_payment_id`,`id`),
  ADD KEY `idx_stored_value_reconciliation_difference_order_refund` (`order_refund_id`,`id`),
  ADD CONSTRAINT `fk_stored_value_reconciliation_difference_order_payment`
    FOREIGN KEY (`order_payment_id`) REFERENCES `stored_value_order_payment` (`id`),
  ADD CONSTRAINT `fk_stored_value_reconciliation_difference_order_refund`
    FOREIGN KEY (`order_refund_id`) REFERENCES `stored_value_order_refund` (`id`),
  ADD CONSTRAINT `fk_stored_value_reconciliation_difference_ledger`
    FOREIGN KEY (`ledger_transaction_id`) REFERENCES `stored_value_ledger_transaction` (`id`),
  ADD CONSTRAINT `fk_stored_value_reconciliation_difference_account`
    FOREIGN KEY (`account_id`) REFERENCES `stored_value_account` (`id`);
