-- A26-P0-04：客服反馈原文落库
-- submitReview/submitComplaint/writeCaseEvent 当前把脱敏后文本同时写入 content_text 与 content_sanitized，
-- 原文无任何落库路径，正则误吞普通文本时损坏不可恢复。新增 content_raw/note_raw 列存原文（仅审计/管理员可见），
-- 脱敏后文本继续写入 content_text/note_text（向后兼容），前端响应不返回 raw 列（实体字段标 @JsonIgnore）。
-- 列族与 content_text/note_text 一致（text COLLATE utf8mb4_unicode_ci，nullable）。

ALTER TABLE venue_review
  ADD COLUMN content_raw text COLLATE utf8mb4_unicode_ci DEFAULT NULL;

ALTER TABLE customer_complaint
  ADD COLUMN content_raw text COLLATE utf8mb4_unicode_ci DEFAULT NULL;

ALTER TABLE customer_service_case_event
  ADD COLUMN note_raw text COLLATE utf8mb4_unicode_ci DEFAULT NULL;
