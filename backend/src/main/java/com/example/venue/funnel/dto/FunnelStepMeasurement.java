package com.example.venue.funnel.dto;

/**
 * 漏斗步骤的计量状态（F-22）：搜索发生在用户选定场馆之前，场馆口径下天然没有归属事实可计量。
 * NOT_APPLICABLE 的步骤 count/conversionRate 为空，不能用 0 冒充「本场馆零转化」。
 */
public enum FunnelStepMeasurement {
    MEASURED,
    NOT_APPLICABLE
}
