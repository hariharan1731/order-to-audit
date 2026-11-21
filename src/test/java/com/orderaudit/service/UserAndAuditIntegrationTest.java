package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.AppUser;
import com.orderaudit.domain.Role;
import com.orderaudit.security.AppUserDetailsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL, rolled back after each test: accounts, password hashing, and the append-only audit log. */
@SpringBootTest
@Testcontainers
@Transactional
class UserAndAuditIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired UserService userService;
    @Autowired AuditService audit;
    @Autowired AppUserDetailsService detailsService;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    @Test
    void thePasswordIsStoredAsAHashNeverInClear() {
        AppUser user = userService.create("ravi", "Ravi Kumar", "murukku2026", Role.SALESMAN, "admin");

        assertThat(user.getPasswordHash()).isNotEqualTo("murukku2026").startsWith("{bcrypt}");
        assertThat(encoder.matches("murukku2026", user.getPasswordHash())).isTrue();
        assertThat(encoder.matches("wrong-password1", user.getPasswordHash())).isFalse();
    }

    @Test
    void twoUsersWithTheSamePasswordGetDifferentHashes() {
        AppUser a = userService.create("user.a", "A", "samepass123", Role.ADMIN, "admin");
        AppUser b = userService.create("user.b", "B", "samepass123", Role.ADMIN, "admin");

        assertThat(a.getPasswordHash()).isNotEqualTo(b.getPasswordHash());   // random salt per hash
    }

    @Test
    void usernamesAreUniqueIgnoringCase() {
        userService.create("Priya", "Priya", "murukku2026", Role.ACCOUNTANT, "admin");

        assertThatThrownBy(() -> userService.create("priya", "Other", "murukku2026", Role.ADMIN, "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already taken");
    }

    @Test
    void badUsernamesAndWeakPasswordsAreRejected() {
        assertThatThrownBy(() -> userService.create("a b", "X", "murukku2026", Role.ADMIN, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> userService.create("valid.name", "X", "short1", Role.ADMIN, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> userService.create("valid.name", "X", "murukku2026", null, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loginLookupGivesTheRightRoleAndIgnoresCase() {
        userService.create("Meena", "Meena", "murukku2026", Role.PRODUCTION_SUPERVISOR, "admin");

        UserDetails details = detailsService.loadUserByUsername("meena");

        assertThat(details.getUsername()).isEqualTo("Meena");
        assertThat(details.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_PRODUCTION_SUPERVISOR");
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void unknownUserGivesAGenericError() {
        assertThatThrownBy(() -> detailsService.loadUserByUsername("nobody"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void aDisabledUserCannotLogIn() {
        AppUser user = userService.create("old.staff", "Old Staff", "murukku2026", Role.SALESMAN, "admin");
        userService.setEnabled(user.getId(), false, "admin");

        assertThat(detailsService.loadUserByUsername("old.staff").isEnabled()).isFalse();

        userService.setEnabled(user.getId(), true, "admin");
        assertThat(detailsService.loadUserByUsername("old.staff").isEnabled()).isTrue();
    }

    @Test
    void youCannotDisableYourOwnAccount() {
        AppUser me = userService.create("boss", "Boss", "murukku2026", Role.OWNER, "admin");

        assertThatThrownBy(() -> userService.setEnabled(me.getId(), false, "boss"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("own account");
    }

    @Test
    void changingAPasswordReplacesTheHash() {
        AppUser user = userService.create("sita", "Sita", "murukku2026", Role.ADMIN, "admin");
        String before = user.getPasswordHash();

        userService.changePassword(user.getId(), "newpass2027", "sita");

        assertThat(user.getPasswordHash()).isNotEqualTo(before);
        assertThat(encoder.matches("newpass2027", user.getPasswordHash())).isTrue();
        assertThat(encoder.matches("murukku2026", user.getPasswordHash())).isFalse();
    }

    @Test
    void userChangesAreAuditedWithoutLeakingPasswords() {
        AppUser user = userService.create("kiran", "Kiran", "murukku2026", Role.ADMIN, "owner1");
        userService.changePassword(user.getId(), "newpass2027", "kiran");

        List<AuditEntry> trail = audit.forEntity("USER", user.getId());

        assertThat(trail).extracting(AuditEntry::action).containsExactly("USER_CREATED", "PASSWORD_CHANGED");
        assertThat(trail.get(0).actor()).isEqualTo("owner1");
        assertThat(trail).extracting(AuditEntry::details).noneMatch(d -> d != null && d.contains("murukku2026"));
        assertThat(trail).extracting(AuditEntry::details).noneMatch(d -> d != null && d.contains("newpass2027"));
    }

    @Test
    void anAuditRowCannotBeUpdatedOrDeleted() {
        AppUser user = userService.create("lata", "Lata", "murukku2026", Role.ADMIN, "admin");
        long id = audit.forEntity("USER", user.getId()).get(0).id();

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET actor = 'someone else' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void deletingAuditRowsIsAlsoForbidden() {
        userService.create("mani", "Mani", "murukku2026", Role.ADMIN, "admin");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void anAuditEntryNeedsAnActorAndAction() {
        assertThatThrownBy(() -> audit.record(" ", "X", "Y", 1L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> audit.record("a", "", "Y", 1L, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
