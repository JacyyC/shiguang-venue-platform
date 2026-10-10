-- V2-R6-OWNED-PLACEMENT/S08B：建立自营宣传位的版位、候选版本、决策与测量事实。
-- 宣传域只拥有来源类型/来源 ID/候选规则/决策快照/事件事实；来源状态、正文、预算
-- 仍归 notice/promotion/community 域。宣传决策与事件不复用 funnel_event 或公告 outbox，
-- 必须保存独立令牌哈希与规则快照。MySQL 是频控权威，Redis 仅作加速提示。

CREATE TABLE `placement_slot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `slot_code` varchar(32) NOT NULL,
  `slot_name` varchar(64) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ENABLED',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_placement_slot_code` (`slot_code`),
  CONSTRAINT `chk_placement_slot_status` CHECK (`status` IN ('ENABLED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 候选版本只引用来源并保存不可变规则；标题/摘要/深链等白名单字段由决策时 Source Reader
-- 按当前用户实时重校验后写入决策快照，候选版本不存正文副本或跳转 URL。发布后规则不可覆盖，
-- 下线或调整以新版本替代。来源有效时间不在此冗余，由 Reader 在决策时返回。
CREATE TABLE `placement_candidate_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `slot_id` bigint NOT NULL,
  `candidate_no` varchar(64) NOT NULL,
  `source_type` varchar(32) NOT NULL,
  `source_id` bigint NOT NULL,
  `source_version` varchar(64) DEFAULT NULL,
  `effective_from` datetime NOT NULL,
  `effective_until` datetime NOT NULL,
  `audience_type` varchar(32) NOT NULL DEFAULT 'ALL_AUTHENTICATED',
  `audience_level_code` varchar(32) DEFAULT NULL,
  `priority` int NOT NULL DEFAULT 0,
  `frequency_cap_per_user` int NOT NULL DEFAULT 1,
  `frequency_cap_window_hours` int NOT NULL DEFAULT 24,
  `rule_version` varchar(64) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'DRAFT',
  `created_by` bigint DEFAULT NULL,
  `published_by` bigint DEFAULT NULL,
  `published_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_placement_candidate_no` (`candidate_no`),
  KEY `idx_placement_candidate_slot_status`
    (`slot_id`,`status`,`effective_from`,`effective_until`,`id`),
  KEY `idx_placement_candidate_source` (`source_type`,`source_id`,`id`),
  CONSTRAINT `chk_placement_candidate_audience`
    CHECK (`audience_type` IN ('ALL_AUTHENTICATED','MEMBER_LEVEL')),
  CONSTRAINT `chk_placement_candidate_audience_level`
    CHECK ((`audience_type` = 'ALL_AUTHENTICATED' AND `audience_level_code` IS NULL)
      OR (`audience_type` = 'MEMBER_LEVEL' AND `audience_level_code` IS NOT NULL)),
  CONSTRAINT `chk_placement_candidate_status`
    CHECK (`status` IN ('DRAFT','PUBLISHED','PAUSED','RETIRED')),
  CONSTRAINT `chk_placement_candidate_window`
    CHECK (`effective_until` > `effective_from`),
  CONSTRAINT `chk_placement_candidate_frequency`
    CHECK (`frequency_cap_per_user` > 0 AND `frequency_cap_window_hours` > 0),
  CONSTRAINT `chk_placement_candidate_priority`
    CHECK (`priority` BETWEEN 0 AND 9999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 决策快照保存候选版本、受众、频控、来源白名单字段与排序值；令牌为 32 字节密码学随机
-- 不透明值，只返回原值，此表持久化 SHA-256。一次决策与一枚令牌 1:1，过期取签发后 10 分钟、
-- 候选结束、来源结束中的最早值。首条满足阈值的 CLIENT_VIEWABLE 将决策标记为 CONSUMED。
CREATE TABLE `placement_decision` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `decision_no` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `slot_id` bigint NOT NULL,
  `candidate_version_id` bigint NOT NULL,
  `token_hash` char(64) NOT NULL,
  `rule_version_snapshot` varchar(64) NOT NULL,
  `audience_snapshot_json` json NOT NULL,
  `frequency_snapshot_json` json NOT NULL,
  `source_snapshot_json` json NOT NULL,
  `priority_snapshot` int NOT NULL,
  `expires_at` datetime NOT NULL,
  `consumed_at` datetime DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ISSUED',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_placement_decision_no` (`decision_no`),
  UNIQUE KEY `uk_placement_decision_token_hash` (`token_hash`),
  KEY `idx_placement_decision_user_slot_candidate`
    (`user_id`,`slot_id`,`candidate_version_id`,`status`,`expires_at`,`id`),
  KEY `idx_placement_decision_user_slot_status_expires`
    (`user_id`,`slot_id`,`status`,`expires_at`,`id`),
  CONSTRAINT `chk_placement_decision_status`
    CHECK (`status` IN ('ISSUED','CONSUMED','EXPIRED')),
  CONSTRAINT `chk_placement_decision_priority`
    CHECK (`priority_snapshot` BETWEEN 0 AND 9999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 事件只接受 CLIENT_VIEWABLE 与 CLICK。Idempotency-Key 作用域 (decision,eventType)：
-- 同键同载荷返回原结果、同键异载荷冲突；同一决策同类事件最多一条（uk_placement_event_decision_type）。
-- CLICK 可先于曝光，分析记为数据缺口，不反向补写曝光。
CREATE TABLE `placement_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_no` varchar(64) NOT NULL,
  `event_key` varchar(160) NOT NULL,
  `event_type` varchar(32) NOT NULL,
  `decision_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `slot_id` bigint NOT NULL,
  `candidate_version_id` bigint NOT NULL,
  `request_hash` char(64) NOT NULL,
  `visible_ratio` decimal(5,4) DEFAULT NULL,
  `visible_duration_ms` int DEFAULT NULL,
  `result_status` varchar(32) NOT NULL,
  `rejection_code` varchar(64) DEFAULT NULL,
  `occurred_at` datetime NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_placement_event_no` (`event_no`),
  UNIQUE KEY `uk_placement_event_key` (`event_key`),
  UNIQUE KEY `uk_placement_event_decision_type` (`decision_id`,`event_type`),
  KEY `idx_placement_event_decision` (`decision_id`,`occurred_at`,`id`),
  KEY `idx_placement_event_user_candidate_type_time`
    (`user_id`,`candidate_version_id`,`event_type`,`result_status`,`occurred_at`,`id`),
  KEY `idx_placement_event_slot_type_time` (`slot_id`,`event_type`,`occurred_at`,`id`),
  KEY `idx_placement_event_candidate_type_time`
    (`candidate_version_id`,`event_type`,`occurred_at`,`id`),
  CONSTRAINT `chk_placement_event_type`
    CHECK (`event_type` IN ('CLIENT_VIEWABLE','CLICK')),
  CONSTRAINT `chk_placement_event_result`
    CHECK (`result_status` IN ('ACCEPTED','REJECTED','REPLAYED')),
  CONSTRAINT `chk_placement_event_visible`
    CHECK (`event_type` <> 'CLIENT_VIEWABLE'
      OR (`visible_ratio` IS NOT NULL AND `visible_duration_ms` IS NOT NULL)),
  CONSTRAINT `chk_placement_event_ratio`
    CHECK (`visible_ratio` IS NULL OR (`visible_ratio` >= 0.0000 AND `visible_ratio` <= 1.0000)),
  CONSTRAINT `chk_placement_event_duration`
    CHECK (`visible_duration_ms` IS NULL OR `visible_duration_ms` BETWEEN 0 AND 86400000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 首页语义版位固定为 HOME_PRIMARY，仅此一条 seed；运营通过候选版本驱动内容。
INSERT INTO `placement_slot` (`slot_code`, `slot_name`, `status`)
SELECT 'HOME_PRIMARY', '首页自营宣传位', 'ENABLED'
WHERE NOT EXISTS (
  SELECT 1 FROM `placement_slot` existing_slot
  WHERE existing_slot.`slot_code` = 'HOME_PRIMARY'
);

-- 三项 placement 叶子权限挂在既有 /content-admin 根菜单下，使用自然键与 NOT EXISTS，
-- 不固定菜单 ID，也不复用 community:* 或 member-reward:*。content-admin:view 仍只守卫
-- 前端入口，不授予任何宣传 API。
INSERT INTO `sys_menu` (
  `menu_name`, `parent_id`, `path`, `component`, `icon`, `perms`, `menu_type`,
  `sort_order`, `visible`, `status`, `create_time`, `update_time`, `deleted`
)
SELECT
  permission_row.`menu_name`, root_menu.`id`, NULL, NULL, NULL,
  permission_row.`perms`, 'BUTTON', permission_row.`sort_order`, 'N', 'ACTIVE',
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM (
  SELECT MIN(`id`) AS `id`
  FROM `sys_menu`
  WHERE `parent_id` = 0
    AND `path` = '/content-admin'
    AND `perms` = 'content-admin:view'
    AND `menu_type` = 'MENU'
    AND `deleted` = 0
) root_menu
JOIN (
  SELECT '宣传候选管理' AS `menu_name`, 'placement:manage' AS `perms`, 10 AS `sort_order`
  UNION ALL SELECT '宣传发布复核', 'placement:publish', 11
  UNION ALL SELECT '宣传审计查看', 'placement:audit:view', 12
) permission_row
WHERE root_menu.`id` IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `sys_menu` existing_menu
    WHERE existing_menu.`parent_id` = root_menu.`id`
      AND existing_menu.`perms` = permission_row.`perms`
      AND existing_menu.`menu_type` = 'BUTTON'
      AND existing_menu.`deleted` = 0
  );

-- SYSTEM_ADMIN 默认仅获得审计查看；候选管理与发布仍由角色管理显式授权，场馆管理与
-- community:* 均不继承宣传能力。
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT system_role.`id`, permission_menu.`id`
FROM `sys_role` system_role
JOIN `sys_menu` root_menu
  ON root_menu.`parent_id` = 0
 AND root_menu.`path` = '/content-admin'
 AND root_menu.`perms` = 'content-admin:view'
 AND root_menu.`menu_type` = 'MENU'
 AND root_menu.`deleted` = 0
JOIN `sys_menu` permission_menu
  ON permission_menu.`parent_id` = root_menu.`id`
 AND permission_menu.`perms` IN ('content-admin:view','placement:audit:view')
 AND permission_menu.`deleted` = 0
WHERE system_role.`role_key` = 'SYSTEM_ADMIN'
  AND system_role.`status` = 'ACTIVE'
  AND system_role.`deleted` = 0
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_menu` existing_relation
    WHERE existing_relation.`role_id` = system_role.`id`
      AND existing_relation.`menu_id` = permission_menu.`id`
  );
