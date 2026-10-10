-- V2-WAVE-A/S06: community activities, companion requests, deterministic matching,
-- participation, reservation commitments, state evidence, idempotency and notification handoff.

CREATE TABLE `community_activity` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activity_no` varchar(64) NOT NULL,
  `group_id` bigint NOT NULL,
  `reference_post_id` bigint DEFAULT NULL,
  `organizer_user_id` bigint NOT NULL,
  `activity_type` varchar(32) NOT NULL,
  `title` varchar(80) NOT NULL,
  `description` varchar(1000) DEFAULT NULL,
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `location_type` varchar(32) NOT NULL,
  `venue_id` bigint DEFAULT NULL,
  `space_id` bigint DEFAULT NULL,
  `area_code` varchar(64) DEFAULT NULL,
  `reservation_id` bigint DEFAULT NULL,
  `location_commitment_status` varchar(32) NOT NULL DEFAULT 'UNCONFIRMED',
  `commitment_version` bigint NOT NULL DEFAULT '0',
  `capacity_total` int NOT NULL,
  `participant_count` int NOT NULL DEFAULT '1',
  `status` varchar(32) NOT NULL DEFAULT 'DRAFT',
  `version_no` bigint NOT NULL DEFAULT '0',
  `published_at` datetime DEFAULT NULL,
  `closed_at` datetime DEFAULT NULL,
  `canceled_at` datetime DEFAULT NULL,
  `expired_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_activity_no` (`activity_no`),
  KEY `idx_community_activity_discovery` (`status`,`activity_type`,`start_time`,`id`),
  KEY `idx_community_activity_group` (`group_id`,`status`,`start_time`,`id`),
  KEY `idx_community_activity_organizer` (`organizer_user_id`,`status`,`id`),
  KEY `idx_community_activity_reservation` (`reservation_id`,`location_commitment_status`,`id`),
  KEY `idx_community_activity_location` (`location_type`,`venue_id`,`space_id`,`area_code`,`status`,`start_time`,`id`),
  CONSTRAINT `fk_community_activity_group`
    FOREIGN KEY (`group_id`) REFERENCES `community_group` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_community_activity_post`
    FOREIGN KEY (`reference_post_id`) REFERENCES `community_post` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_community_activity_type`
    CHECK (`activity_type` IN ('STUDY','SPORTS','BOARD_GAME','WORKSHOP','OTHER')),
  CONSTRAINT `chk_community_activity_time`
    CHECK (`start_time` < `end_time`),
  CONSTRAINT `chk_community_activity_location`
    CHECK ((`location_type` = 'VENUE_SPACE'
        AND `venue_id` IS NOT NULL AND `space_id` IS NOT NULL AND `area_code` IS NULL)
      OR (`location_type` = 'CAMPUS_AREA'
        AND `venue_id` IS NULL AND `space_id` IS NULL AND `area_code` IS NOT NULL)),
  CONSTRAINT `chk_community_activity_commitment`
    CHECK ((`location_commitment_status` = 'CONFIRMED'
        AND `location_type` = 'VENUE_SPACE' AND `reservation_id` IS NOT NULL)
      OR (`location_commitment_status` = 'UNCONFIRMED' AND `reservation_id` IS NULL)
      OR (`location_commitment_status` = 'DEGRADED'
        AND `location_type` = 'VENUE_SPACE' AND `reservation_id` IS NOT NULL)),
  CONSTRAINT `chk_community_activity_capacity`
    CHECK (`capacity_total` BETWEEN 2 AND 1000
      AND `participant_count` BETWEEN 0 AND `capacity_total`),
  CONSTRAINT `chk_community_activity_status`
    CHECK (`status` IN ('DRAFT','PUBLISHED','FULL','CLOSED','CANCELED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `community_companion_request` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_no` varchar(64) NOT NULL,
  `group_id` bigint NOT NULL,
  `activity_id` bigint DEFAULT NULL,
  `reference_post_id` bigint DEFAULT NULL,
  `owner_user_id` bigint NOT NULL,
  `activity_type` varchar(32) NOT NULL,
  `title` varchar(80) NOT NULL,
  `description` varchar(1000) DEFAULT NULL,
  `window_start` datetime NOT NULL,
  `window_end` datetime NOT NULL,
  `location_type` varchar(32) NOT NULL,
  `venue_id` bigint DEFAULT NULL,
  `space_id` bigint DEFAULT NULL,
  `area_code` varchar(64) DEFAULT NULL,
  `reservation_id` bigint DEFAULT NULL,
  `location_commitment_status` varchar(32) NOT NULL DEFAULT 'UNCONFIRMED',
  `commitment_version` bigint NOT NULL DEFAULT '0',
  `capacity_total` int NOT NULL,
  `participant_count` int NOT NULL DEFAULT '1',
  `status` varchar(32) NOT NULL DEFAULT 'DRAFT',
  `version_no` bigint NOT NULL DEFAULT '0',
  `published_at` datetime DEFAULT NULL,
  `closed_at` datetime DEFAULT NULL,
  `canceled_at` datetime DEFAULT NULL,
  `expired_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_companion_request_no` (`request_no`),
  KEY `idx_community_companion_discovery` (`status`,`activity_type`,`window_start`,`window_end`,`id`),
  KEY `idx_community_companion_group` (`group_id`,`status`,`window_start`,`id`),
  KEY `idx_community_companion_owner` (`owner_user_id`,`status`,`id`),
  KEY `idx_community_companion_activity` (`activity_id`,`status`,`id`),
  KEY `idx_community_companion_reservation` (`reservation_id`,`location_commitment_status`,`id`),
  KEY `idx_community_companion_location` (`location_type`,`venue_id`,`space_id`,`area_code`,`status`,`window_start`,`id`),
  CONSTRAINT `fk_community_companion_group`
    FOREIGN KEY (`group_id`) REFERENCES `community_group` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_community_companion_activity`
    FOREIGN KEY (`activity_id`) REFERENCES `community_activity` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_community_companion_post`
    FOREIGN KEY (`reference_post_id`) REFERENCES `community_post` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_community_companion_type`
    CHECK (`activity_type` IN ('STUDY','SPORTS','BOARD_GAME','WORKSHOP','OTHER')),
  CONSTRAINT `chk_community_companion_time`
    CHECK (`window_start` < `window_end`),
  CONSTRAINT `chk_community_companion_location`
    CHECK ((`location_type` = 'VENUE_SPACE'
        AND `venue_id` IS NOT NULL AND `space_id` IS NOT NULL AND `area_code` IS NULL)
      OR (`location_type` = 'CAMPUS_AREA'
        AND `venue_id` IS NULL AND `space_id` IS NULL AND `area_code` IS NOT NULL)),
  CONSTRAINT `chk_community_companion_commitment`
    CHECK ((`location_commitment_status` = 'CONFIRMED'
        AND `location_type` = 'VENUE_SPACE' AND `reservation_id` IS NOT NULL)
      OR (`location_commitment_status` = 'UNCONFIRMED' AND `reservation_id` IS NULL)
      OR (`location_commitment_status` = 'DEGRADED'
        AND `location_type` = 'VENUE_SPACE' AND `reservation_id` IS NOT NULL)),
  CONSTRAINT `chk_community_companion_capacity`
    CHECK (`capacity_total` BETWEEN 2 AND 100
      AND `participant_count` BETWEEN 0 AND `capacity_total`),
  CONSTRAINT `chk_community_companion_status`
    CHECK (`status` IN ('DRAFT','PUBLISHED','FULL','CLOSED','CANCELED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `community_activity_application` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `application_no` varchar(64) NOT NULL,
  `target_type` varchar(32) NOT NULL,
  `target_id` bigint NOT NULL,
  `applicant_user_id` bigint NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `reason_code` varchar(64) DEFAULT NULL,
  `applied_at` datetime NOT NULL,
  `decided_by` bigint DEFAULT NULL,
  `decided_at` datetime DEFAULT NULL,
  `withdrawn_at` datetime DEFAULT NULL,
  `version_no` bigint NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_activity_application_no` (`application_no`),
  UNIQUE KEY `uk_community_activity_application_target_user`
    (`target_type`,`target_id`,`applicant_user_id`),
  KEY `idx_community_activity_application_target`
    (`target_type`,`target_id`,`status`,`id`),
  KEY `idx_community_activity_application_user`
    (`applicant_user_id`,`status`,`id`),
  CONSTRAINT `chk_community_activity_application_target`
    CHECK (`target_type` IN ('ACTIVITY','COMPANION_REQUEST')),
  CONSTRAINT `chk_community_activity_application_status`
    CHECK (`status` IN ('PENDING','ACCEPTED','REJECTED','WITHDRAWN')),
  CONSTRAINT `chk_community_activity_application_timeline`
    CHECK ((`status` = 'PENDING' AND `decided_at` IS NULL AND `withdrawn_at` IS NULL)
      OR (`status` IN ('ACCEPTED','REJECTED') AND `decided_by` IS NOT NULL
        AND `decided_at` IS NOT NULL AND `withdrawn_at` IS NULL)
      OR (`status` = 'WITHDRAWN' AND `withdrawn_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `community_activity_participation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `participation_no` varchar(64) NOT NULL,
  `target_type` varchar(32) NOT NULL,
  `target_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `role_type` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
  `application_id` bigint DEFAULT NULL,
  `joined_at` datetime NOT NULL,
  `left_at` datetime DEFAULT NULL,
  `version_no` bigint NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_activity_participation_no` (`participation_no`),
  UNIQUE KEY `uk_community_activity_participation_target_user`
    (`target_type`,`target_id`,`user_id`),
  KEY `idx_community_activity_participation_target`
    (`target_type`,`target_id`,`status`,`id`),
  KEY `idx_community_activity_participation_user`
    (`user_id`,`status`,`id`),
  CONSTRAINT `fk_community_activity_participation_application`
    FOREIGN KEY (`application_id`) REFERENCES `community_activity_application` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_community_activity_participation_target`
    CHECK (`target_type` IN ('ACTIVITY','COMPANION_REQUEST')),
  CONSTRAINT `chk_community_activity_participation_role`
    CHECK (`role_type` IN ('ORGANIZER','OWNER','PARTICIPANT')),
  CONSTRAINT `chk_community_activity_participation_status`
    CHECK (`status` IN ('ACTIVE','LEFT','CANCELED')),
  CONSTRAINT `chk_community_activity_participation_timeline`
    CHECK ((`status` = 'ACTIVE' AND `left_at` IS NULL)
      OR (`status` IN ('LEFT','CANCELED') AND `left_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `community_match_decision` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `decision_no` varchar(64) NOT NULL,
  `source_request_id` bigint NOT NULL,
  `candidate_request_id` bigint NOT NULL,
  `source_version` bigint NOT NULL,
  `candidate_version` bigint NOT NULL,
  `rule_version` varchar(32) NOT NULL,
  `outcome` varchar(32) NOT NULL,
  `score` int NOT NULL DEFAULT '0',
  `reason_codes_json` json NOT NULL,
  `evidence_json` json NOT NULL,
  `decided_at` datetime NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_match_decision_no` (`decision_no`),
  UNIQUE KEY `uk_community_match_decision_replay`
    (`source_request_id`,`candidate_request_id`,`source_version`,`candidate_version`,`rule_version`),
  KEY `idx_community_match_decision_result`
    (`source_request_id`,`source_version`,`rule_version`,`outcome`,`score`,`candidate_request_id`),
  CONSTRAINT `fk_community_match_source`
    FOREIGN KEY (`source_request_id`) REFERENCES `community_companion_request` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_community_match_candidate`
    FOREIGN KEY (`candidate_request_id`) REFERENCES `community_companion_request` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_community_match_distinct`
    CHECK (`source_request_id` <> `candidate_request_id`),
  CONSTRAINT `chk_community_match_rule`
    CHECK (`rule_version` = 'COMPANION_MATCH_V1'),
  CONSTRAINT `chk_community_match_outcome`
    CHECK (`outcome` IN ('MATCHED','EXCLUDED')),
  CONSTRAINT `chk_community_match_score`
    CHECK (`score` BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `community_activity_state_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_no` varchar(64) NOT NULL,
  `event_type` varchar(64) NOT NULL,
  `subject_type` varchar(32) NOT NULL,
  `subject_id` bigint NOT NULL,
  `actor_user_id` bigint DEFAULT NULL,
  `source_type` varchar(32) NOT NULL,
  `from_status` varchar(32) DEFAULT NULL,
  `to_status` varchar(32) DEFAULT NULL,
  `reason_code` varchar(64) DEFAULT NULL,
  `idempotency_key` varchar(128) NOT NULL,
  `metadata_json` json NOT NULL,
  `occurred_at` datetime NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_activity_event_no` (`event_no`),
  UNIQUE KEY `uk_community_activity_event_idempotency`
    (`subject_type`,`subject_id`,`event_type`,`idempotency_key`),
  KEY `idx_community_activity_event_subject` (`subject_type`,`subject_id`,`id`),
  KEY `idx_community_activity_event_actor` (`actor_user_id`,`occurred_at`,`id`),
  CONSTRAINT `chk_community_activity_event_subject`
    CHECK (`subject_type` IN ('ACTIVITY','COMPANION_REQUEST','APPLICATION','PARTICIPATION','MATCH_DECISION','LOCATION_COMMITMENT')),
  CONSTRAINT `chk_community_activity_event_source`
    CHECK (`source_type` IN ('USER','ORGANIZER','SYSTEM','RESERVATION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `community_activity_command` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `actor_user_id` bigint NOT NULL,
  `command_type` varchar(64) NOT NULL,
  `idempotency_key` varchar(128) NOT NULL,
  `request_hash` varchar(64) NOT NULL,
  `target_type` varchar(32) DEFAULT NULL,
  `target_id` bigint DEFAULT NULL,
  `result_status` varchar(32) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'PROCESSING',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `completed_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_activity_command`
    (`actor_user_id`,`command_type`,`idempotency_key`),
  KEY `idx_community_activity_command_target` (`target_type`,`target_id`,`id`),
  CONSTRAINT `chk_community_activity_command_status`
    CHECK ((`status` = 'PROCESSING' AND `completed_at` IS NULL)
      OR (`status` = 'COMPLETED' AND `target_type` IS NOT NULL
        AND `target_id` IS NOT NULL AND `result_status` IS NOT NULL
        AND `completed_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `community_activity_notification_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_type` varchar(64) NOT NULL,
  `event_key` varchar(128) NOT NULL,
  `recipient_user_id` bigint NOT NULL,
  `title` varchar(200) NOT NULL,
  `content` varchar(1000) NOT NULL,
  `business_type` varchar(32) NOT NULL,
  `business_id` bigint NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `attempt_count` int NOT NULL DEFAULT '0',
  `next_retry_time` datetime DEFAULT NULL,
  `last_error_code` varchar(64) DEFAULT NULL,
  `last_error_message` varchar(255) DEFAULT NULL,
  `claimed_at` datetime DEFAULT NULL,
  `delivered_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_activity_notification_event`
    (`event_key`,`recipient_user_id`),
  KEY `idx_community_activity_notification_due`
    (`status`,`next_retry_time`,`update_time`,`id`),
  CONSTRAINT `chk_community_activity_notification_status`
    CHECK (`status` IN ('PENDING','DISPATCHING','RETRY_WAITING','DELIVERED','FAILED')),
  CONSTRAINT `chk_community_activity_notification_attempts`
    CHECK (`attempt_count` BETWEEN 0 AND 8),
  CONSTRAINT `chk_community_activity_notification_timeline`
    CHECK ((`status` = 'PENDING' AND `attempt_count` = 0
        AND `next_retry_time` IS NULL AND `claimed_at` IS NULL AND `delivered_at` IS NULL)
      OR (`status` = 'DISPATCHING' AND `attempt_count` BETWEEN 1 AND 8
        AND `next_retry_time` IS NULL AND `claimed_at` IS NOT NULL AND `delivered_at` IS NULL)
      OR (`status` = 'RETRY_WAITING' AND `attempt_count` BETWEEN 1 AND 7
        AND `next_retry_time` IS NOT NULL AND `claimed_at` IS NULL AND `delivered_at` IS NULL)
      OR (`status` = 'DELIVERED' AND `attempt_count` BETWEEN 1 AND 8
        AND `next_retry_time` IS NULL AND `claimed_at` IS NULL AND `delivered_at` IS NOT NULL)
      OR (`status` = 'FAILED' AND `attempt_count` = 8
        AND `next_retry_time` IS NULL AND `claimed_at` IS NULL AND `delivered_at` IS NULL
        AND `last_error_code` IS NOT NULL AND `last_error_message` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
