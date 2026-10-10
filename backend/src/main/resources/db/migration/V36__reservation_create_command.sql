-- 预约创建请求身份与结果核对。
-- 目的：一次提交意图在响应丢失、超时或同键重试时只产生一条预约及其副作用。
-- 归属：唯一索引包含 user_id，不同用户无法命中彼此的请求。
-- 隐私：只存请求指纹与业务主键，不存联系人等个人信息正文。
CREATE TABLE `reservation_create_command` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `idempotency_key` varchar(128) NOT NULL,
  `request_hash` varchar(64) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'PROCESSING',
  `reservation_id` bigint DEFAULT NULL,
  `result_status` varchar(32) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `completed_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservation_create_command_user_key` (`user_id`,`idempotency_key`),
  KEY `idx_reservation_create_command_create_time` (`create_time`),
  KEY `idx_reservation_create_command_reservation` (`reservation_id`),
  CONSTRAINT `chk_reservation_create_command_status`
    CHECK ((`status` = 'PROCESSING' AND `reservation_id` IS NULL AND `completed_at` IS NULL)
      OR (`status` = 'COMPLETED' AND `reservation_id` IS NOT NULL
        AND `result_status` IS NOT NULL AND `completed_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
