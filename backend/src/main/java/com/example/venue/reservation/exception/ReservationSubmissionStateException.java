package com.example.venue.reservation.exception;

import com.example.venue.common.BusinessException;
import com.example.venue.common.ResultCode;
import com.example.venue.reservation.enums.ReservationSubmissionState;

/**
 * 预约提交请求身份的冲突。携带 reasonCode，前端据此与库存冲突区分。
 */
public class ReservationSubmissionStateException extends BusinessException {
    private final ReservationSubmissionState state;

    public ReservationSubmissionStateException(ReservationSubmissionState state, String message) {
        super(ResultCode.CONFLICT, message);
        this.state = state;
    }

    public ReservationSubmissionState getState() {
        return state;
    }
}
