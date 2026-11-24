package com.orderaudit.security;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/** Feeds the sign-in results into LoginThrottle. Only a wrong password counts as a failed guess. */
@Component
public class LoginThrottleListener {

    private final LoginThrottle throttle;

    public LoginThrottleListener(LoginThrottle throttle) {
        this.throttle = throttle;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        throttle.recordSuccess(event.getAuthentication().getName());
    }

    @EventListener
    public void onBadPassword(AuthenticationFailureBadCredentialsEvent event) {
        throttle.recordFailure(event.getAuthentication().getName());
    }
}
