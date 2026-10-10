package com.example.venue.resource.enums;

public enum AllocationConflictCode {
    EXCLUSIVE_OVERLAP,
    CAPACITY_EXCEEDED,
    /**
     * F-01（SHIGUANG-WAVE-01-REMEDIATION-20260916 S04）：请求人数超过场地物理承载人数
     * （{@code venue_space.capacity}）。与 {@link #CAPACITY_EXCEEDED} 不同：后者是资源规则的
     * 可售余量不足，前者是场地物理上限，二者不互相推导。
     */
    PHYSICAL_CAPACITY_EXCEEDED,
    SEAT_ALREADY_ALLOCATED,
    RESOURCE_RULE_UNAVAILABLE
}
