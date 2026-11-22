package com.orderaudit.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who is logged in right now. "system" when there is no login (seed data, background work, plain service tests). */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * True/false when someone is logged in (decided by their roles), empty when nobody is (seed data,
     * background work, plain service tests).
     */
    public static java.util.Optional<Boolean> hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return java.util.Optional.empty();
        }
        String wanted = "ROLE_" + role;
        return java.util.Optional.of(auth.getAuthorities().stream().anyMatch(a -> wanted.equals(a.getAuthority())));
    }

    public static String name() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || auth.getName().isBlank()
                || "anonymousUser".equals(auth.getName())) {
            return "system";
        }
        return auth.getName();
    }
}
