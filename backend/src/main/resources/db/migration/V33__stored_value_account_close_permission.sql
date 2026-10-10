-- S06: register the account close leaf permission without granting any role.
INSERT INTO `sys_menu` (`menu_name`,`parent_id`,`path`,`component`,`icon`,`perms`,`menu_type`,`sort_order`,`visible`,`status`,`create_time`,`update_time`,`deleted`)
SELECT permission_row.menu_name, root_menu.id, NULL, NULL, NULL, permission_row.perms, 'BUTTON', permission_row.sort_order, 'N', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM (SELECT MIN(id) AS id FROM sys_menu WHERE parent_id = 0 AND path = '/system-admin' AND perms = 'system:view' AND menu_type = 'MENU' AND deleted = 0) root_menu
JOIN (
  SELECT 'Stored value account close' AS menu_name, 'stored-value:account:close' AS perms, 84 AS sort_order
) permission_row
WHERE root_menu.id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing_menu WHERE existing_menu.parent_id = root_menu.id AND existing_menu.perms = permission_row.perms AND existing_menu.deleted = 0);
