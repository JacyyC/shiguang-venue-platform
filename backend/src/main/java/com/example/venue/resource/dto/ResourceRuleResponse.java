package com.example.venue.resource.dto;

import com.example.venue.resource.entity.VenueResourceRule;
import com.example.venue.venue.entity.VenueSpace;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资源规则响应。
 *
 * <p>{@code capacityLimit} 是资源规则的可售单位（EXCLUSIVE 恒为 1，表示一笔整场占用）；
 * {@code physicalCapacity} 是 {@code venue_space.capacity}，即场地物理承载人数。
 * 两者职责不同、不自动同步：前者决定「能卖出几份」，后者决定「能到场几人」。
 * F-01（SHIGUANG-WAVE-01-REMEDIATION-20260916 S04）以物理容量约束预约人数上限，
 * 并通过该字段让客户端在提交前就能提示上限，而不是只能等 400。
 */
public record ResourceRuleResponse(
    Long id,
    Long spaceId,
    String resourceCategory,
    String bookingMode,
    Integer capacityLimit,
    Integer physicalCapacity,
    Integer versionNo,
    String status,
    LocalDateTime effectiveFrom,
    List<ResourceSeatResponse> seats
) {
    public static ResourceRuleResponse of(VenueResourceRule rule, List<ResourceSeatResponse> seats) {
        return of(rule, seats, null);
    }

    public static ResourceRuleResponse of(VenueResourceRule rule, List<ResourceSeatResponse> seats,
                                          VenueSpace space) {
        return new ResourceRuleResponse(
            rule.id,
            rule.spaceId,
            rule.resourceCategory,
            rule.bookingMode,
            rule.capacityLimit,
            space == null ? null : space.capacity,
            rule.versionNo,
            rule.status,
            rule.effectiveFrom,
            seats == null ? List.of() : List.copyOf(seats)
        );
    }
}
