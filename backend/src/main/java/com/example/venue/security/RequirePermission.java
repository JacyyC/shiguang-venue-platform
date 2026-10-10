package com.example.venue.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires the current authentication to contain the declared authority.
 * Enforcement is provided by {@link RequirePermissionAspect} so the annotation does not depend on
 * unsupported method-security annotation templating.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface RequirePermission {
    String value();
}
