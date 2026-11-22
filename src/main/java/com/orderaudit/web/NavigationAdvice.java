package com.orderaudit.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Adds "who is logged in, what may they open, and where are they now" to every page. */
@ControllerAdvice
public class NavigationAdvice {

    @ModelAttribute("nav")
    public Nav nav(Authentication auth, HttpServletRequest request) {
        String path = request == null ? "" : request.getRequestURI();
        if (auth == null || !auth.isAuthenticated()) {
            return new Nav(null, false, false, false, false, false, false, path);
        }
        boolean owner = has(auth, "OWNER");
        boolean admin = has(auth, "ADMIN") || owner;
        return new Nav(auth.getName(),
                has(auth, "SALESMAN") || admin,
                admin,
                has(auth, "PRODUCTION_SUPERVISOR") || admin,
                has(auth, "ACCOUNTANT") || has(auth, "PRODUCTION_SUPERVISOR") || admin,
                has(auth, "ACCOUNTANT") || admin,
                owner,
                path);
    }

    private static boolean has(Authentication auth, String role) {
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (a.getAuthority().equals("ROLE_" + role)) {
                return true;
            }
        }
        return false;
    }

    /** What the menu may show for the current user, plus the page they are on so the menu can highlight it. */
    public record Nav(String username, boolean sales, boolean admin, boolean production, boolean stock,
                      boolean reports, boolean owner, String path) {

        /**
         * True when the current page belongs to that menu section, so the link can be highlighted.
         * "/" only matches the home page itself, otherwise every page would match it.
         */
        public boolean on(String section) {
            if (path == null) {
                return false;
            }
            if ("/".equals(section)) {
                return "/".equals(path);
            }
            return path.equals(section) || path.startsWith(section + "/");
        }
    }
}
