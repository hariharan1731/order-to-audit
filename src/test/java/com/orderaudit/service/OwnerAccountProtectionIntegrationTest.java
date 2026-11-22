package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderaudit.domain.AppUser;
import com.orderaudit.domain.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Audit finding C2: an admin must not be able to take over the business. Only an owner may create an owner,
 * reset an owner's password or enable / disable an owner, and the last active owner can never be disabled.
 */
@SpringBootTest
@Testcontainers
@Transactional
class OwnerAccountProtectionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired WebApplicationContext context;
    @Autowired UserService userService;
    @Autowired AuditService audit;
    @Autowired PasswordEncoder encoder;

    MockMvc mvc;
    AppUser owner;
    AppUser admin;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // created by the start-up seeder, as on a fresh installation
        owner = userService.create("boss", "Boss", "murukku2026", Role.OWNER, "seed");
        admin = userService.create("office", "Office", "murukku2026", Role.ADMIN, "seed");
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    private void loggedInAs(String name, String role) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(name, "x", "ROLE_" + role));
    }

    private String flashOf(MockHttpServletRequestBuilder request, String key) throws Exception {
        var result = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn();
        Object value = result.getFlashMap().get(key);
        return value == null ? null : value.toString();
    }

    // ---- service rules, with a logged-in user

    @Test
    void anAdminCannotCreateAnOwner() {
        loggedInAs("office", "ADMIN");
        assertThatThrownBy(() -> userService.create("newboss", "X", "murukku2026", Role.OWNER, "office"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Only an owner");
    }

    @Test
    void anAdminCannotResetTheOwnersPasswordOrDisableTheOwner() {
        String hashBefore = owner.getPasswordHash();
        loggedInAs("office", "ADMIN");

        assertThatThrownBy(() -> userService.changePassword(owner.getId(), "takeover2026", "office"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Only an owner");
        assertThatThrownBy(() -> userService.setEnabled(owner.getId(), false, "office"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Only an owner");

        assertThat(owner.getPasswordHash()).isEqualTo(hashBefore);
        assertThat(owner.isEnabled()).isTrue();
        assertThat(audit.forEntity("USER", owner.getId())).extracting(AuditEntry::action)
                .containsExactly("USER_CREATED");   // nothing happened, so nothing was logged
    }

    @Test
    void anAdminStillManagesOrdinaryAccountsAndOwnPassword() {
        loggedInAs("office", "ADMIN");
        AppUser salesman = userService.create("ravi", "Ravi", "murukku2026", Role.SALESMAN, "office");
        userService.setEnabled(salesman.getId(), false, "office");
        userService.changePassword(salesman.getId(), "newpass2027", "office");
        userService.changePassword(admin.getId(), "mypass2027", "office");

        assertThat(salesman.isEnabled()).isFalse();
        assertThat(encoder.matches("mypass2027", admin.getPasswordHash())).isTrue();
    }

    @Test
    void anOwnerCanManageAnotherOwner() {
        loggedInAs("boss", "OWNER");
        AppUser partner = userService.create("partner", "Partner", "murukku2026", Role.OWNER, "boss");
        userService.changePassword(partner.getId(), "partner2027", "boss");
        userService.setEnabled(partner.getId(), false, "boss");

        assertThat(partner.isEnabled()).isFalse();
        assertThat(encoder.matches("partner2027", partner.getPasswordHash())).isTrue();
    }

    @Test
    void theLastActiveOwnerCanNeverBeDisabled() {
        loggedInAs("partner", "OWNER");   // even by another owner whose own login is the only other one
        assertThatThrownBy(() -> userService.setEnabled(owner.getId(), false, "partner"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("last active owner");
        assertThat(owner.isEnabled()).isTrue();
    }

    // ---- the same rules through the website

    @Test
    void theWebsiteRefusesAnAdminWhoTriesToCreateAnOwner() throws Exception {
        String error = flashOf(post("/users").with(user("office").roles("ADMIN")).with(csrf())
                .param("username", "sneaky").param("displayName", "Sneaky").param("password", "murukku2026")
                .param("role", "OWNER"), "error");

        assertThat(error).contains("Only an owner");
        assertThat(userService.all()).extracting(AppUser::getUsername).doesNotContain("sneaky");
    }

    @Test
    void theWebsiteRefusesAnAdminWhoTriesToResetTheOwnersPassword() throws Exception {
        String error = flashOf(post("/users/" + owner.getId() + "/password").with(user("office").roles("ADMIN"))
                .with(csrf()).param("password", "takeover2026"), "error");

        assertThat(error).contains("Only an owner");
        assertThat(encoder.matches("takeover2026", owner.getPasswordHash())).isFalse();
    }

    @Test
    void anAdminDoesNotSeeTheOwnerOptionOrOwnerControlsButAnOwnerDoes() throws Exception {
        String asAdmin = mvc.perform(get("/users").with(user("office").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String asOwner = mvc.perform(get("/users").with(user("boss").roles("OWNER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(asAdmin).doesNotContain("<option value=\"OWNER\"").contains("Only an owner can change this account");
        assertThat(asOwner).contains("<option value=\"OWNER\"").doesNotContain("Only an owner can change this account");
    }
}
