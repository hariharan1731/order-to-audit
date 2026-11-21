package com.orderaudit.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Adds "who is logged in and what may they open" to every page, so the menu shows only allowed links. */
@ControllerAdvice
public class NavigationAdvice {

    @ModelAttribute("nav")
    public Nav nav(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return new Nav(null, false, false, false, false, false, false);
        }
        boolean owner = has(auth, "OWNER");
        boolean admin = has(auth, "ADMIN") || owner;
        return new Nav(auth.getName(),
                has(auth, "SALESMAN") || admin,
                admin,
                has(auth, "PRODUCTION_SUPERVISOR") || admin,
                has(auth, "ACCOUNTANT") || has(auth, "PRODUCTION_SUPERVISOR") || admin,
                has(auth, "ACCOUNTANT") || admin,
                owner);
    }

    private static boolean has(Authentication auth, String role) {
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (a.getAuthority().equals("ROLE_" + role)) {
                return true;
            }
        }
        return false;
    }

    /** What the menu may show for the current user. */
    public record Nav(String username, boolean sales, boolean admin, boolean production, boolean stock,
                      boolean reports, boolean owner) {
    }
}
