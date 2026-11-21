package com.orderaudit.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The role matrix, checked against the real filter chain. 200 = allowed, 403 = logged in but not allowed,
 * 302 to /login = not logged in at all.
 */
@SpringBootTest
@Testcontainers
class SecurityRulesIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private void allowed(String role, String path) throws Exception {
        mvc.perform(get(path).with(user("u").roles(role))).andExpect(status().isOk());
    }

    private void forbidden(String role, String path) throws Exception {
        mvc.perform(get(path).with(user("u").roles(role))).andExpect(status().isForbidden());
    }

    @Test
    void anonymousVisitorsAreSentToTheLoginPage() throws Exception {
        for (String path : new String[] {"/sales/probe", "/production/probe", "/reports/probe", "/owner/probe",
                "/admin/probe", "/other/probe"}) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        }
    }

    @Test
    void salesmanSeesOnlySales() throws Exception {
        allowed("SALESMAN", "/sales/probe");
        forbidden("SALESMAN", "/admin/probe");
        forbidden("SALESMAN", "/production/probe");
        forbidden("SALESMAN", "/stock/probe");
        forbidden("SALESMAN", "/reports/probe");
        forbidden("SALESMAN", "/owner/probe");
        forbidden("SALESMAN", "/users/probe");
        forbidden("SALESMAN", "/audit/probe");
    }

    @Test
    void productionSupervisorSeesProductionAndStockOnly() throws Exception {
        allowed("PRODUCTION_SUPERVISOR", "/production/probe");
        allowed("PRODUCTION_SUPERVISOR", "/stock/probe");
        forbidden("PRODUCTION_SUPERVISOR", "/sales/probe");
        forbidden("PRODUCTION_SUPERVISOR", "/admin/probe");
        forbidden("PRODUCTION_SUPERVISOR", "/reports/probe");
        forbidden("PRODUCTION_SUPERVISOR", "/owner/probe");
    }

    @Test
    void accountantSeesReportsAndStockButCannotChangeAnything() throws Exception {
        allowed("ACCOUNTANT", "/reports/probe");
        allowed("ACCOUNTANT", "/stock/probe");
        forbidden("ACCOUNTANT", "/sales/probe");
        forbidden("ACCOUNTANT", "/production/probe");
        forbidden("ACCOUNTANT", "/admin/probe");
        forbidden("ACCOUNTANT", "/users/probe");
        forbidden("ACCOUNTANT", "/owner/probe");
    }

    @Test
    void adminRunsTheOfficeButNotTheOwnerArea() throws Exception {
        allowed("ADMIN", "/sales/probe");
        allowed("ADMIN", "/admin/probe");
        allowed("ADMIN", "/production/probe");
        allowed("ADMIN", "/stock/probe");
        allowed("ADMIN", "/reports/probe");
        allowed("ADMIN", "/users/probe");
        allowed("ADMIN", "/audit/probe");
        forbidden("ADMIN", "/owner/probe");
    }

    @Test
    void ownerSeesEverything() throws Exception {
        for (String path : new String[] {"/sales/probe", "/production/probe", "/stock/probe", "/reports/probe",
                "/owner/probe", "/admin/probe", "/users/probe", "/audit/probe", "/other/probe"}) {
            allowed("OWNER", path);
        }
    }

    @Test
    void anyOtherPathJustNeedsALogin() throws Exception {
        allowed("SALESMAN", "/other/probe");
        allowed("ACCOUNTANT", "/other/probe");
    }

    @Test
    void aPostWithoutTheCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/sales/probe").with(user("u").roles("SALESMAN"))).andExpect(status().isForbidden());
    }

    @Test
    void aPostWithTheCsrfTokenWorksForTheRightRole() throws Exception {
        mvc.perform(post("/sales/probe").with(user("u").roles("SALESMAN")).with(csrf())).andExpect(status().isOk());
    }

    @Test
    void aPostWithTheCsrfTokenStillFailsForTheWrongRole() throws Exception {
        mvc.perform(post("/sales/probe").with(user("u").roles("ACCOUNTANT")).with(csrf()))
                .andExpect(status().isForbidden());
    }
}
