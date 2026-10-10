-- V2-R7-ADVERTISING/S05：广告查询计划与保留任务所需的时间/状态索引。
-- 仅追加索引，不改变 V24-V26 的事实、状态或保留语义。

CREATE TABLE `advertising_conversion_guard` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `reservation_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_advertising_conversion_guard_reservation` (`user_id`,`reservation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `advertising_decision`
  ADD KEY `idx_advertising_decision_create_time` (`create_time`,`id`);

ALTER TABLE `advertising_event`
  ADD KEY `idx_advertising_event_type_result_time`
    (`event_type`,`result_status`,`occurred_at`,`id`);

ALTER TABLE `advertising_attribution`
  ADD KEY `idx_advertising_attribution_status_time`
    (`status`,`conversion_occurred_at`,`id`);

ALTER TABLE `advertising_conversion_callback`
  ADD KEY `idx_advertising_callback_result_time`
    (`result_code`,`occurred_at`,`id`),
  ADD KEY `idx_advertising_callback_status_time`
    (`status`,`occurred_at`,`id`);

ALTER TABLE `advertising_audit_event`
  ADD KEY `idx_advertising_audit_create_time` (`create_time`,`id`);
