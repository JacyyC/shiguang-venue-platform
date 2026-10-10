-- V2-UX-REMEDIATION/S01：小组类型分区基础。
-- 注册 community_group_category 字典类型并种子 6 条初值词表（管理员后续可经字典维护增删）；
-- community_group 增加 category_code 可空列，存量小组保持 NULL 由前端归「其他」分区。
-- append-only：仅新增迁移，不改既有迁移、不动任何角色授权种子。

INSERT INTO `sys_dict_type` (`id`, `dict_name`, `dict_type`, `status`, `create_time`, `update_time`)
VALUES (92, '小组分类', 'community_group_category', 'ACTIVE', '2026-08-24 00:00:00', '2026-08-24 00:00:00');

INSERT INTO `sys_dict_data` (`id`, `dict_type_id`, `dict_type`, `label`, `value`, `sort_order`, `status`) VALUES
  (355, NULL, 'community_group_category', '学习共读', 'STUDY', 1, 'ACTIVE'),
  (356, NULL, 'community_group_category', '运动户外', 'SPORTS', 2, 'ACTIVE'),
  (357, NULL, 'community_group_category', '艺术创作', 'ART', 3, 'ACTIVE'),
  (358, NULL, 'community_group_category', '桌游推理', 'BOARD_GAME', 4, 'ACTIVE'),
  (359, NULL, 'community_group_category', '亲子同城', 'FAMILY', 5, 'ACTIVE'),
  (360, NULL, 'community_group_category', '职业交流', 'CAREER', 6, 'ACTIVE');

ALTER TABLE `community_group`
  ADD COLUMN `category_code` varchar(32) DEFAULT NULL AFTER `join_policy`,
  ADD KEY `idx_community_group_category` (`status`, `category_code`, `id`);
