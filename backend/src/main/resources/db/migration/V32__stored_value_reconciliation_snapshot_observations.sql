-- S05: persist stable statement metadata and append-only difference observations.
-- Existing V1-V31 facts remain unchanged; this migration contains no business seed data.

ALTER TABLE `stored_value_reconciliation_run`
  ADD COLUMN `statement_snapshot_key` char(64) DEFAULT NULL AFTER `provider_file_digest`,
  ADD COLUMN `period_start` datetime DEFAULT NULL AFTER `statement_snapshot_key`,
  ADD COLUMN `period_end` datetime DEFAULT NULL AFTER `period_start`,
  ADD COLUMN `provider_config_version` varchar(96) DEFAULT NULL AFTER `period_end`,
  ADD COLUMN `provider_config_digest` char(64) DEFAULT NULL AFTER `provider_config_version`,
  ADD COLUMN `provider_source` varchar(64) DEFAULT NULL AFTER `provider_config_digest`,
  ADD COLUMN `statement_item_count` int NOT NULL DEFAULT 0 AFTER `provider_source`,
  ADD COLUMN `statement_summary` varchar(512) DEFAULT NULL AFTER `statement_item_count`,
  ADD COLUMN `failure_code` varchar(64) DEFAULT NULL AFTER `statement_summary`,
  ADD COLUMN `failure_message` varchar(512) DEFAULT NULL AFTER `failure_code`,
  ADD UNIQUE KEY `uk_stored_value_reconciliation_snapshot` (`statement_snapshot_key`),
  ADD CONSTRAINT `chk_stored_value_reconciliation_item_count` CHECK (`statement_item_count` >= 0);

CREATE TABLE `stored_value_reconciliation_difference_observation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `difference_id` bigint NOT NULL,
  `run_id` bigint NOT NULL,
  `provider_amount` decimal(18,2) DEFAULT NULL,
  `local_amount` decimal(18,2) DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `reason` varchar(512) DEFAULT NULL,
  `observed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stored_value_reconciliation_difference_observation` (`difference_id`,`run_id`),
  KEY `idx_stored_value_reconciliation_observation_run` (`run_id`,`id`),
  CONSTRAINT `fk_stored_value_reconciliation_observation_difference`
    FOREIGN KEY (`difference_id`) REFERENCES `stored_value_reconciliation_difference` (`id`),
  CONSTRAINT `fk_stored_value_reconciliation_observation_run`
    FOREIGN KEY (`run_id`) REFERENCES `stored_value_reconciliation_run` (`id`),
  CONSTRAINT `chk_stored_value_reconciliation_observation_amount`
    CHECK ((`provider_amount` IS NULL OR `provider_amount` = ROUND(`provider_amount`,2))
      AND (`local_amount` IS NULL OR `local_amount` = ROUND(`local_amount`,2)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
