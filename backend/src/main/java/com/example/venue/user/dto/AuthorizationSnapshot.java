package com.example.venue.user.dto;

import com.example.venue.common.CurrentUser;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The current request's server-authoritative authorization projection.
 */
public record AuthorizationSnapshot(List<String> roles, List<String> permissions) {
    public static AuthorizationSnapshot from(CurrentUser user) {
        return new AuthorizationSnapshot(sorted(user.roles()), sorted(user.permissions()));
    }

    private static List<String> sorted(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(value -> value != null && !value.isBlank())
            .sorted(Comparator.naturalOrder()).toList();
    }
}
