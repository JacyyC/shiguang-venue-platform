package com.example.venue.usertime.dto;

import java.time.LocalDateTime;

/**
 * 「我的时间」聚合条目（只读）。
 * sourceType：RESERVATION（场地预约）/ ACTIVITY（活动参与）/ COMPANION_REQUEST（搭子邀约参与或待确认申请）。
 * status 一律保留各权威域原始状态，不做跨域归一或涂改。
 */
public class MeTimeItem {
    public String sourceType;
    public Long sourceId;
    public String title;
    public String location;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public String status;
    public Boolean pending;
    public String detailPath;
}
