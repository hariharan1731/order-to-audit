package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderaudit.domain.Customer;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.SalesOrder;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The production supervisor's screens and the purchase form, through the real stack, on the official numbers. */
@SpringBootTest
@Testcontainers
@Transactional
class WebProductionAndPurchaseIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final Pattern RUN_URL = Pattern.compile("/production/runs/(\\d+)");

    @Autowired WebApplicationContext context;
    @Autowired ItemService itemService;
    @Autowired RecipeService recipeService;
    @Autowired PackConfigService packConfigService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired SupplierService supplierService;
    @Autowired CustomerService customerService;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired InvoiceService invoiceService;
    @Autowired DispatchService dispatchService;
    @Autowired StockService stock;

    MockMvc mvc;
    BenchmarkFixture f;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
    }

    private static RequestPostProcessor supervisor() {
        return user("prod1").roles("PRODUCTION_SUPERVISOR");
    }

    private String flashOf(MockHttpServletRequestBuilder request, String key) throws Exception {
        var result = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn();
        Object value = result.getFlashMap().get(key);
        return value == null ? null : value.toString();
    }

    private MockHttpServletRequestBuilder purchaseOfSixtyKgFlour() {
        return post("/stock/purchases").with(supervisor()).with(csrf())
                .param("supplierId", String.valueOf(f.supplier.getId()))
                .param("purchaseDate", "2026-11-21").param("reference", "BILL-1")
                .param("itemId", String.valueOf(f.flour.getId())).param("quantity", "60").param("rate", "45")
                .param("itemId", "").param("quantity", "").param("rate", "");
    }

    private MockHttpServletRequestBuilder officialRun() {
        return post("/production/runs").with(supervisor()).with(csrf())
                .param("packConfigId", String.valueOf(f.config.getId())).param("batches", "1")
                .param("runDate", "2026-11-21").param("goodPackets", "188").param("damagedPouches", "6")
                .param("itemId", String.valueOf(f.flour.getId())).param("actual", "40")
                .param("itemId", String.valueOf(f.oil.getId())).param("actual", "10.5")
                .param("itemId", String.valueOf(f.spice.getId())).param("actual", "1.2")
                .param("itemId", String.valueOf(f.pouch.getId())).param("actual", "194")
                .param("itemId", String.valueOf(f.carton.getId())).param("actual", "9")
                .param("labour", "1200").param("gasPower", "600");
    }

    private long postOfficialRun() throws Exception {
        mvc.perform(purchaseOfSixtyKgFlour()).andExpect(status().is3xxRedirection());
        String location = mvc.perform(officialRun()).andExpect(status().is3xxRedirection()).andReturn()
                .getResponse().getRedirectedUrl();
        Matcher m = RUN_URL.matcher(location);
        assertThat(m.find()).as("redirected to " + location).isTrue();
        return Long.parseLong(m.group(1));
    }

    // ---------- purchases ----------

    @Test
    void purchaseFormShowsForTheSupervisor() throws Exception {
        String body = mvc.perform(get("/stock/purchases/new").with(supervisor())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("Record a purchase").contains("Flour (kg)").contains("Flour Mill");
    }

    @Test
    void sixtyKgOfFlourAtFortyFiveRupeesRaisesStockToOneSixtyKg() throws Exception {
        String message = flashOf(purchaseOfSixtyKgFlour(), "message");
        assertThat(message).contains("Purchase recorded");
        assertThat(stock.balanceOf(f.flour.getId())).isEqualTo(160_000);
    }

    @Test
    void aPurchaseLineWithoutARateIsRefusedAndStockIsUnchanged() throws Exception {
        String error = flashOf(post("/stock/purchases").with(supervisor()).with(csrf())
                .param("supplierId", String.valueOf(f.supplier.getId())).param("purchaseDate", "2026-11-21")
                .param("itemId", String.valueOf(f.flour.getId())).param("quantity", "60").param("rate", ""), "error");
        assertThat(error).contains("Enter the rate");
        assertThat(stock.balanceOf(f.flour.getId())).isEqualTo(100_000);
    }

    @Test
    void aGramFractionInAPurchaseIsRefused() throws Exception {
        String error = flashOf(post("/stock/purchases").with(supervisor()).with(csrf())
                .param("supplierId", String.valueOf(f.supplier.getId())).param("purchaseDate", "2026-11-21")
                .param("itemId", String.valueOf(f.flour.getId())).param("quantity", "0.0005").param("rate", "45"), "error");
        assertThat(error).contains("too many decimals");
    }

    @Test
    void theAccountantCanLookButNotRecordAPurchase() throws Exception {
        mvc.perform(post("/stock/purchases").with(user("acc1").roles("ACCOUNTANT")).with(csrf())
                .param("supplierId", "1").param("purchaseDate", "2026-11-21")).andExpect(status().isForbidden());
    }

    @Test
    void aSupplierCanBeAddedFromTheScreen() throws Exception {
        String message = flashOf(post("/stock/purchases/suppliers").with(supervisor()).with(csrf())
                .param("name", "Oil Traders").param("stateCode", "33"), "message");
        assertThat(message).isEqualTo("Supplier added.");
        assertThat(supplierService.activeList()).extracting(s -> s.getName()).contains("Oil Traders");
    }

    // ---------- production ----------

    @Test
    void thePlanScreenShowsTheOfficialPlannedQuantities() throws Exception {
        String body = mvc.perform(get("/production/new").with(supervisor())
                        .param("packConfigId", String.valueOf(f.config.getId())).param("batches", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("40 kg").contains("10 L").contains("1.2 kg").contains("200 pcs");
    }

    @Test
    void theOfficialRunThroughTheWebGivesTheOfficialStockAndCost() throws Exception {
        long runId = postOfficialRun();

        assertThat(stock.balanceOf(f.flour.getId())).isEqualTo(120_000);      // 100 + 60 - 40 kg
        assertThat(stock.balanceOf(f.oil.getId())).isEqualTo(19_500);
        assertThat(stock.balanceOf(f.pouch.getId())).isEqualTo(306);
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(238);

        String page = mvc.perform(get("/production/runs/" + runId).with(supervisor())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("-6").contains("flagged").contains("31.14");
    }

    @Test
    void aRunNeedingMoreFlourThanExistsIsRefusedAndNothingMoves() throws Exception {
        String error = flashOf(post("/production/runs").with(supervisor()).with(csrf())
                .param("packConfigId", String.valueOf(f.config.getId())).param("batches", "1")
                .param("runDate", "2026-11-21").param("goodPackets", "188").param("damagedPouches", "6")
                .param("itemId", String.valueOf(f.flour.getId())).param("actual", "500"), "error");
        assertThat(error).isNotBlank();
        assertThat(stock.balanceOf(f.flour.getId())).isEqualTo(100_000);
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(50);
    }

    @Test
    void theRunListShowsAPostedRun() throws Exception {
        long runId = postOfficialRun();
        String body = mvc.perform(get("/production").with(supervisor())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("/production/runs/" + runId).contains("POSTED");
    }

    @Test
    void reversingARunPutsStockBack() throws Exception {
        long runId = postOfficialRun();
        String message = flashOf(post("/production/runs/" + runId + "/reverse").with(supervisor()).with(csrf()), "message");
        assertThat(message).contains("reversed");
        assertThat(stock.balanceOf(f.flour.getId())).isEqualTo(160_000);
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(50);
    }

    @Test
    void aRunCannotBeReversedOnceItsPacketsWereSold() throws Exception {
        long runId = postOfficialRun();
        Customer retailerA = customerService.create("Retailer A", null, "33", null);
        priceService.setListPrice(f.murukku.getId(), 5_000);
        SalesOrder order = orderService.submit(retailerA.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), 150)));
        orderService.approve(order.getId(), "admin1");
        invoiceService.raise(order.getId(), LocalDate.of(2026, 11, 21), "admin1");
        dispatchService.dispatch(order.getId(), "admin1");

        String error = flashOf(post("/production/runs/" + runId + "/reverse").with(supervisor()).with(csrf()), "error");
        assertThat(error).isNotBlank();
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(88);
    }

    @Test
    void aSalesmanCannotOpenProductionScreens() throws Exception {
        mvc.perform(get("/production").with(user("ravi").roles("SALESMAN"))).andExpect(status().isForbidden());
        mvc.perform(get("/production/new").with(user("ravi").roles("SALESMAN"))).andExpect(status().isForbidden());
    }

    @Test
    void anUnknownRunIsNotFound() throws Exception {
        mvc.perform(get("/production/runs/999999").with(supervisor())).andExpect(status().isNotFound());
    }
}
