-- S09：为首页决策候选和运营审计列表提供有界、可排序的查询路径。
-- 候选查询按优先级稳定排序后只取固定上限；事件审计按版位和时间窗口倒序取固定上限。
CREATE INDEX `idx_placement_candidate_slot_status_priority`
  ON `placement_candidate_version` (
    `slot_id`, `status`, `priority` DESC, `effective_from` DESC, `id` ASC
  );

CREATE INDEX `idx_placement_event_slot_time`
  ON `placement_event` (`slot_id`, `occurred_at` DESC, `id` DESC);
