package com.example.venue.reservation.enums;

/**
 * 预约提交请求身份相关的冲突码。
 *
 * <p>与库存冲突区分：这两个码表示「同一次提交意图」的状态问题，不是时段被占用。
 * 前端据此把「结果待核对」与真实业务冲突分开呈现，不能回落为库存冲突。</p>
 */
public enum ReservationSubmissionState {
    /** 同一请求键的首个提交仍在处理中：结果未知，应核对而不是改换键重投。 */
    SUBMISSION_IN_PROGRESS,
    /** 同一请求键被用于不同载荷：明确拒绝，不覆盖也不新建。 */
    SUBMISSION_KEY_REUSED
}
