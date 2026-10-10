-- S07：为储值真实页面注册入口并绑定最小只读权限。
-- V21 已注册独立叶子权限；本迁移不默认授予退款、冻结、争议或调整权限。

INSERT INTO `sys_menu`
  (`menu_name`,`parent_id`,`path`,`component`,`icon`,`perms`,`menu_type`,`sort_order`,`visible`,`status`,
   `create_time`,`update_time`,`deleted`)
SELECT '储值中心', root.id, '/me/stored-value', 'StoredValue', 'Wallet', 'stored-value:me', 'MENU', 9, 'Y', 'ACTIVE',
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM sys_menu root
WHERE root.parent_id = 0 AND root.path = '/' AND root.perms = 'user:view'
  AND root.menu_type = 'MENU' AND root.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM sys_menu existing
    WHERE existing.path = '/me/stored-value' AND existing.deleted = 0
  );

INSERT INTO `sys_menu`
  (`menu_name`,`parent_id`,`path`,`component`,`icon`,`perms`,`menu_type`,`sort_order`,`visible`,`status`,
   `create_time`,`update_time`,`deleted`)
SELECT '储值财务', root.id, '/system-admin/stored-value', 'SystemStoredValue', 'Wallet',
       'stored-value:finance:view', 'MENU', 30, 'Y', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM sys_menu root
WHERE root.parent_id = 0 AND root.path = '/system-admin' AND root.perms = 'system:view'
  AND root.menu_type = 'MENU' AND root.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM sys_menu existing
    WHERE existing.path = '/system-admin/stored-value' AND existing.deleted = 0
  );

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT role.id, menu.id
FROM sys_role role
JOIN sys_menu menu ON menu.path = '/me/stored-value' AND menu.perms = 'stored-value:me'
  AND menu.menu_type = 'MENU' AND menu.deleted = 0
WHERE role.role_key IN ('USER', 'SYSTEM_ADMIN') AND role.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu existing
    WHERE existing.role_id = role.id AND existing.menu_id = menu.id
  );

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT role.id, menu.id
FROM sys_role role
JOIN sys_menu menu ON menu.path = '/system-admin/stored-value'
  AND menu.perms = 'stored-value:finance:view' AND menu.menu_type = 'MENU' AND menu.deleted = 0
WHERE role.role_key = 'SYSTEM_ADMIN' AND role.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu existing
    WHERE existing.role_id = role.id AND existing.menu_id = menu.id
  );
