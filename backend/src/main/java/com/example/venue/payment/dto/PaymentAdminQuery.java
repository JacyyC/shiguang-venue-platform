package com.example.venue.payment.dto;

import com.example.venue.common.PageQuery;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 支付管理端读侧查询（B5-H23）：字段保留 public 供服务层改写默认渠道，
 * 但必须带 setter——Spring MVC 的 @ModelAttribute 绑定走 BeanWrapper（setter 语义），
 * 纯 public 字段会被静默跳过，HTTP 查询串永远到不了 mapper（S04 隔离栈 general log 反例）。
 */
public class PaymentAdminQuery extends PageQuery {
    public String providerEnv;
    public String providerName;
    public String status;
    public String orderNo;
    public Long venueId;
    public LocalDateTime startTime;
    public LocalDateTime endTime;

    public void setProviderEnv(String providerEnv) {
        this.providerEnv = providerEnv;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public void setVenueId(Long venueId) {
        this.venueId = venueId;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public String normalizedProviderEnv() {
        return normalize(providerEnv);
    }

    public String normalizedProviderName() {
        return normalize(providerName);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
