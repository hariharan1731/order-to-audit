package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.Customer;
import com.orderaudit.domain.OrderStatus;
import com.orderaudit.domain.OverheadInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The website, driven through the real filter chain, controllers, Thymeleaf templates and a real PostgreSQL.
 * Salesman Ravi orders 150 packets for Retailer A; the admin approves, invoices and dispatches.
 */
@SpringBootTest
@Testcontainers
@Transactional
class WebOrderFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);

    @Autowired WebApplicationContext context;
    @Autowired ItemService itemService;
    @Autowired RecipeService recipeService;
    @Autowired PackConfigService packConfigService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired SupplierService supplierService;
    @Autowired CustomerService customerService;
    @Autowired ProductionService productionService;
    @Autowired PriceService priceService;
    @Autowired OrderQueryService queries;
    @Autowired StockService stock;

    MockMvc mvc;
    BenchmarkFixture f;
    Customer retailerA;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
        productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(new ActualLine(f.oil.getId(), 10_500), new ActualLine(f.pouch.getId(), 194),
                        new ActualLine(f.carton.getId(), 9)),
                List.of(new OverheadInput("Labour", 120_000), new OverheadInput("Gas and power", 60_000)), "production");
        retailerA = customerService.create("Retailer A", null, "33", null);
        priceService.setListPrice(f.murukku.getId(), 5_000);
    }

    // ---- helpers

    private static org.springframework.test.web.servlet.request.RequestPostProcessor ravi() {
        return user("ravi").roles("SALESMAN");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return user("admin1").roles("ADMIN");
    }

    private MockHttpServletRequestBuilder placeOrder(String requestId, String quantity) {
        return post("/sales/orders").with(ravi()).with(csrf())
                .param("customerId", String.valueOf(retailerA.getId()))
                .param("requestId", requestId)
                .param("itemId", String.valueOf(f.murukku.getId()))
                .param("quantity", quantity);
    }

    private long placeOrderAndGetId(String requestId, String quantity) throws Exception {
        mvc.perform(placeOrder(requestId, quantity)).andExpect(status().is3xxRedirection());
        return queries.mine("ravi").get(0).id();
    }

    /** Performs the request, expects a redirect, and returns the flash message stored under the key (or null). */
    private String flashOf(MockHttpServletRequestBuilder request, String key) throws Exception {
        var result = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn();
        Object value = result.getFlashMap().get(key);
        return value == null ? null : value.toString();
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    // ---- pages

    @Test
    void theLoginPageIsPublic() throws Exception {
        assertThat(body(get("/login"))).contains("Log in").contains("Username").contains("_csrf");
    }

    @Test
    void theHomePageOnlyOffersWhatTheRoleMayUse() throws Exception {
        String salesman = body(get("/").with(ravi()));
        assertThat(salesman).contains("New order").doesNotContain("Order desk").doesNotContain("Reports");

        String office = body(get("/").with(admin()));
        assertThat(office).contains("Order desk").contains("Reports").contains("Production");

        String accountant = body(get("/").with(user("acc").roles("ACCOUNTANT")));
        assertThat(accountant).contains("Reports").doesNotContain("New order").doesNotContain("Order desk");
    }

    @Test
    void theNewOrderFormListsTheCustomersAndTheProduct() throws Exception {
        String page = body(get("/sales/orders/new").with(ravi()));

        assertThat(page).contains("Retailer A").contains("Murukku 200 g").contains("name=\"requestId\"");
    }

    // ---- salesman

    @Test
    void aSalesmanPlacesAnOrderAndSeesItWithThePrice() throws Exception {
        assertThat(flashOf(placeOrder("req-1", "150"), "message")).contains("sent for approval");
        long id = queries.mine("ravi").get(0).id();

        String page = body(get("/sales/orders/" + id).with(ravi()));

        assertThat(page).contains("Retailer A").contains("SUBMITTED").contains("150").contains("Rs 50.00")
                .contains("Rs 7,500.00");
        assertThat(body(get("/sales/orders").with(ravi()))).contains("Retailer A").contains("150 packets");
    }

    @Test
    void sendingTheSameFormTwiceCreatesOnlyOneOrder() throws Exception {
        mvc.perform(placeOrder("same-request", "10")).andExpect(status().is3xxRedirection());
        mvc.perform(placeOrder("same-request", "10")).andExpect(status().is3xxRedirection());

        assertThat(queries.mine("ravi")).hasSize(1);
    }

    @Test
    void anOrderWithAllQuantitiesAtZeroIsRefusedWithAMessage() throws Exception {
        mvc.perform(placeOrder("req-zero", "0")).andExpect(redirectedUrl("/sales/orders/new"));
        assertThat(flashOf(placeOrder("req-zero", "0"), "error")).contains("at least one line");

        assertThat(queries.mine("ravi")).isEmpty();
    }

    @Test
    void nonNumericQuantitiesGetAFriendlyMessage() throws Exception {
        assertThat(flashOf(placeOrder("req-bad", "abc"), "error")).isEqualTo("Quantities must be whole numbers.");
    }

    @Test
    void aSalesmanCannotSeeAnotherSalesmansOrderButTheAdminCan() throws Exception {
        long id = placeOrderAndGetId("req-2", "5");

        mvc.perform(get("/sales/orders/" + id).with(user("someone.else").roles("SALESMAN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/sales/orders/" + id).with(admin())).andExpect(status().isOk());
    }

    @Test
    void anUnknownOrderIsA404() throws Exception {
        mvc.perform(get("/sales/orders/999999").with(ravi())).andExpect(status().isNotFound());
    }

    @Test
    void aFormPostedWithoutTheCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/sales/orders").with(ravi()).param("customerId", "1")).andExpect(status().isForbidden());
    }

    @Test
    void aSalesmanCannotOpenTheOrderDesk() throws Exception {
        mvc.perform(get("/admin/orders").with(ravi())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/orders/1/approve").with(ravi()).with(csrf())).andExpect(status().isForbidden());
    }

    // ---- admin: the whole official flow through the website

    @Test
    void theOrderDeskListsASubmittedOrderWithItsStockCheck() throws Exception {
        long id = placeOrderAndGetId("req-3", "150");

        assertThat(body(get("/admin/orders").with(admin()))).contains("To approve").contains("Retailer A").contains("ravi");
        String page = body(get("/admin/orders/" + id).with(admin()));
        assertThat(page).contains("238").contains("Approve").doesNotContain("Stock is short");
    }

    @Test
    void adminApprovesInvoicesAndDispatchesThroughTheWebsite() throws Exception {
        long id = placeOrderAndGetId("req-4", "150");

        mvc.perform(post("/admin/orders/" + id + "/approve").with(admin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/orders/" + id));
        assertThat(queries.view(id).summary().status()).isEqualTo(OrderStatus.APPROVED);
        assertThat(body(get("/admin/orders/" + id).with(admin()))).contains("Raise invoice");

        assertThat(flashOf(post("/admin/orders/" + id + "/invoice").with(admin()).with(csrf())
                .param("invoiceDate", "2026-11-21"), "message")).isEqualTo("Invoice INV/2026-27/0001 raised.");
        String invoiced = body(get("/admin/orders/" + id).with(admin()));
        assertThat(invoiced).contains("INV/2026-27/0001").contains("Rs 7,875.00").contains("Dispatch");
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(238);

        mvc.perform(post("/admin/orders/" + id + "/dispatch").with(admin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/orders/" + id));

        assertThat(queries.view(id).summary().status()).isEqualTo(OrderStatus.DISPATCHED);
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(88);           // OFFICIAL 88
        assertThat(body(get("/admin/orders/" + id).with(admin()))).contains("DISPATCHED").contains("dispatched by");
    }

    @Test
    void anOrderBiggerThanStockCannotBeApproved() throws Exception {
        long id = placeOrderAndGetId("req-5", "500");

        assertThat(body(get("/admin/orders/" + id).with(admin()))).contains("Stock is short").contains("short 262");
        assertThat(flashOf(post("/admin/orders/" + id + "/approve").with(admin()).with(csrf()), "error"))
                .contains("Not enough stock");

        assertThat(queries.view(id).summary().status()).isEqualTo(OrderStatus.SUBMITTED);
    }

    @Test
    void theAdminCanReduceALineAndThenApprove() throws Exception {
        long id = placeOrderAndGetId("req-6", "500");

        assertThat(flashOf(post("/admin/orders/" + id + "/lines/" + f.murukku.getId()).with(admin()).with(csrf())
                .param("quantity", "200").param("priceRupees", "48.50"), "message")).isEqualTo("Line updated.");

        var view = queries.view(id);
        assertThat(view.lines().get(0).quantity()).isEqualTo(200);
        assertThat(view.lines().get(0).unitPricePaise()).isEqualTo(4_850);
        assertThat(view.summary().totalPaise()).isEqualTo(970_000);
    }

    @Test
    void rejectingNeedsAReasonAndRecordsIt() throws Exception {
        long id = placeOrderAndGetId("req-7", "10");

        assertThat(flashOf(post("/admin/orders/" + id + "/reject").with(admin()).with(csrf()).param("reason", " "),
                "error")).contains("reason");
        assertThat(flashOf(post("/admin/orders/" + id + "/reject").with(admin()).with(csrf())
                .param("reason", "Credit limit"), "message")).isEqualTo("Order #" + id + " rejected.");

        assertThat(queries.view(id).summary().status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(body(get("/sales/orders/" + id).with(ravi()))).contains("REJECTED").contains("Credit limit");
    }

    @Test
    void invoicingAnUnapprovedOrderShowsAMessageNotAnErrorPage() throws Exception {
        long id = placeOrderAndGetId("req-8", "10");

        assertThat(flashOf(post("/admin/orders/" + id + "/invoice").with(admin()).with(csrf())
                .param("invoiceDate", "2026-11-21"), "error")).isNotNull();
    }
}
