package com.orderaudit.security;

import com.orderaudit.domain.AppUser;
import com.orderaudit.repository.AppUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tells Spring Security how to find a user at login: look them up in our app_user table. */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final LoginThrottle throttle;

    public AppUserDetailsService(AppUserRepository users, LoginThrottle throttle) {
        this.users = users;
        this.throttle = throttle;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        AppUser user = users.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));   // same message either way: no hints
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())          // becomes the authority ROLE_<NAME>
                .disabled(!user.isEnabled())
                .accountLocked(throttle.isLocked(user.getUsername()))   // too many wrong passwords recently
                .build();
    }
}
