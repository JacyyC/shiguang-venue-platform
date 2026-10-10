-- S02: keep every callback arrival before domain parsing or provider verification.
ALTER TABLE `stored_value_callback_evidence`
  ADD COLUMN `request_id` varchar(64) DEFAULT NULL AFTER `id`,
  ADD COLUMN `route` varchar(128) NOT NULL DEFAULT 'stored-value.callback' AFTER `request_id`,
  MODIFY COLUMN `provider_key` varchar(128) DEFAULT NULL,
  MODIFY COLUMN `provider_env` varchar(16) DEFAULT NULL,
  MODIFY COLUMN `provider_name` varchar(64) DEFAULT NULL,
  DROP INDEX `uk_stored_value_callback_event`;

UPDATE `stored_value_callback_evidence`
SET `request_id` = CONCAT('LEGACY-', `id`)
WHERE `request_id` IS NULL;

ALTER TABLE `stored_value_callback_evidence`
  MODIFY COLUMN `request_id` varchar(64) NOT NULL,
  ADD UNIQUE KEY `uk_stored_value_callback_request` (`request_id`),
  ADD KEY `idx_stored_value_callback_event`
    (`provider_env`, `provider_name`, `external_event_id`, `payload_digest`);
