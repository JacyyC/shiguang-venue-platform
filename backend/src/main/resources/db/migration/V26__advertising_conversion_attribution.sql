-- V2-R7-ADVERTISING/S04：预约提交后的广告回连任务、最后点击归因和生命周期缺口。
-- 回连只保存最小预约引用与状态快照，不写预约、订单、支付或金额事实。

CREATE TABLE `advertising_conversion_callback` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `callback_no` varchar(64) NOT NULL,
  `event_key` varchar(192) NOT NULL,
  `event_type` varchar(32) NOT NULL,
  `reservation_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `venue_id` bigint DEFAULT NULL,
  `source_status` varchar(32) NOT NULL,
  `policy_version` varchar(64) NOT NULL,
  `occurred_at` datetime NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `result_code` varchar(64) DEFAULT NULL,
  `attempt_count` int NOT NULL DEFAULT 0,
  `next_retry_at` datetime DEFAULT NULL,
  `processed_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_advertising_callback_no` (`callback_no`),
  UNIQUE KEY `uk_advertising_callback_event_key` (`event_key`),
  KEY `idx_advertising_callback_reservation_event` (`reservation_id`,`event_type`,`id`),
  KEY `idx_advertising_callback_retry` (`status`,`next_retry_at`,`id`),
  CONSTRAINT `chk_advertising_callback_event_type`
    CHECK (`event_type` IN ('RESERVATION_SUBMITTED','ORDER_SUBMITTED','RESERVATION_REVOKED')),
  CONSTRAINT `chk_advertising_callback_status`
    CHECK (`status` IN ('PENDING','PROCESSED','PENDING_RETRY')),
  CONSTRAINT `chk_advertising_callback_attempt_count`
    CHECK (`attempt_count` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `advertising_attribution` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `attribution_no` varchar(64) NOT NULL,
  `reservation_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `click_event_id` bigint DEFAULT NULL,
  `decision_id` bigint DEFAULT NULL,
  `advertiser_id` bigint DEFAULT NULL,
  `campaign_id` bigint DEFAULT NULL,
  `creative_version_id` bigint DEFAULT NULL,
  `click_occurred_at` datetime DEFAULT NULL,
  `conversion_occurred_at` datetime NOT NULL,
  `policy_version` varchar(64) NOT NULL,
  `rule_version_snapshot` varchar(64) NOT NULL,
  `status` varchar(32) NOT NULL,
  `reason_code` varchar(64) NOT NULL,
  `source_status` varchar(32) NOT NULL,
  `source_event_key` varchar(192) NOT NULL,
  `revoked_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_advertising_attribution_no` (`attribution_no`),
  UNIQUE KEY `uk_advertising_attribution_reservation` (`reservation_id`),
  KEY `idx_advertising_attribution_click_event` (`click_event_id`,`id`),
  KEY `idx_advertising_attribution_user_status_time` (`user_id`,`status`,`conversion_occurred_at`,`id`),
  KEY `idx_advertising_attribution_campaign_time` (`campaign_id`,`creative_version_id`,`conversion_occurred_at`,`id`),
  CONSTRAINT `chk_advertising_attribution_status`
    CHECK (`status` IN ('ATTRIBUTED','GAP','REVOKED')),
  CONSTRAINT `chk_advertising_attribution_click_snapshot`
    CHECK (`status` <> 'ATTRIBUTED'
      OR (`click_event_id` IS NOT NULL AND `decision_id` IS NOT NULL
        AND `advertiser_id` IS NOT NULL AND `campaign_id` IS NOT NULL
        AND `creative_version_id` IS NOT NULL AND `click_occurred_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
