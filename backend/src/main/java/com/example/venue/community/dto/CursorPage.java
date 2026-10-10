package com.example.venue.community.dto;

import java.util.List;

public record CursorPage<T>(List<T> records, Long nextCursor, boolean hasMore) {
    public static <T> CursorPage<T> of(List<T> records, Long nextCursor, boolean hasMore) {
        return new CursorPage<>(records == null ? List.of() : List.copyOf(records), nextCursor, hasMore);
    }
}
