package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.Unit;
import java.math.BigDecimal;
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

/** Audit finding H5: opening stock and corrections can be done from a screen, with a reason, by the right roles. */
@SpringBootTest
@Testcontainers
@Transactional
class StockAdjustmentScreenIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired WebApplicationContext context;
    @Autowired ItemService itemService;
    @Autowired StockQueryService stockQuery;
    @Autowired AuditService audit;

    MockMvc mvc;
    Item flour;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        flour = itemService.create("Screen Flour", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);
    }

    private long onHand() {
        return stockQuery.snapshot().stream().filter(r -> r.itemId() == flour.getId()).findFirst().orElseThrow().onHand();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder save(
            String role, String action, String qty, String reason) {
        return post("/stock/adjustments").with(user("stockman").roles(role)).with(csrf())
                .param("itemId", String.valueOf(flour.getId())).param("action", action)
                .param("quantity", qty).param("reason", reason);
    }

    private String flashError(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        Object v = mvc.perform(req).andExpect(status().is3xxRedirection()).andReturn().getFlashMap().get("error");
        return v == null ? null : v.toString();
    }

    @Test
    void theFormShowsActiveItemsWithTheirUnit() throws Exception {
        String page = mvc.perform(get("/stock/adjustments/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Screen Flour (kg)").contains("Opening stock");
    }

    @Test
    void openingStockInKilogramsIsStoredInGrams() throws Exception {
        mvc.perform(save("ADMIN", "OPENING", "40", "Opening stock")).andExpect(status().is3xxRedirection());
        assertThat(onHand()).isEqualTo(40_000);
    }

    @Test
    void aDecimalQuantityIsExact() throws Exception {
        mvc.perform(save("ADMIN", "OPENING", "1.5", "Opening stock")).andExpect(status().is3xxRedirection());
        assertThat(onHand()).isEqualTo(1_500);
    }

    @Test
    void removeAndAddMoveStockBothWaysAndAreAudited() throws Exception {
        mvc.perform(save("ADMIN", "OPENING", "10", "Opening stock")).andExpect(status().is3xxRedirection());
        mvc.perform(save("ADMIN", "REMOVE", "2", "Spilled in storage")).andExpect(status().is3xxRedirection());
        assertThat(onHand()).isEqualTo(8_000);
        mvc.perform(save("ADMIN", "ADD", "0.5", "Found behind the shelf")).andExpect(status().is3xxRedirection());
        assertThat(onHand()).isEqualTo(8_500);

        assertThat(audit.recent(1000)).anyMatch(e -> e.action().equals("STOCK_ADJUSTED")
                && e.details().contains("-2000") && e.details().contains("Spilled in storage"));
    }

    @Test
    void removingMoreThanIsOnHandIsRefusedAndNothingChanges() throws Exception {
        mvc.perform(save("ADMIN", "OPENING", "1", "Opening stock")).andExpect(status().is3xxRedirection());
        String error = flashError(save("ADMIN", "REMOVE", "5", "Too much"));
        assertThat(error).isNotNull();
        assertThat(onHand()).isEqualTo(1_000);
    }

    @Test
    void aReasonIsRequired() throws Exception {
        String error = flashError(save("ADMIN", "OPENING", "10", "   "));
        assertThat(error).contains("reason");
        assertThat(onHand()).isZero();
    }

    @Test
    void zeroNegativeAndNonNumericQuantitiesAreRefused() throws Exception {
        for (String bad : new String[] {"0", "-3", "abc", "0.0005"}) {
            assertThat(flashError(save("ADMIN", "OPENING", bad, "Opening stock"))).as(bad).isNotNull();
        }
        assertThat(onHand()).isZero();
    }

    @Test
    void theProductionSupervisorMayAdjustButTheAccountantMayOnlyLook() throws Exception {
        mvc.perform(save("PRODUCTION_SUPERVISOR", "OPENING", "3", "Opening stock")).andExpect(status().is3xxRedirection());
        assertThat(onHand()).isEqualTo(3_000);

        mvc.perform(save("ACCOUNTANT", "OPENING", "3", "Opening stock")).andExpect(status().isForbidden());
        assertThat(onHand()).isEqualTo(3_000);
        mvc.perform(get("/stock/adjustments/new").with(user("acc").roles("ACCOUNTANT"))).andExpect(status().isOk());
    }

    @Test
    void aSalesmanCannotReachTheScreen() throws Exception {
        mvc.perform(get("/stock/adjustments/new").with(user("ravi").roles("SALESMAN"))).andExpect(status().isForbidden());
        mvc.perform(save("SALESMAN", "OPENING", "3", "x")).andExpect(status().isForbidden());
    }

    @Test
    void theStockPageLinksToTheScreen() throws Exception {
        String page = mvc.perform(get("/stock").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"adjust-stock\"").contains("/stock/adjustments/new");
    }
}
