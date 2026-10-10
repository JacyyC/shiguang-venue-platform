package com.example.venue.checkin.dto;

import com.example.venue.common.PageQuery;

public class CheckinAdminQuery extends PageQuery {
    public Long reservationId;
    public Long userId;
    public Long venueId;
    public Long spaceId;
    public String credentialStatus;
    public String attendanceStatus;
    public String checkinStatus;
    public String noShowStatus;

    // SHIGUANG-WAVE-03-REMEDIATION-20260917 S02 / F-14：Spring MVC @ModelAttribute 只绑定
    // 可写 JavaBean 属性，纯 public 字段会被静默丢弃，导致 HTTP 查询串从未到达
    // ReservationLifecycleService.applyCheckinAdminQuery 的既有条件。按 SiteOpsQuery/PaymentAdminQuery
    // 的仓内先例补齐八个领域字段的访问器；分页属性沿用 PageQuery，不改变字段与排序语义。
    public Long getReservationId() {
        return reservationId;
    }

    public void setReservationId(Long reservationId) {
        this.reservationId = reservationId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getVenueId() {
        return venueId;
    }

    public void setVenueId(Long venueId) {
        this.venueId = venueId;
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(Long spaceId) {
        this.spaceId = spaceId;
    }

    public String getCredentialStatus() {
        return credentialStatus;
    }

    public void setCredentialStatus(String credentialStatus) {
        this.credentialStatus = credentialStatus;
    }

    public String getAttendanceStatus() {
        return attendanceStatus;
    }

    public void setAttendanceStatus(String attendanceStatus) {
        this.attendanceStatus = attendanceStatus;
    }

    public String getCheckinStatus() {
        return checkinStatus;
    }

    public void setCheckinStatus(String checkinStatus) {
        this.checkinStatus = checkinStatus;
    }

    public String getNoShowStatus() {
        return noShowStatus;
    }

    public void setNoShowStatus(String noShowStatus) {
        this.noShowStatus = noShowStatus;
    }
}
