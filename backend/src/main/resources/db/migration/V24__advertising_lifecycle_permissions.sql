-- V2-R7-ADVERTISING/S02：建立商业广告主、计划、创意版本和追加式审计事实。
-- 本迁移不建立展示、曝光、点击或归因表；这些事实在后续 Stage 通过独立合同追加。
-- 创意只允许纯文本与站内目标枚举，不保存外链、脚本、图片、上传或完整请求载荷。

CREATE TABLE `advertising_advertiser` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `advertiser_no` varchar(64) NOT NULL,
  `advertiser_name` varchar(128) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_advertising_advertiser_no` (`advertiser_no`),
  KEY `idx_advertising_advertiser_status` (`status`,`id`),
  CONSTRAINT `chk_advertising_advertiser_status`
    CHECK (`status` IN ('ACTIVE','SUSPENDED','RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `advertising_campaign` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `campaign_no` varchar(64) NOT NULL,
  `advertiser_id` bigint NOT NULL,
  `venue_id` bigint DEFAULT NULL,
  `campaign_name` varchar(128) NOT NULL,
  `effective_from` datetime NOT NULL,
  `effective_until` datetime NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'DRAFT',
  `created_by` bigint NOT NULL,
  `submitted_by` bigint DEFAULT NULL,
  `submitted_at` datetime DEFAULT NULL,
  `reviewed_by` bigint DEFAULT NULL,
  `reviewed_at` datetime DEFAULT NULL,
  `published_by` bigint DEFAULT NULL,
  `published_at` datetime DEFAULT NULL,
  `paused_by` bigint DEFAULT NULL,
  `paused_at` datetime DEFAULT NULL,
  `retired_by` bigint DEFAULT NULL,
  `retired_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_advertising_campaign_no` (`campaign_no`),
  KEY `idx_advertising_campaign_venue_status` (`venue_id`,`status`,`effective_from`,`effective_until`,`id`),
  KEY `idx_advertising_campaign_advertiser_status` (`advertiser_id`,`status`,`id`),
  CONSTRAINT `chk_advertising_campaign_status`
    CHECK (`status` IN ('DRAFT','PENDING_REVIEW','REJECTED','APPROVED','PUBLISHED','PAUSED','RETIRED')),
  CONSTRAINT `chk_advertising_campaign_window`
    CHECK (`effective_until` > `effective_from`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `advertising_creative_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `creative_no` varchar(64) NOT NULL,
  `campaign_id` bigint NOT NULL,
  `version_no` int NOT NULL,
  `headline` varchar(80) NOT NULL,
  `body_text` varchar(500) NOT NULL,
  `call_to_action` varchar(32) NOT NULL,
  `target_type` varchar(32) NOT NULL,
  `target_id` bigint DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'DRAFT',
  `created_by` bigint NOT NULL,
  `submitted_by` bigint DEFAULT NULL,
  `submitted_at` datetime DEFAULT NULL,
  `reviewed_by` bigint DEFAULT NULL,
  `reviewed_at` datetime DEFAULT NULL,
  `published_by` bigint DEFAULT NULL,
  `published_at` datetime DEFAULT NULL,
  `paused_by` bigint DEFAULT NULL,
  `paused_at` datetime DEFAULT NULL,
  `retired_by` bigint DEFAULT NULL,
  `retired_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_advertising_creative_no` (`creative_no`),
  UNIQUE KEY `uk_advertising_creative_campaign_version` (`campaign_id`,`version_no`),
  KEY `idx_advertising_creative_campaign_status` (`campaign_id`,`status`,`id`),
  KEY `idx_advertising_creative_target` (`target_type`,`target_id`,`status`,`id`),
  CONSTRAINT `chk_advertising_creative_version`
    CHECK (`version_no` > 0),
  CONSTRAINT `chk_advertising_creative_status`
    CHECK (`status` IN ('DRAFT','PENDING_REVIEW','REJECTED','APPROVED','PUBLISHED','PAUSED','RETIRED')),
  CONSTRAINT `chk_advertising_creative_text`
    CHECK (char_length(trim(`headline`)) BETWEEN 1 AND 80
      AND char_length(trim(`body_text`)) BETWEEN 1 AND 500),
  CONSTRAINT `chk_advertising_creative_cta`
    CHECK (`call_to_action` IN ('VIEW','BOOK','LEARN_MORE')),
  CONSTRAINT `chk_advertising_creative_target`
    CHECK ((`target_type` = 'VENUE_LIST' AND `target_id` IS NULL)
      OR (`target_type` IN ('VENUE_DETAIL','ACTIVITY_DETAIL')
        AND `target_id` IS NOT NULL AND `target_id` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `advertising_audit_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `audit_no` varchar(64) NOT NULL,
  `entity_type` varchar(32) NOT NULL,
  `entity_id` bigint NOT NULL,
  `entity_no` varchar(64) NOT NULL,
  `venue_id` bigint DEFAULT NULL,
  `related_entity_type` varchar(32) DEFAULT NULL,
  `related_entity_id` bigint DEFAULT NULL,
  `action` varchar(32) NOT NULL,
  `actor_id` bigint NOT NULL,
  `source_type` varchar(32) NOT NULL DEFAULT 'OPERATOR_API',
  `reason_code` varchar(64) DEFAULT NULL,
  `reason_text` varchar(500) DEFAULT NULL,
  `before_status` varchar(32) DEFAULT NULL,
  `after_status` varchar(32) DEFAULT NULL,
  `result_status` varchar(32) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_advertising_audit_no` (`audit_no`),
  KEY `idx_advertising_audit_entity_time` (`entity_type`,`entity_id`,`create_time`,`id`),
  KEY `idx_advertising_audit_venue_time` (`venue_id`,`create_time`,`id`),
  KEY `idx_advertising_audit_actor_time` (`actor_id`,`create_time`,`id`),
  CONSTRAINT `chk_advertising_audit_entity_type`
    CHECK (`entity_type` IN ('ADVERTISER','CAMPAIGN','CREATIVE')),
  CONSTRAINT `chk_advertising_audit_action`
    CHECK (`action` IN ('CREATE','SUBMIT_REVIEW','APPROVE','REJECT','PUBLISH','PAUSE','RETIRE','SUSPEND')),
  CONSTRAINT `chk_advertising_audit_result`
    CHECK (`result_status` IN ('ACCEPTED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- advertising 叶子权限挂在既有 /content-admin 根菜单下，使用自然键和 NOT EXISTS。
-- 系统管理员默认只获得审计查看；管理、审核、发布和分析必须显式授权。
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
  SELECT '商业广告管理' AS `menu_name`, 'advertising:manage' AS `perms`, 20 AS `sort_order`
  UNION ALL SELECT '商业广告审核', 'advertising:review', 21
  UNION ALL SELECT '商业广告发布', 'advertising:publish', 22
  UNION ALL SELECT '商业广告分析', 'advertising:analysis:view', 23
  UNION ALL SELECT '商业广告审计', 'advertising:audit:view', 24
) permission_row
WHERE root_menu.`id` IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `sys_menu` existing_menu
    WHERE existing_menu.`parent_id` = root_menu.`id`
      AND existing_menu.`perms` = permission_row.`perms`
      AND existing_menu.`menu_type` = 'BUTTON'
      AND existing_menu.`deleted` = 0
  );

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
 AND permission_menu.`perms` IN ('content-admin:view','advertising:audit:view')
 AND permission_menu.`deleted` = 0
WHERE system_role.`role_key` = 'SYSTEM_ADMIN'
  AND system_role.`status` = 'ACTIVE'
  AND system_role.`deleted` = 0
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_menu` existing_relation
    WHERE existing_relation.`role_id` = system_role.`id`
      AND existing_relation.`menu_id` = permission_menu.`id`
  );
