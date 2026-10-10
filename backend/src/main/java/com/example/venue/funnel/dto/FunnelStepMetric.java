package com.example.venue.funnel.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

public class FunnelStepMetric {
    public String eventName;
    public String label;
    /** 计量状态（F-22）：区分「本口径下真实计数」与「该步骤在此口径没有归属事实」。 */
    public FunnelStepMeasurement measurement;
    /** 当前口径的第一个可计量步骤；界面据此显示起点，而不是给起点一个虚假转化率。 */
    public boolean startStep;
    /** 不适用时说明它归属哪个口径，避免读成零转化。 */
    public String notApplicableReason;
    /** NOT_APPLICABLE 时为 null；jackson 显式保留 null，前端不能把空读成 0。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Long count;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public BigDecimal conversionRate;
}
