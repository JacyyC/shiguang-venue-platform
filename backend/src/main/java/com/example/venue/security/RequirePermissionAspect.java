package com.example.venue.security;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class RequirePermissionAspect {

    @Before("@annotation(requirePermission)")
    public void requireMethodPermission(RequirePermission requirePermission) {
        requireAuthority(requirePermission.value());
    }

    @Before("@within(requirePermission)")
    public void requireTypePermission(RequirePermission requirePermission) {
        requireAuthority(requirePermission.value());
    }

    private void requireAuthority(String requiredAuthority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean granted = requiredAuthority != null
            && !requiredAuthority.isBlank()
            && authentication != null
            && authentication.isAuthenticated()
            && authentication.getAuthorities().stream()
                .anyMatch(authority -> requiredAuthority.equals(authority.getAuthority()));
        if (!granted) {
            throw new AccessDeniedException("无权限访问该资源");
        }
    }
}
