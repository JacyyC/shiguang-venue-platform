package com.example.venue.placement.source;

import java.time.LocalDateTime;

/**
 * 来源白名单快照。Reader 只返回复核所需的最小字段：来源类型/ID/版本、限长纯文本标题与摘要、
 * 可选受控图片、来源有效时间和服务端生成的站内深链。不含原始正文、受众 JSON、预算、内部
 * metadata 或任意跳转 URL。当前三类来源均无可安全复用的图片字段，首期 image 可为 null，
 * 首页走文本降级。
 */
public record PlacementSourceSnapshot(
    PlacementSourceType sourceType,
    Long sourceId,
    String sourceVersion,
    String title,
    String summary,
    String imageUrl,
    LocalDateTime sourceEffectiveFrom,
    LocalDateTime sourceEffectiveUntil,
    String deepLink
) {
    public PlacementSourceSnapshot {
        if (sourceType == null || sourceId == null) {
            throw new IllegalArgumentException("sourceType and sourceId are required");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (summary == null) {
            summary = "";
        }
        if (deepLink == null || deepLink.isBlank()) {
            throw new IllegalArgumentException("deepLink is required");
        }
    }
}
