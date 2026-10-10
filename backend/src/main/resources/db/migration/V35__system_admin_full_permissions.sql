-- UX-AUDIT-FIX-20260826/S04（C-04 方案 A）：补齐超级管理员（SYSTEM_ADMIN，role_id=3）的全量菜单权限。
-- 现状：UI 与 API 口径不一——界面按 role_menu 拦截场馆工作台/内容后台/广告管理，而服务端 @PreAuthorize 对部分入口放行。
-- append-only 合同：仅新增缺失的 sys_role_menu 行（WHERE NOT EXISTS 防重），覆盖全部有效 sys_menu 条目；
-- 不 UPDATE、不 DELETE、不改任何既有行或既有迁移。迁移前后差集必须仅为新增行。
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT system_role.`id`, permission_menu.`id`
FROM `sys_role` system_role
JOIN `sys_menu` permission_menu
  ON permission_menu.`deleted` = 0
WHERE system_role.`id` = 3
  AND system_role.`role_key` = 'SYSTEM_ADMIN'
  AND system_role.`status` = 'ACTIVE'
  AND system_role.`deleted` = 0
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_menu` existing_relation
    WHERE existing_relation.`role_id` = system_role.`id`
      AND existing_relation.`menu_id` = permission_menu.`id`
  );
