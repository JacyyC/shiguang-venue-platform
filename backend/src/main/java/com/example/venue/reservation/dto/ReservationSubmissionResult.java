package com.example.venue.reservation.dto;

import com.example.venue.reservation.entity.Reservation;

/**
 * 预约提交结果的只读核对视图。
 *
 * <p>区分三种可观察状态，供前端在刷新、返回或响应丢失后恢复：
 * {@code NONE} 表示该键从未产生结果（可安全形成新意图）；
 * {@code PROCESSING} 表示原请求仍在处理中（结果待核对，不能改换键重投）；
 * {@code COMPLETED} 表示已产生预约，携带真实预约实体。</p>
 */
public class ReservationSubmissionResult {
    public String status;
    public Long reservationId;
    public Reservation reservation;

    public static ReservationSubmissionResult none() {
        ReservationSubmissionResult result = new ReservationSubmissionResult();
        result.status = "NONE";
        return result;
    }

    public static ReservationSubmissionResult processing(Long reservationId) {
        ReservationSubmissionResult result = new ReservationSubmissionResult();
        result.status = "PROCESSING";
        result.reservationId = reservationId;
        return result;
    }

    public static ReservationSubmissionResult completed(Reservation reservation) {
        ReservationSubmissionResult result = new ReservationSubmissionResult();
        result.status = "COMPLETED";
        result.reservationId = reservation == null ? null : reservation.id;
        result.reservation = reservation;
        return result;
    }
}
