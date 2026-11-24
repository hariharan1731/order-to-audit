package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.Role;
import com.orderaudit.domain.Unit;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Audit finding H3: manual stock changes and sign-ins (good and bad) must be in the append-only audit log.
 * (Orders and purchases were already logged.)
 */
@SpringBootTest
@Testcontainers
@Transactional
class StockAndLoginAuditIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired WebApplicationContext context;
    @Autowired ItemService itemService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired UserService userService;
    @Autowired AuditService audit;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private List<AuditEntry> by(String actor, String action) {
        return audit.recent(1000).stream().filter(e -> e.actor().equals(actor) && e.action().equals(action)).toList();
    }

    // ---- stock

    @Test
    void openingStockIsInTheAuditLog() {
        Item flour = itemService.create("Audit Flour", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);

        adjustments.recordOpening(flour.getId(), 100_000, null, "Opening stock", "stockman");

        List<AuditEntry> rows = by("stockman", "STOCK_OPENING_RECORDED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).entityType()).isEqualTo("STOCK_ADJUSTMENT");
        assertThat(rows.get(0).details()).contains("item " + flour.getId()).contains("+100000").contains("Opening stock");
    }

    @Test
    void aManualCorrectionShowsWhoRemovedHowMuchAndWhy() {
        Item flour = itemService.create("Audit Flour 2", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);
        adjustments.recordOpening(flour.getId(), 100_000, null, "Opening stock", "stockman");

        adjustments.recordCorrection(flour.getId(), -2_500, null, "Spilled in storage", "stockman2");

        List<AuditEntry> rows = by("stockman2", "STOCK_ADJUSTED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).details()).contains("-2500").contains("Spilled in storage");
    }

    @Test
    void aRefusedAdjustmentLeavesNoAuditRow() {
        Item flour = itemService.create("Audit Flour 3", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> adjustments.recordCorrection(flour.getId(), -5, null, " ", "stockman3"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(by("stockman3", "STOCK_ADJUSTED")).isEmpty();
    }

    // ---- sign-ins

    @Test
    void aGoodSignInIsLoggedWithTheUsername() throws Exception {
        userService.create("login-ok", "Login Ok", "murukku2026", Role.SALESMAN, "seed");

        mvc.perform(formLogin("/login").user("login-ok").password("murukku2026"))
                .andExpect(redirectedUrl("/"));

        List<AuditEntry> rows = by("login-ok", "LOGIN_SUCCEEDED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).details()).contains("signed in");
    }

    @Test
    void aWrongPasswordIsLoggedButThePasswordItselfIsNot() throws Exception {
        userService.create("login-bad", "Login Bad", "murukku2026", Role.SALESMAN, "seed");

        mvc.perform(formLogin("/login").user("login-bad").password("totally-wrong-guess"))
                .andExpect(redirectedUrl("/login?error"));

        List<AuditEntry> rows = by("login-bad", "LOGIN_FAILED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).details()).contains("wrong password or unknown user")
                .doesNotContain("totally-wrong-guess");
        assertThat(by("login-bad", "LOGIN_SUCCEEDED")).isEmpty();
    }

    @Test
    void anUnknownUsernameIsLoggedAsAFailedAttempt() throws Exception {
        mvc.perform(formLogin("/login").user("nobody-here").password("whatever123"))
                .andExpect(redirectedUrl("/login?error"));

        assertThat(by("nobody-here", "LOGIN_FAILED")).hasSize(1);
    }

    @Test
    void aDisabledAccountIsToldApartFromAWrongPassword() throws Exception {
        var user = userService.create("login-off", "Login Off", "murukku2026", Role.SALESMAN, "seed");
        userService.setEnabled(user.getId(), false, "seed");

        mvc.perform(formLogin("/login").user("login-off").password("murukku2026"))
                .andExpect(redirectedUrl("/login?error"));

        List<AuditEntry> rows = by("login-off", "LOGIN_FAILED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).details()).contains("account is disabled");
    }
}
