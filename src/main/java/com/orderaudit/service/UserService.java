package com.orderaudit.service;

import com.orderaudit.domain.AppUser;
import com.orderaudit.domain.Role;
import com.orderaudit.repository.AppUserRepository;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates and maintains login accounts. Passwords are hashed here and never stored or logged in clear. */
@Service
public class UserService {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,50}");

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserService(AppUserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Transactional
    public AppUser create(String username, String displayName, String password, Role role, String createdBy) {
        if (username == null || !USERNAME.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException("Username must be 3-50 letters, digits, dot, dash or underscore");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 100) {
            throw new IllegalArgumentException("Display name is required (at most 100 characters)");
        }
        if (role == null) {
            throw new IllegalArgumentException("Role is required");
        }
        requireOwnerToManageOwner(role, createdBy, "create");
        String name = username.trim();
        if (users.existsByUsernameIgnoreCase(name)) {
            throw new IllegalArgumentException("Username " + name + " is already taken");
        }
        PasswordPolicy.check(password, name);
        AppUser saved = users.save(new AppUser(name, displayName.trim(), encoder.encode(password), role));
        audit.record(createdBy, "USER_CREATED", "USER", saved.getId(), name + " as " + role);
        return saved;
    }

    @Transactional
    public void changePassword(long userId, String newPassword, String changedBy) {
        AppUser user = load(userId);
        boolean ownPassword = user.getUsername().equalsIgnoreCase(changedBy == null ? "" : changedBy.trim());
        if (!ownPassword) {
            requireOwnerToManageOwner(user.getRole(), changedBy, "reset the password of");
        }
        PasswordPolicy.check(newPassword, user.getUsername());
        user.changePasswordHash(encoder.encode(newPassword));
        audit.record(changedBy, "PASSWORD_CHANGED", "USER", userId, user.getUsername());   // never the password
    }

    @Transactional
    public void setEnabled(long userId, boolean enabled, String changedBy) {
        AppUser user = load(userId);
        if (enabled) {
            requireOwnerToManageOwner(user.getRole(), changedBy, "enable");
            user.enable();
        } else {
            if (user.getUsername().equalsIgnoreCase(changedBy)) {
                throw new IllegalArgumentException("You cannot disable your own account");
            }
            requireOwnerToManageOwner(user.getRole(), changedBy, "disable");
            if (user.getRole() == Role.OWNER && user.isEnabled() && users.countByRoleAndEnabledTrue(Role.OWNER) <= 1) {
                throw new IllegalArgumentException("This is the last active owner account; it cannot be disabled");
            }
            user.disable();
        }
        audit.record(changedBy, enabled ? "USER_ENABLED" : "USER_DISABLED", "USER", userId, user.getUsername());
    }

    @Transactional(readOnly = true)
    public List<AppUser> all() {
        return users.findAllByOrderByUsernameAsc();
    }

    /**
     * Owner accounts are protected (audit finding C2): only an OWNER may create one, reset its password,
     * or enable / disable it. Otherwise an admin could make himself an owner, or take over the owner's login.
     * On the website the decision uses the logged-in user's own roles. With nobody logged in (the start-up
     * seeder creating the first owner, plain service code) the named actor is looked up instead: a real user
     * must be an owner, a name that is not a user at all ("seed") is the system itself.
     */
    private void requireOwnerToManageOwner(Role targetRole, String actor, String action) {
        if (targetRole != Role.OWNER) {
            return;
        }
        boolean allowed = com.orderaudit.security.CurrentUser.hasRole("OWNER").orElseGet(() ->
                users.findByUsernameIgnoreCase(actor == null ? "" : actor.trim())
                        .map(me -> me.getRole() == Role.OWNER)
                        .orElse(true));
        if (!allowed) {
            throw new IllegalArgumentException("Only an owner can " + action + " an owner account");
        }
    }

    private AppUser load(long userId) {
        return users.findById(userId).orElseThrow(() -> new IllegalArgumentException("User " + userId + " does not exist"));
    }
}
