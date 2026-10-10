-- A26-P3-01：lifecycle 与改期申请相关 reason_text 加长到 varchar(500)
-- ReservationCancelRequest.reasonText 已 @Size(max=500)，但 reservation_lifecycle 与
-- reservation_lifecycle_event 的 reason_text 仅 varchar(255)，超长即整个取消事务失败且返回泛化 400。
-- 同型问题另见 RescheduleRequest/CheckinVerifyRequest/ManualAttendanceRequest/NoShowDecisionRequest
-- 多字段无 @Size（在 DTO 层补 @Size，schema 层统一加长到 500 与取消原因对齐）。
-- RescheduleRequest.reasonText 落库到 reservation_reschedule_request.reason_text（varchar(255)），
-- 若只加长 lifecycle 两表，@Size(max=500) 仍会在改期申请表落库失败——同型直接耦合，一并加长。
-- review_reason 仍是管理员审核原因，不在 evidence P3-01 范围，保持 varchar(255) 不变。
-- append-only：不改列名、不改默认值、不改 COLLATE，仅扩展长度。列未显式指定 COLLATE，
-- MODIFY COLUMN 不带 COLLATE 时继承表级 utf8mb4_unicode_ci，与原定义一致。

ALTER TABLE reservation_lifecycle
  MODIFY COLUMN reason_text varchar(500) DEFAULT NULL;

ALTER TABLE reservation_lifecycle_event
  MODIFY COLUMN reason_text varchar(500) DEFAULT NULL;

ALTER TABLE reservation_reschedule_request
  MODIFY COLUMN reason_text varchar(500) DEFAULT NULL;
