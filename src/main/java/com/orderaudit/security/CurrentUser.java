package com.orderaudit.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who is logged in right now. "system" when there is no login (seed data, background work, plain service tests). */
public final class CurrentUser {

    private CurrentUser() {
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
