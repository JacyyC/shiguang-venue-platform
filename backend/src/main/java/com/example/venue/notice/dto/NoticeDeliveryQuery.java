package com.example.venue.notice.dto;

import com.example.venue.common.PageQuery;

import java.time.LocalDateTime;
import java.util.Locale;

public class NoticeDeliveryQuery extends PageQuery {
    public String providerEnv;
    public String providerName;
    public String channel;
    public String status;
    public String deliveryStatus;
    public String readStatus;
    public Long batchId;
    public Long noticeId;
    public Long experimentId;
    public Long userId;
    public LocalDateTime startTime;
    public LocalDateTime endTime;

    public String providerEnv() {
        return normalize(providerEnv);
    }

    public String providerName() {
        return normalize(providerName);
    }

    public String getProviderEnv() {
        return providerEnv;
    }

    public void setProviderEnv(String providerEnv) {
        this.providerEnv = providerEnv;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getNoticeId() {
        return noticeId;
    }

    public void setNoticeId(Long noticeId) {
        this.noticeId = noticeId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(String deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public String getReadStatus() {
        return readStatus;
    }

    public void setReadStatus(String readStatus) {
        this.readStatus = readStatus;
    }

    public Long getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(Long experimentId) {
        this.experimentId = experimentId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public String normalizedStatus() {
        return normalize(status);
    }

    public String normalizedChannel() {
        return normalize(channel);
    }

    public String normalizedDeliveryStatus() {
        return normalize(deliveryStatus);
    }

    public String normalizedReadStatus() {
        return normalize(readStatus);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
