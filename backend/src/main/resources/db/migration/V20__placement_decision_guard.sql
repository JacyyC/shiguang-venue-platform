-- S09：为每个用户/版位/候选建立持久化 guard 行。
-- 空范围的 InnoDB 间隙锁可由并发事务同时持有，不能单独串行化首次签发；
-- ON DUPLICATE KEY UPDATE 在同一事务内取得此行的排他锁，MySQL 才是并发权威。
CREATE TABLE `placement_decision_guard` (
  `user_id` bigint NOT NULL,
  `slot_id` bigint NOT NULL,
  `candidate_version_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`, `slot_id`, `candidate_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
