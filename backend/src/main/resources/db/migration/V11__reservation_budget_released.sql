-- AUD-P2-04：reservation 新增 budget_released 列作为活动预算回补幂等标记。
-- 回补时先 CAS update reservation set budget_released=1 where id=? and budget_released=0，
-- 返回 0 行表示已回补则跳过 releaseCampaignBudget，确保 expireOrder（reservation→EXPIRED）
-- 与 markRefundSuccess（reservation→REFUNDED，rescheduledLateRefund=false）双终态路径
-- 对同一 reservation 只回补一次活动预算，避免重复释放导致活动预算误减。
-- 改期绑定新活动时由 ReservationService.applyPromotionPricing 重置为 0，使新活动预算可在后续终态回补。
alter table reservation add column budget_released tinyint not null default 0;
