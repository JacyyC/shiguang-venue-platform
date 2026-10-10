package com.example.venue.security;

import com.example.venue.common.CurrentUser;
import org.springframework.security.core.Authentication;

public final class SecurityContext {
    private SecurityContext() {
    }

    public static CurrentUser currentUser() {
        Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CurrentUser user)) {
            return null;
        }
        return user;
    }

    public static Long currentUserId() {
        CurrentUser user = currentUser();
        return user == null ? null : user.id();
    }

    public static boolean hasAuthority(String authority) {
        Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return authority != null
            && auth != null
            && auth.isAuthenticated()
            && auth.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
