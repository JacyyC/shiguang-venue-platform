package com.example.venue.funnel.dto;

import jakarta.validation.constraints.Size;

/**
 * A26-P2-24：批量漏斗事件入参约束。
 *
 * <p>批量条数上限 100，避免单请求产生过多 funnel_session/funnel_event 行导致事务过长、
 * 锁竞争与回滚成本过大。{@code @Size} 注解需 Controller 端 {@code @Valid} 触发；
 * {@code FunnelEventService.recordBatch} 同步做防御性条数校验，确保即使 Controller
 * 未加 {@code @Valid} 也能强制拒绝超长批量。</p>
 *
 * <p>各字段长度约束（pagePath/sessionKey/eventStage/resultStatus 等）与 occurredAt 时间窗
 * 校验在 {@code FunnelEventRequest} 字段层与 {@code FunnelEventService.validate} 中
 * 防御性强制执行，避免超长字段触发数据库 500、伪造 occurredAt 投放进管理端任意日期窗口。</p>
 */
public class FunnelEventBatchRequest {
    @Size(max = 100, message = "批量漏斗事件条数不能超过 100")
    public java.util.List<FunnelEventRequest> events;
}
