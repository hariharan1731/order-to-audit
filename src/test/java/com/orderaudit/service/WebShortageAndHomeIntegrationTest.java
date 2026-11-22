package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.OrderStatus;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.SalesOrder;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The official Distributor B story through the screens: 238 produced, Retailer A takes 150 (88 left),
 * Distributor B asks for 120 and is short by 32; reduced to 88 it is billed with IGST (other state).
 * Plus the role home pages.
 */
@SpringBootTest
@Testcontainers
@Transactional
class WebShortageAndHomeIntegrationTest {

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
    @Autowired OrderService orderService;
    @Autowired InvoiceService invoiceService;
    @Autowired DispatchService dispatchService;
    @Autowired OrderQueryService orderQueries;
    @Autowired StockService stock;

    MockMvc mvc;
    BenchmarkFixture f;
    Customer distributorB;
    SalesOrder bOrder;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
        productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(new ActualLine(f.oil.getId(), 10_500), new ActualLine(f.pouch.getId(), 194),
                        new ActualLine(f.carton.getId(), 9)),
                List.of(new OverheadInput("Labour", 120_000), new OverheadInput("Gas and power", 60_000)), "production");
        Customer retailerA = customerService.create("Retailer A", null, "33", null);
        distributorB = customerService.create("Distributor B", null, "29", null);   // Karnataka: other state
        priceService.setListPrice(f.murukku.getId(), 5_000);
        SalesOrder a = orderService.submit(retailerA.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), 150)));
        orderService.approve(a.getId(), "admin1");
        invoiceService.raise(a.getId(), DAY, "admin1");
        dispatchService.dispatch(a.getId(), "admin1");
        bOrder = orderService.submit(distributorB.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), 120)));
    }

    private static RequestPostProcessor admin() {
        return user("admin1").roles("ADMIN");
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String flashOf(MockHttpServletRequestBuilder request, String key) throws Exception {
        var result = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn();
        Object value = result.getFlashMap().get(key);
        return value == null ? null : value.toString();
    }

    @Test
    void finishedStockIsEightyEightBeforeDistributorBOrders() {
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(88);
    }

    @Test
    void theOrderDeskFlagsTheShortageOfThirtyTwo() throws Exception {
        String page = body(get("/admin/orders/" + bOrder.getId()).with(admin()));
        assertThat(page).contains("short 32").contains("Stock is short");
    }

    @Test
    void approvingAShortOrderIsRefusedAndTheOrderStaysWaiting() throws Exception {
        String error = flashOf(post("/admin/orders/" + bOrder.getId() + "/approve").with(admin()).with(csrf()), "error");
        assertThat(error).isNotBlank();
        assertThat(orderQueries.view(bOrder.getId()).summary().status()).isEqualTo(OrderStatus.SUBMITTED);
    }

    @Test
    void reducedToEightyEightItIsApprovedAndBilledWithIgst() throws Exception {
        String saved = flashOf(post("/admin/orders/" + bOrder.getId() + "/lines/" + f.murukku.getId())
                .with(admin()).with(csrf()).param("quantity", "88").param("priceRupees", "50"), "message");
        assertThat(saved).isNotNull();
        String approved = flashOf(post("/admin/orders/" + bOrder.getId() + "/approve").with(admin()).with(csrf()), "message");
        assertThat(approved).isNotNull();
        Invoice invoice = invoiceService.raise(bOrder.getId(), DAY, "admin1");
        assertThat(invoice.getTaxablePaise()).isEqualTo(440_000);          // 88 x Rs 50
        assertThat(invoice.getIgstPaise()).isEqualTo(22_000);              // 5% IGST = Rs 220
        assertThat(invoice.getCgstPaise()).isZero();
        assertThat(invoice.getSgstPaise()).isZero();
        assertThat(invoice.getTotalPaise()).isEqualTo(462_000);
    }

    // ---- role home pages

    @Test
    void salesmanHomeShowsHisOrderCounts() throws Exception {
        String page = body(get("/").with(user("ravi").roles("SALESMAN")));
        assertThat(page).contains("id=\"sales-summary\"").contains("Waiting approval").doesNotContain("office-summary");
    }

    @Test
    void adminHomeShowsOrdersWaitingAndMoney() throws Exception {
        String page = body(get("/").with(admin()));
        assertThat(page).contains("id=\"office-summary\"").contains("Orders waiting approval").contains("Outstanding");
    }

    @Test
    void productionHomeShowsStockAndFlaggedRunsButNoMoney() throws Exception {
        String page = body(get("/").with(user("prod").roles("PRODUCTION_SUPERVISOR")));
        assertThat(page).contains("Finished packets in stock").contains("Flagged runs").doesNotContain("Outstanding")
                .doesNotContain("Sales this month");
    }

    @Test
    void accountantHomeShowsMoneyButNoApprovalQueue() throws Exception {
        String page = body(get("/").with(user("acc").roles("ACCOUNTANT")));
        assertThat(page).contains("Sales this month").contains("Outstanding").doesNotContain("Orders waiting approval");
    }
}
