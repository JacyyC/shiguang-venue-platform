package com.example.venue.placement.source;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Source Reader 注册表。Reader 实现由各来源域提供并注入，宣传域只通过此注册表按来源类型获取。
 * 未注册某类型的 Reader 时，决策与预览均视为来源不可用，不抛出领域异常。
 */
@Component
public class PlacementSourceReaderRegistry {

    private final Map<PlacementSourceType, PlacementSourceReader> readers =
        new EnumMap<>(PlacementSourceType.class);

    public PlacementSourceReaderRegistry(java.util.List<PlacementSourceReader> registered) {
        if (registered != null) {
            for (PlacementSourceReader reader : registered) {
                if (reader != null && reader.sourceType() != null) {
                    readers.put(reader.sourceType(), reader);
                }
            }
        }
    }

    public Optional<PlacementSourceReader> find(PlacementSourceType sourceType) {
        return Optional.ofNullable(readers.get(sourceType));
    }
}
