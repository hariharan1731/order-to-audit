package com.orderaudit.security;

import com.orderaudit.service.AuditService;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes every sign-in and every failed sign-in to the audit log (who, when, from which address, why it failed).
 * Spring Security announces these as events; this class just listens. The password is never logged.
 */
@Component
public class LoginAuditListener {

    private final AuditService audit;

    public LoginAuditListener(AuditService audit) {
        this.audit = audit;
    }

    @EventListener
    @Transactional
    public void onSuccess(AuthenticationSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        audit.record(actorOf(auth), "LOGIN_SUCCEEDED", "USER", null, "signed in" + from(auth));
    }

    @EventListener
    @Transactional
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        Authentication auth = event.getAuthentication();
        audit.record(actorOf(auth), "LOGIN_FAILED", "USER", null, reasonOf(event.getException()) + from(auth));
    }

    /** The name that was typed (or the signed-in user); never empty and never longer than the audit column. */
    private static String actorOf(Authentication auth) {
        String name = auth == null || auth.getName() == null ? "" : auth.getName().trim();
        if (name.isEmpty()) {
            return "(blank)";
        }
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private static String reasonOf(Exception e) {
        if (e instanceof DisabledException) {
            return "account is disabled";
        }
        if (e instanceof LockedException) {
            return "account is locked";
        }
        if (e instanceof BadCredentialsException) {
            return "wrong password or unknown user";
        }
        return "sign-in refused (" + e.getClass().getSimpleName() + ")";
    }

    private static String from(Authentication auth) {
        if (auth != null && auth.getDetails() instanceof WebAuthenticationDetails details
                && details.getRemoteAddress() != null) {
            return " from " + details.getRemoteAddress();
        }
        return "";
    }
}
