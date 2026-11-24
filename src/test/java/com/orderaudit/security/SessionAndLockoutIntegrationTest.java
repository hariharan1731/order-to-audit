package com.orderaudit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderaudit.domain.Role;
import com.orderaudit.service.AuditService;
import com.orderaudit.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Audit finding M5: a disabled user is signed out at once, and repeated wrong passwords lock the username. */
@SpringBootTest
@Testcontainers
@Transactional
class SessionAndLockoutIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired WebApplicationContext context;
    @Autowired UserService userService;
    @Autowired AuditService audit;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private void wrongPassword(String user, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            mvc.perform(formLogin("/login").user(user).password("not-the-password")).andExpect(redirectedUrl("/login?error"));
        }
    }

    // ---- disabled users lose their session

    @Test
    void aDisabledUserIsSignedOutOnTheNextClick() throws Exception {
        var user = userService.create("m5-session", "M5 Session", "murukku2026", Role.SALESMAN, "seed");
        MvcResult login = mvc.perform(formLogin("/login").user("m5-session").password("murukku2026")).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession();

        String before = String.valueOf(mvc.perform(get("/").session(session)).andReturn().getResponse().getRedirectedUrl());
        assertThat(before).doesNotContain("/login");                  // still welcome

        userService.setEnabled(user.getId(), false, "office");

        mvc.perform(get("/").session(session)).andExpect(redirectedUrl("/login?disabled"));
        mvc.perform(get("/sales/orders").session(session)).andExpect(redirectedUrl("/login"));   // session is gone
    }

    @Test
    void theLoginPageExplainsADisabledAccount() throws Exception {
        String page = mvc.perform(get("/login?disabled")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Your account has been disabled");
    }

    @Test
    void anEnabledUserKeepsTheirSession() throws Exception {
        userService.create("m5-active", "M5 Active", "murukku2026", Role.SALESMAN, "seed");
        MvcResult login = mvc.perform(formLogin("/login").user("m5-active").password("murukku2026")).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession();

        for (int i = 0; i < 3; i++) {
            String redirect = String.valueOf(mvc.perform(get("/").session(session)).andReturn().getResponse().getRedirectedUrl());
            assertThat(redirect).doesNotContain("/login");
        }
    }

    // ---- lock-out

    @Test
    void fiveWrongPasswordsLockTheUsernameEvenForTheRightPassword() throws Exception {
        userService.create("m5-locked", "M5 Locked", "murukku2026", Role.SALESMAN, "seed");
        wrongPassword("m5-locked", 5);

        mvc.perform(formLogin("/login").user("m5-locked").password("murukku2026")).andExpect(redirectedUrl("/login?error"));

        assertThat(audit.recent(1000)).anyMatch(e -> e.actor().equals("m5-locked")
                && e.action().equals("LOGIN_FAILED") && e.details().contains("account is locked"));
        assertThat(audit.recent(1000)).noneMatch(e -> e.actor().equals("m5-locked") && e.action().equals("LOGIN_SUCCEEDED"));
    }

    @Test
    void fourWrongPasswordsThenTheRightOneStillSignsIn() throws Exception {
        userService.create("m5-four", "M5 Four", "murukku2026", Role.SALESMAN, "seed");
        wrongPassword("m5-four", 4);

        mvc.perform(formLogin("/login").user("m5-four").password("murukku2026")).andExpect(redirectedUrl("/"));
    }

    @Test
    void aGoodSignInResetsTheCount() throws Exception {
        userService.create("m5-reset", "M5 Reset", "murukku2026", Role.SALESMAN, "seed");
        wrongPassword("m5-reset", 4);
        mvc.perform(formLogin("/login").user("m5-reset").password("murukku2026")).andExpect(redirectedUrl("/"));

        wrongPassword("m5-reset", 4);        // four more, but the count started again from zero
        mvc.perform(formLogin("/login").user("m5-reset").password("murukku2026")).andExpect(redirectedUrl("/"));
    }

    @Test
    void lockingOneUserLeavesOthersAlone() throws Exception {
        userService.create("m5-victim", "M5 Victim", "murukku2026", Role.SALESMAN, "seed");
        userService.create("m5-other", "M5 Other", "murukku2026", Role.SALESMAN, "seed");
        wrongPassword("m5-victim", 5);

        mvc.perform(formLogin("/login").user("m5-other").password("murukku2026")).andExpect(redirectedUrl("/"));
    }
}
