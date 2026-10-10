package com.example.venue.funnel.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 漏斗总览查询参数（SHIGUANG-BATCH-04-REFACTOR-20260914 S03 / B4-F16）。
 *
 * <p>Spring MVC 的 {@code @ModelAttribute} 绑定只写入可写 JavaBean 属性（{@code DataBinder} →
 * {@code BeanWrapperImpl.isWritableProperty}），纯 public 字段会被静默丢弃。本类原为四个无访问器的
 * public 字段，实测 {@code GET /api/admin/funnel/overview?startDate=…&endDate=…&venueId=…&sourceChannel=…}
 * 四个参数全部绑定为 null，导致日期窗、场馆范围与来源渠道过滤从未到达服务端（总览恒为「近 7 天 +
 * 全部可管理场馆」）。此处补齐访问器与 {@link DateTimeFormat}，并新增
 * {@code TE/funnel/FunnelOverviewQueryBindingTest.java} 用 MVC 真实绑定路径锁定，防止退回该基线状态。
 * 字段名、语义与默认值均未改变。</p>
 */
public class FunnelOverviewQuery {
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    public LocalDate startDate;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    public LocalDate endDate;
    public Long venueId;
    public String sourceChannel;

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public Long getVenueId() {
        return venueId;
    }

    public void setVenueId(Long venueId) {
        this.venueId = venueId;
    }

    public String getSourceChannel() {
        return sourceChannel;
    }

    public void setSourceChannel(String sourceChannel) {
        this.sourceChannel = sourceChannel;
    }
}
