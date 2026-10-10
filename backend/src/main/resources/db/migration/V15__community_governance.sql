-- V2-WAVE-A/S04：建立登录后社区与内容治理的最小权威模型。
-- 用户引用不建立外键，避免 sys_user 物理删除阻断社区保留与去标识化；仅社区域内部
-- 的层级关系使用 RESTRICT 外键。正文清理期限由 retain_until/reason_retain_until 驱动，
-- 不含正文的治理事件至少保留 365 天。

CREATE TABLE `community_group` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_no` varchar(64) NOT NULL,
  `name` varchar(40) DEFAULT NULL,
  `description` varchar(300) DEFAULT NULL,
  `owner_user_id` bigint DEFAULT NULL,
  `visibility` varchar(32) NOT NULL,
  `join_policy` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
  `idempotency_key` varchar(128) NOT NULL,
  `request_hash` varchar(64) NOT NULL,
  `version_no` bigint NOT NULL DEFAULT '0',
  `hidden_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL,
  `retain_until` datetime DEFAULT NULL,
  `purged_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_group_no` (`group_no`),
  UNIQUE KEY `uk_community_group_owner_idempotency` (`owner_user_id`,`idempotency_key`),
  KEY `idx_community_group_discovery` (`status`,`id`),
  KEY `idx_community_group_name` (`status`,`name`,`id`),
  KEY `idx_community_group_owner_status` (`owner_user_id`,`status`,`id`),
  KEY `idx_community_group_retention` (`status`,`retain_until`,`id`),
  CONSTRAINT `chk_community_group_visibility`
    CHECK (`visibility` IN ('PLATFORM_VISIBLE','MEMBERS_ONLY')),
  CONSTRAINT `chk_community_group_join_policy`
    CHECK (`join_policy` IN ('DIRECT','REVIEW_REQUIRED')),
  CONSTRAINT `chk_community_group_status`
    CHECK (`status` IN ('ACTIVE','HIDDEN','DELETED','PURGED')),
  CONSTRAINT `chk_community_group_owner_identity`
    CHECK ((`status` = 'PURGED' AND `owner_user_id` IS NULL)
      OR (`status` <> 'PURGED' AND `owner_user_id` IS NOT NULL)),
  CONSTRAINT `chk_community_group_content`
    CHECK ((`status` = 'PURGED' AND `name` IS NULL AND `description` IS NULL)
      OR (`status` <> 'PURGED'
        AND `name` IS NOT NULL
        AND CHAR_LENGTH(TRIM(`name`)) BETWEEN 1 AND 40
        AND (`description` IS NULL OR CHAR_LENGTH(`description`) <= 300))),
  CONSTRAINT `chk_community_group_idempotency`
    CHECK (CHAR_LENGTH(TRIM(`idempotency_key`)) BETWEEN 1 AND 128
      AND CHAR_LENGTH(TRIM(`request_hash`)) BETWEEN 1 AND 64),
  CONSTRAINT `chk_community_group_hidden`
    CHECK (`status` <> 'HIDDEN'
      OR (`hidden_at` IS NOT NULL
        AND `deleted_at` IS NOT NULL
        AND `retain_until` IS NOT NULL
        AND `retain_until` >= `deleted_at`)),
  CONSTRAINT `chk_community_group_retention`
    CHECK ((`status` NOT IN ('DELETED','PURGED'))
      OR (`deleted_at` IS NOT NULL AND `retain_until` IS NOT NULL
        AND `retain_until` >= `deleted_at`)),
  CONSTRAINT `chk_community_group_purged`
    CHECK ((`status` <> 'PURGED' AND `purged_at` IS NULL)
      OR (`status` = 'PURGED' AND `purged_at` IS NOT NULL
        AND `purged_at` >= `retain_until`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `community_group_member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_id` bigint NOT NULL,
  `user_id` bigint DEFAULT NULL,
  `member_role` varchar(32) NOT NULL DEFAULT 'MEMBER',
  `status` varchar(32) NOT NULL,
  `last_idempotency_key` varchar(128) DEFAULT NULL,
  `last_request_hash` varchar(64) DEFAULT NULL,
  `reviewed_by` bigint DEFAULT NULL,
  `reviewed_at` datetime DEFAULT NULL,
  `joined_at` datetime DEFAULT NULL,
  `left_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `active_owner_group_id` bigint GENERATED ALWAYS AS (
    CASE WHEN `member_role` = 'OWNER' AND `status` = 'ACTIVE' THEN `group_id` ELSE NULL END
  ) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_group_member` (`group_id`,`user_id`),
  UNIQUE KEY `uk_community_group_active_owner` (`active_owner_group_id`),
  KEY `idx_community_group_member_group` (`group_id`,`status`,`id`),
  KEY `idx_community_group_member_user` (`user_id`,`status`,`group_id`),
  KEY `idx_community_group_member_review` (`status`,`create_time`,`id`),
  CONSTRAINT `fk_community_group_member_group`
    FOREIGN KEY (`group_id`) REFERENCES `community_group` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_community_group_member_role`
    CHECK (`member_role` IN ('OWNER','ADMIN','MEMBER')),
  CONSTRAINT `chk_community_group_member_status`
    CHECK (`status` IN ('PENDING','ACTIVE','REJECTED','LEFT','REMOVED')),
  CONSTRAINT `chk_community_group_member_identity`
    CHECK (`status` IN ('REJECTED','LEFT','REMOVED') OR `user_id` IS NOT NULL),
  CONSTRAINT `chk_community_group_member_idempotency`
    CHECK ((`last_idempotency_key` IS NULL AND `last_request_hash` IS NULL)
      OR (`last_idempotency_key` IS NOT NULL AND `last_request_hash` IS NOT NULL
        AND CHAR_LENGTH(TRIM(`last_idempotency_key`)) BETWEEN 1 AND 128
        AND CHAR_LENGTH(TRIM(`last_request_hash`)) BETWEEN 1 AND 64)),
  CONSTRAINT `chk_community_group_member_timeline`
    CHECK ((`status` = 'PENDING' AND `joined_at` IS NULL AND `left_at` IS NULL)
      OR (`status` = 'ACTIVE' AND `joined_at` IS NOT NULL AND `left_at` IS NULL)
      OR (`status` = 'REJECTED' AND `reviewed_at` IS NOT NULL
        AND `joined_at` IS NULL AND `left_at` IS NULL)
      OR (`status` IN ('LEFT','REMOVED') AND `joined_at` IS NOT NULL AND `left_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `community_post` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `post_no` varchar(64) NOT NULL,
  `group_id` bigint NOT NULL,
  `author_user_id` bigint DEFAULT NULL,
  `title` varchar(80) DEFAULT NULL,
  `content_text` varchar(5000) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'PUBLISHED',
  `idempotency_key` varchar(128) NOT NULL,
  `request_hash` varchar(64) NOT NULL,
  `version_no` bigint NOT NULL DEFAULT '0',
  `published_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `hidden_by` bigint DEFAULT NULL,
  `hidden_scope` varchar(32) DEFAULT NULL,
  `hidden_reason_code` varchar(64) DEFAULT NULL,
  `hidden_at` datetime DEFAULT NULL,
  `deleted_by` bigint DEFAULT NULL,
  `deletion_reason` varchar(1000) DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL,
  `retain_until` datetime DEFAULT NULL,
  `purged_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_post_no` (`post_no`),
  UNIQUE KEY `uk_community_post_author_idempotency` (`author_user_id`,`idempotency_key`),
  UNIQUE KEY `uk_community_post_id_group` (`id`,`group_id`),
  KEY `idx_community_post_cursor` (`status`,`id`),
  KEY `idx_community_post_group_cursor` (`group_id`,`status`,`id`),
  KEY `idx_community_post_author_cursor` (`author_user_id`,`status`,`id`),
  KEY `idx_community_post_title_cursor` (`status`,`title`,`id`),
  KEY `idx_community_post_retention` (`status`,`retain_until`,`id`),
  CONSTRAINT `fk_community_post_group`
    FOREIGN KEY (`group_id`) REFERENCES `community_group` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_community_post_status`
    CHECK (`status` IN ('PUBLISHED','HIDDEN','DELETED','PURGED')),
  CONSTRAINT `chk_community_post_author_identity`
    CHECK ((`status` = 'PURGED' AND `author_user_id` IS NULL)
      OR (`status` <> 'PURGED' AND `author_user_id` IS NOT NULL)),
  CONSTRAINT `chk_community_post_content`
    CHECK ((`status` = 'PURGED' AND `title` IS NULL AND `content_text` IS NULL)
      OR (`status` <> 'PURGED'
        AND `title` IS NOT NULL AND CHAR_LENGTH(TRIM(`title`)) BETWEEN 1 AND 80
        AND `content_text` IS NOT NULL AND CHAR_LENGTH(TRIM(`content_text`)) BETWEEN 1 AND 5000)),
  CONSTRAINT `chk_community_post_idempotency`
    CHECK (CHAR_LENGTH(TRIM(`idempotency_key`)) BETWEEN 1 AND 128
      AND CHAR_LENGTH(TRIM(`request_hash`)) BETWEEN 1 AND 64),
  CONSTRAINT `chk_community_post_hidden`
    CHECK (`status` <> 'HIDDEN'
      OR (`hidden_at` IS NOT NULL
        AND `hidden_scope` IN ('GROUP','PLATFORM')
        AND `hidden_reason_code` IS NOT NULL
        AND `deleted_at` IS NOT NULL
        AND `retain_until` IS NOT NULL
        AND `retain_until` >= `deleted_at`)),
  CONSTRAINT `chk_community_post_retention`
    CHECK ((`status` NOT IN ('DELETED','PURGED'))
      OR (`deleted_at` IS NOT NULL AND `retain_until` IS NOT NULL
        AND `retain_until` >= `deleted_at`
        AND `deletion_reason` IS NOT NULL)),
  CONSTRAINT `chk_community_post_purged`
    CHECK ((`status` <> 'PURGED' AND `purged_at` IS NULL)
      OR (`status` = 'PURGED' AND `purged_at` IS NOT NULL
        AND `purged_at` >= `retain_until`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `community_comment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `comment_no` varchar(64) NOT NULL,
  `post_id` bigint NOT NULL,
  `group_id` bigint NOT NULL,
  `author_user_id` bigint DEFAULT NULL,
  `content_text` varchar(1000) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'PUBLISHED',
  `idempotency_key` varchar(128) NOT NULL,
  `request_hash` varchar(64) NOT NULL,
  `version_no` bigint NOT NULL DEFAULT '0',
  `published_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `hidden_by` bigint DEFAULT NULL,
  `hidden_scope` varchar(32) DEFAULT NULL,
  `hidden_reason_code` varchar(64) DEFAULT NULL,
  `hidden_at` datetime DEFAULT NULL,
  `deleted_by` bigint DEFAULT NULL,
  `deletion_reason` varchar(1000) DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL,
  `retain_until` datetime DEFAULT NULL,
  `purged_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_comment_no` (`comment_no`),
  UNIQUE KEY `uk_community_comment_author_idempotency` (`author_user_id`,`idempotency_key`),
  KEY `idx_community_comment_post_cursor` (`post_id`,`status`,`id`),
  KEY `idx_community_comment_group_cursor` (`group_id`,`status`,`id`),
  KEY `idx_community_comment_author_cursor` (`author_user_id`,`status`,`id`),
  KEY `idx_community_comment_retention` (`status`,`retain_until`,`id`),
  CONSTRAINT `fk_community_comment_post_group`
    FOREIGN KEY (`post_id`,`group_id`) REFERENCES `community_post` (`id`,`group_id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_community_comment_status`
    CHECK (`status` IN ('PUBLISHED','HIDDEN','DELETED','PURGED')),
  CONSTRAINT `chk_community_comment_author_identity`
    CHECK ((`status` = 'PURGED' AND `author_user_id` IS NULL)
      OR (`status` <> 'PURGED' AND `author_user_id` IS NOT NULL)),
  CONSTRAINT `chk_community_comment_content`
    CHECK ((`status` = 'PURGED' AND `content_text` IS NULL)
      OR (`status` <> 'PURGED'
        AND `content_text` IS NOT NULL AND CHAR_LENGTH(TRIM(`content_text`)) BETWEEN 1 AND 1000)),
  CONSTRAINT `chk_community_comment_idempotency`
    CHECK (CHAR_LENGTH(TRIM(`idempotency_key`)) BETWEEN 1 AND 128
      AND CHAR_LENGTH(TRIM(`request_hash`)) BETWEEN 1 AND 64),
  CONSTRAINT `chk_community_comment_hidden`
    CHECK (`status` <> 'HIDDEN'
      OR (`hidden_at` IS NOT NULL
        AND `hidden_scope` IN ('GROUP','PLATFORM')
        AND `hidden_reason_code` IS NOT NULL
        AND `deleted_at` IS NOT NULL
        AND `retain_until` IS NOT NULL
        AND `retain_until` >= `deleted_at`)),
  CONSTRAINT `chk_community_comment_retention`
    CHECK ((`status` NOT IN ('DELETED','PURGED'))
      OR (`deleted_at` IS NOT NULL AND `retain_until` IS NOT NULL
        AND `retain_until` >= `deleted_at`
        AND `deletion_reason` IS NOT NULL)),
  CONSTRAINT `chk_community_comment_purged`
    CHECK ((`status` <> 'PURGED' AND `purged_at` IS NULL)
      OR (`status` = 'PURGED' AND `purged_at` IS NOT NULL
        AND `purged_at` >= `retain_until`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `community_user_block` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `blocker_user_id` bigint DEFAULT NULL,
  `blocked_user_id` bigint DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
  `last_idempotency_key` varchar(128) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `removed_at` datetime DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_user_block_pair` (`blocker_user_id`,`blocked_user_id`),
  KEY `idx_community_user_block_forward` (`blocker_user_id`,`status`,`blocked_user_id`),
  KEY `idx_community_user_block_reverse` (`blocked_user_id`,`status`,`blocker_user_id`),
  KEY `idx_community_user_block_cursor` (`blocker_user_id`,`status`,`id`),
  CONSTRAINT `chk_community_user_block_distinct`
    CHECK (`blocker_user_id` <> `blocked_user_id`),
  CONSTRAINT `chk_community_user_block_status`
    CHECK (`status` IN ('ACTIVE','REMOVED')),
  CONSTRAINT `chk_community_user_block_identity`
    CHECK (`status` = 'REMOVED'
      OR (`blocker_user_id` IS NOT NULL AND `blocked_user_id` IS NOT NULL)),
  CONSTRAINT `chk_community_user_block_idempotency`
    CHECK (CHAR_LENGTH(TRIM(`last_idempotency_key`)) BETWEEN 1 AND 128),
  CONSTRAINT `chk_community_user_block_timeline`
    CHECK ((`status` = 'ACTIVE' AND `removed_at` IS NULL)
      OR (`status` = 'REMOVED' AND `removed_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `community_report` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `report_no` varchar(64) NOT NULL,
  `reporter_user_id` bigint DEFAULT NULL,
  `target_type` varchar(32) NOT NULL,
  `target_id` bigint NOT NULL,
  `reason_text` varchar(1000) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'OPEN',
  `idempotency_key` varchar(128) NOT NULL,
  `request_hash` varchar(64) NOT NULL,
  `assigned_to` bigint DEFAULT NULL,
  `decision_code` varchar(64) DEFAULT NULL,
  `internal_note` varchar(1000) DEFAULT NULL,
  `closed_at` datetime DEFAULT NULL,
  `reason_retain_until` datetime DEFAULT NULL,
  `reason_purged_at` datetime DEFAULT NULL,
  `minimal_retain_until` datetime NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_report_no` (`report_no`),
  UNIQUE KEY `uk_community_reporter_idempotency` (`reporter_user_id`,`idempotency_key`),
  KEY `idx_community_report_queue` (`status`,`id`),
  KEY `idx_community_report_target` (`target_type`,`target_id`,`status`,`id`),
  KEY `idx_community_report_reporter` (`reporter_user_id`,`status`,`id`),
  KEY `idx_community_report_assignee` (`assigned_to`,`status`,`id`),
  KEY `idx_community_report_reason_retention` (`reason_retain_until`,`reason_purged_at`,`id`),
  KEY `idx_community_report_minimal_retention` (`minimal_retain_until`,`id`),
  CONSTRAINT `chk_community_report_target_type`
    CHECK (`target_type` IN ('GROUP','POST','COMMENT')),
  CONSTRAINT `chk_community_report_status`
    CHECK (`status` IN ('OPEN','IN_REVIEW','RESOLVED','DISMISSED')),
  CONSTRAINT `chk_community_report_reporter_identity`
    CHECK (`status` IN ('RESOLVED','DISMISSED') OR `reporter_user_id` IS NOT NULL),
  CONSTRAINT `chk_community_report_reason`
    CHECK ((`reason_purged_at` IS NULL
        AND `reason_text` IS NOT NULL
        AND CHAR_LENGTH(TRIM(`reason_text`)) BETWEEN 1 AND 1000)
      OR (`reason_purged_at` IS NOT NULL
        AND `reason_text` IS NULL AND `internal_note` IS NULL
        AND `reason_retain_until` IS NOT NULL
        AND `reason_purged_at` >= `reason_retain_until`)),
  CONSTRAINT `chk_community_report_idempotency`
    CHECK (CHAR_LENGTH(TRIM(`idempotency_key`)) BETWEEN 1 AND 128
      AND CHAR_LENGTH(TRIM(`request_hash`)) BETWEEN 1 AND 64),
  CONSTRAINT `chk_community_report_closed`
    CHECK ((`status` IN ('OPEN','IN_REVIEW')
        AND `closed_at` IS NULL AND `reason_retain_until` IS NULL
        AND `reason_purged_at` IS NULL)
      OR (`status` IN ('RESOLVED','DISMISSED')
        AND `closed_at` IS NOT NULL AND `decision_code` IS NOT NULL
        AND `reason_retain_until` IS NOT NULL
        AND `reason_retain_until` >= DATE_ADD(`closed_at`, INTERVAL 180 DAY))),
  CONSTRAINT `chk_community_report_minimal_retention`
    CHECK (`minimal_retain_until` >= DATE_ADD(`create_time`, INTERVAL 365 DAY))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `community_governance_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_no` varchar(64) NOT NULL,
  `event_type` varchar(64) NOT NULL,
  `subject_type` varchar(32) NOT NULL,
  `subject_id` bigint NOT NULL,
  `report_id` bigint DEFAULT NULL,
  `actor_user_id` bigint DEFAULT NULL,
  `actor_scope` varchar(32) NOT NULL,
  `source_type` varchar(32) NOT NULL,
  `from_status` varchar(32) DEFAULT NULL,
  `to_status` varchar(32) DEFAULT NULL,
  `reason_code` varchar(64) DEFAULT NULL,
  `reason_summary` varchar(500) DEFAULT NULL,
  `result_status` varchar(32) NOT NULL,
  `idempotency_key` varchar(128) NOT NULL,
  `metadata_json` json NOT NULL,
  `occurred_at` datetime NOT NULL,
  `retain_until` datetime NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_governance_event_no` (`event_no`),
  UNIQUE KEY `uk_community_governance_event_idempotency`
    (`source_type`,`event_type`,`idempotency_key`),
  KEY `idx_community_governance_event_subject`
    (`subject_type`,`subject_id`,`id`),
  KEY `idx_community_governance_event_report` (`report_id`,`id`),
  KEY `idx_community_governance_event_actor`
    (`actor_user_id`,`event_type`,`occurred_at`,`id`),
  KEY `idx_community_governance_event_retention` (`retain_until`,`id`),
  CONSTRAINT `fk_community_governance_event_report`
    FOREIGN KEY (`report_id`) REFERENCES `community_report` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_community_governance_event_type`
    CHECK (CHAR_LENGTH(TRIM(`event_type`)) BETWEEN 1 AND 64),
  CONSTRAINT `chk_community_governance_event_subject`
    CHECK (`subject_type` IN ('GROUP','MEMBER','POST','COMMENT','BLOCK','REPORT','USER')),
  CONSTRAINT `chk_community_governance_event_actor_scope`
    CHECK (`actor_scope` IN ('SELF','GROUP','CONTENT','SYSTEM')),
  CONSTRAINT `chk_community_governance_event_source`
    CHECK (`source_type` IN ('USER','GROUP_ADMIN','CONTENT_OPERATOR','SYSTEM_ADMIN','SYSTEM','SCHEDULED_JOB')),
  CONSTRAINT `chk_community_governance_event_result`
    CHECK (`result_status` IN ('SUCCEEDED','REJECTED','FAILED')),
  CONSTRAINT `chk_community_governance_event_idempotency`
    CHECK (CHAR_LENGTH(TRIM(`idempotency_key`)) BETWEEN 1 AND 128),
  CONSTRAINT `chk_community_governance_event_reason`
    CHECK (`reason_summary` IS NULL OR CHAR_LENGTH(`reason_summary`) <= 500),
  CONSTRAINT `chk_community_governance_event_retention`
    CHECK (`retain_until` >= DATE_ADD(`occurred_at`, INTERVAL 365 DAY))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `sys_deleted_user_snapshot`
  ADD COLUMN `deidentify_after` datetime DEFAULT NULL,
  ADD COLUMN `deidentified_at` datetime DEFAULT NULL,
  ADD KEY `idx_deleted_user_snapshot_deidentify` (`deidentify_after`,`deidentified_at`,`id`),
  ADD CONSTRAINT `chk_deleted_user_snapshot_deidentify`
    CHECK (`deidentified_at` IS NULL
      OR (`deidentify_after` IS NOT NULL AND `deidentified_at` >= `deidentify_after`));

-- 登录用户社区入口挂在既有用户端根菜单下，三个既有角色均可进入；所有业务读取仍由
-- 后端登录态、成员可见性和双向屏蔽规则继续收窄。
INSERT INTO `sys_menu` (
  `menu_name`, `parent_id`, `path`, `component`, `icon`, `perms`, `menu_type`,
  `sort_order`, `visible`, `status`, `create_time`, `update_time`, `deleted`
)
SELECT
  '社区', user_root.`id`, '/community', 'CommunityHome', 'MessagesSquare',
  'community:access', 'MENU', 9, 'Y', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM (
  SELECT MIN(`id`) AS `id`
  FROM `sys_menu`
  WHERE `parent_id` = 0
    AND `perms` = 'user:view'
    AND `menu_type` = 'MENU'
    AND `deleted` = 0
) user_root
WHERE user_root.`id` IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `sys_menu` existing_menu
    WHERE existing_menu.`parent_id` = user_root.`id`
      AND existing_menu.`path` = '/community'
      AND existing_menu.`perms` = 'community:access'
      AND existing_menu.`menu_type` = 'MENU'
      AND existing_menu.`deleted` = 0
  );

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT existing_role.`id`, permission_menu.`id`
FROM `sys_role` existing_role
JOIN `sys_menu` user_root
  ON user_root.`parent_id` = 0
 AND user_root.`perms` = 'user:view'
 AND user_root.`menu_type` = 'MENU'
 AND user_root.`deleted` = 0
JOIN `sys_menu` permission_menu
  ON (permission_menu.`id` = user_root.`id` OR permission_menu.`parent_id` = user_root.`id`)
 AND permission_menu.`perms` IN ('user:view','community:access')
 AND permission_menu.`deleted` = 0
WHERE existing_role.`role_key` IN ('USER','VENUE_ADMIN','SYSTEM_ADMIN')
  AND existing_role.`status` = 'ACTIVE'
  AND existing_role.`deleted` = 0
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_menu` existing_relation
    WHERE existing_relation.`role_id` = existing_role.`id`
      AND existing_relation.`menu_id` = permission_menu.`id`
  );

-- 内容运营入口和权限使用自然键定位，不依赖 V2 固定 ID，也不新增固定第四角色。
INSERT INTO `sys_menu` (
  `menu_name`, `parent_id`, `path`, `component`, `icon`, `perms`, `menu_type`,
  `sort_order`, `visible`, `status`, `create_time`, `update_time`, `deleted`
)
SELECT
  '内容运营后台', 0, '/content-admin', 'ContentAdminDashboard', 'ShieldCheck',
  'content-admin:view', 'MENU', 30, 'N', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_menu`
  WHERE `parent_id` = 0
    AND `path` = '/content-admin'
    AND `perms` = 'content-admin:view'
    AND `menu_type` = 'MENU'
    AND `deleted` = 0
);

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
  SELECT '举报审核' AS `menu_name`, 'community:report:review' AS `perms`, 1 AS `sort_order`
  UNION ALL SELECT '内容处置', 'community:content:moderate', 2
  UNION ALL SELECT '内容恢复', 'community:content:restore', 3
  UNION ALL SELECT '治理审计', 'community:audit:view', 4
) permission_row
WHERE root_menu.`id` IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `sys_menu` existing_menu
    WHERE existing_menu.`parent_id` = root_menu.`id`
      AND existing_menu.`perms` = permission_row.`perms`
      AND existing_menu.`menu_type` = 'BUTTON'
      AND existing_menu.`deleted` = 0
  );

-- SYSTEM_ADMIN 默认仅获得隐藏入口和审计查看；审核、处置和恢复仍需动态角色单独授权。
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
  ON (permission_menu.`id` = root_menu.`id` OR permission_menu.`parent_id` = root_menu.`id`)
 AND permission_menu.`perms` IN ('content-admin:view','community:audit:view')
 AND permission_menu.`deleted` = 0
WHERE system_role.`role_key` = 'SYSTEM_ADMIN'
  AND system_role.`status` = 'ACTIVE'
  AND system_role.`deleted` = 0
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_menu` existing_relation
    WHERE existing_relation.`role_id` = system_role.`id`
      AND existing_relation.`menu_id` = permission_menu.`id`
  );
