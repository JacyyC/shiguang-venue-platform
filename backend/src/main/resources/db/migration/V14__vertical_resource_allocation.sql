-- V2-WAVE-A/S02：建立版本化资源规则、座位单元和统一分配事实。
-- 旧 venue_space.capacity 继续只描述物理容量，不直接成为可售库存；所有历史空间按
-- SPORTS_FIELD / EXCLUSIVE / V1 回填。reservation_occupancy 在原表上追加模式、数量、
-- 规则和快照字段，保留现有订单/支付/取消路径对 active_flag 的释放语义。

CREATE TABLE `venue_resource_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `space_id` bigint NOT NULL,
  `resource_category` varchar(32) NOT NULL,
  `booking_mode` varchar(32) NOT NULL,
  `capacity_limit` int DEFAULT NULL,
  `version_no` int NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
  `active_flag` tinyint DEFAULT '1',
  `effective_from` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `reason_text` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_venue_resource_rule_version` (`space_id`,`version_no`),
  UNIQUE KEY `uk_venue_resource_rule_active` (`space_id`,`active_flag`),
  KEY `idx_venue_resource_rule_mode` (`resource_category`,`booking_mode`,`status`),
  CONSTRAINT `chk_venue_resource_rule_category`
    CHECK (`resource_category` IN ('SPORTS_FIELD','STUDY_ROOM','ACTIVITY_ROOM')),
  CONSTRAINT `chk_venue_resource_rule_mode`
    CHECK (`booking_mode` IN ('EXCLUSIVE','CAPACITY','ASSIGNED_SEAT')),
  CONSTRAINT `chk_venue_resource_rule_capacity`
    CHECK ((`booking_mode` = 'EXCLUSIVE' AND `capacity_limit` = 1)
      OR (`booking_mode` = 'CAPACITY' AND `capacity_limit` > 0)
      OR (`booking_mode` = 'ASSIGNED_SEAT' AND `capacity_limit` IS NULL)),
  CONSTRAINT `chk_venue_resource_rule_active_flag`
    CHECK (`active_flag` IS NULL OR `active_flag` = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `venue_resource_seat` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `space_id` bigint NOT NULL,
  `rule_id` bigint NOT NULL,
  `seat_code` varchar(64) NOT NULL,
  `seat_label` varchar(128) NOT NULL,
  `zone_code` varchar(64) DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_venue_resource_seat_rule_code` (`rule_id`,`seat_code`),
  KEY `idx_venue_resource_seat_space_rule_status` (`space_id`,`rule_id`,`status`,`sort_order`),
  CONSTRAINT `chk_venue_resource_seat_status` CHECK (`status` IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `reservation`
  ADD COLUMN `resource_category_snapshot` varchar(32) NOT NULL DEFAULT 'SPORTS_FIELD',
  ADD COLUMN `booking_mode_snapshot` varchar(32) NOT NULL DEFAULT 'EXCLUSIVE',
  ADD COLUMN `rule_id_snapshot` bigint DEFAULT NULL,
  ADD COLUMN `rule_version_snapshot` int NOT NULL DEFAULT '1',
  ADD COLUMN `participant_count` int NOT NULL DEFAULT '1',
  ADD COLUMN `allocation_snapshot_json` json DEFAULT NULL,
  ADD KEY `idx_reservation_rule_snapshot` (`rule_id_snapshot`,`booking_mode_snapshot`),
  ADD CONSTRAINT `chk_reservation_participant_count` CHECK (`participant_count` > 0);

ALTER TABLE `reservation_occupancy`
  DROP INDEX `uk_reservation_occupancy_slot`,
  ADD COLUMN `rule_id` bigint DEFAULT NULL,
  ADD COLUMN `resource_category_snapshot` varchar(32) NOT NULL DEFAULT 'SPORTS_FIELD',
  ADD COLUMN `booking_mode` varchar(32) NOT NULL DEFAULT 'EXCLUSIVE',
  ADD COLUMN `rule_version_snapshot` int NOT NULL DEFAULT '1',
  ADD COLUMN `quantity` int NOT NULL DEFAULT '1',
  ADD COLUMN `allocation_snapshot_json` json DEFAULT NULL,
  ADD COLUMN `released_at` datetime DEFAULT NULL,
  ADD COLUMN `release_reason` varchar(255) DEFAULT NULL,
  ADD UNIQUE KEY `uk_reservation_occupancy_active` (`reservation_id`,`active_flag`),
  ADD KEY `idx_reservation_occupancy_space_time_active`
    (`space_id`,`slot_date`,`start_time`,`end_time`,`active_flag`),
  ADD KEY `idx_reservation_occupancy_rule` (`rule_id`,`booking_mode`),
  ADD CONSTRAINT `chk_reservation_occupancy_quantity` CHECK (`quantity` > 0),
  ADD CONSTRAINT `chk_reservation_occupancy_mode`
    CHECK (`booking_mode` IN ('EXCLUSIVE','CAPACITY','ASSIGNED_SEAT'));

CREATE TABLE `reservation_occupancy_seat` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `occupancy_id` bigint NOT NULL,
  `reservation_id` bigint NOT NULL,
  `seat_id` bigint NOT NULL,
  `seat_code_snapshot` varchar(64) NOT NULL,
  `seat_label_snapshot` varchar(128) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservation_occupancy_seat` (`occupancy_id`,`seat_id`),
  KEY `idx_reservation_occupancy_seat_reservation` (`reservation_id`),
  KEY `idx_reservation_occupancy_seat_conflict` (`seat_id`,`occupancy_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `venue_resource_rule` (
  `space_id`, `resource_category`, `booking_mode`, `capacity_limit`, `version_no`,
  `status`, `active_flag`, `effective_from`, `reason_text`, `create_time`, `update_time`
)
SELECT
  space.`id`, 'SPORTS_FIELD', 'EXCLUSIVE', 1, 1,
  'ACTIVE', 1, COALESCE(space.`create_time`, CURRENT_TIMESTAMP),
  'V14 历史兼容：旧空间默认整空间排他 V1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM `venue_space` space
;

UPDATE `reservation` r
JOIN `venue_resource_rule` rule_row
  ON rule_row.`space_id` = r.`space_id`
 AND rule_row.`active_flag` = 1
SET r.`resource_category_snapshot` = 'SPORTS_FIELD',
    r.`booking_mode_snapshot` = 'EXCLUSIVE',
    r.`rule_id_snapshot` = rule_row.`id`,
    r.`rule_version_snapshot` = 1,
    r.`participant_count` = 1,
    r.`allocation_snapshot_json` = JSON_OBJECT(
      'source', 'V1',
      'resourceCategory', 'SPORTS_FIELD',
      'bookingMode', 'EXCLUSIVE',
      'ruleVersion', 1,
      'participantCount', 1
    );

UPDATE `reservation_occupancy` occupancy
JOIN `reservation` reservation_row
  ON reservation_row.`id` = occupancy.`reservation_id`
JOIN `venue_resource_rule` rule_row
  ON rule_row.`space_id` = occupancy.`space_id`
 AND rule_row.`active_flag` = 1
SET occupancy.`rule_id` = rule_row.`id`,
    occupancy.`resource_category_snapshot` = 'SPORTS_FIELD',
    occupancy.`booking_mode` = 'EXCLUSIVE',
    occupancy.`rule_version_snapshot` = 1,
    occupancy.`quantity` = 1,
    occupancy.`allocation_snapshot_json` = JSON_OBJECT(
      'source', 'V1',
      'resourceCategory', 'SPORTS_FIELD',
      'bookingMode', 'EXCLUSIVE',
      'ruleVersion', 1,
      'participantCount', 1
    ),
    occupancy.`released_at` = IF(occupancy.`active_flag` IS NULL, reservation_row.`update_time`, NULL),
    occupancy.`release_reason` = IF(occupancy.`active_flag` IS NULL, 'V14 历史兼容：既有占用已释放', NULL);
