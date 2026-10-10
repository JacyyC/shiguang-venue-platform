CREATE TABLE `site_shift_assignment_guard` (
  `assigned_user_id` bigint NOT NULL,
  `role_code` varchar(64) NOT NULL,
  PRIMARY KEY (`assigned_user_id`, `role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
