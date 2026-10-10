-- Persist the posting lease so a process stop after claim can be recovered by
-- the same recharge business key without re-calling the provider.
ALTER TABLE `stored_value_recharge`
  ADD COLUMN `posting_attempt_count` int NOT NULL DEFAULT 0 AFTER `status`,
  ADD COLUMN `posting_claim_token` varchar(64) DEFAULT NULL AFTER `posting_attempt_count`,
  ADD COLUMN `posting_started_at` datetime DEFAULT NULL AFTER `posting_claim_token`,
  ADD COLUMN `posting_last_failure_code` varchar(64) DEFAULT NULL AFTER `posting_started_at`,
  ADD COLUMN `posting_last_failure_message` varchar(512) DEFAULT NULL AFTER `posting_last_failure_code`,
  ADD KEY `idx_stored_value_recharge_posting_recovery`
    (`status`,`posting_started_at`,`id`),
  ADD CONSTRAINT `chk_stored_value_recharge_posting_attempt`
    CHECK (`posting_attempt_count` >= 0);
