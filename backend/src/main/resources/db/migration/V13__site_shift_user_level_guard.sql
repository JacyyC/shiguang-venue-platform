-- A26-P2-11：排班重叠是人员级约束，守卫键不能再按岗位分片。
-- 历史同一人员可能已有多条岗位守卫，先保留字典序最小的一条，再把主键收敛为 assigned_user_id。
DELETE duplicate_guard
FROM `site_shift_assignment_guard` duplicate_guard
JOIN `site_shift_assignment_guard` retained_guard
  ON retained_guard.`assigned_user_id` = duplicate_guard.`assigned_user_id`
 AND retained_guard.`role_code` < duplicate_guard.`role_code`;

ALTER TABLE `site_shift_assignment_guard`
  DROP PRIMARY KEY,
  DROP COLUMN `role_code`,
  ADD PRIMARY KEY (`assigned_user_id`);
