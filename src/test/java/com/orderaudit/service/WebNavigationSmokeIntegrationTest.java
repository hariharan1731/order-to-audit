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
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.ProductionRun;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.report.ReportPeriod;
import com.orderaudit.web.ReportKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

/**
 * "No broken pages": opens every screen and every menu link with real data behind it and insists on HTTP 200.
 * This is the net that catches a template typo or a renamed model attribute, which unit tests never see.
 */
@SpringBootTest
@Testcontainers
@Transactional
class WebNavigationSmokeIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);
    private static final Pattern INTERNAL_LINK = Pattern.compile("href=\"(/[^\"#]*)\"");

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
    @Autowired ReceivablesService receivables;
    @Autowired UserService userService;

    MockMvc mvc;
    BenchmarkFixture f;
    long orderId;
    long invoiceId;
    long runId;
    long itemId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
        ProductionRun run = productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(new ActualLine(f.oil.getId(), 10_500), new ActualLine(f.pouch.getId(), 194),
                        new ActualLine(f.carton.getId(), 9)),
                List.of(new OverheadInput("Labour", 120_000), new OverheadInput("Gas and power", 60_000)), "production");
        runId = run.getId();
        itemId = f.flour.getId();

        Customer retailerA = customerService.create("Retailer A", null, "33", null);
        priceService.setListPrice(f.murukku.getId(), 5_000);
        SalesOrder order = orderService.submit(retailerA.getId(), "ravi", null, "please deliver by Friday",
                List.of(new OrderLineRequest(f.murukku.getId(), 150)));
        orderId = order.getId();
        orderService.approve(orderId, "admin1");
        invoiceId = invoiceService.raise(orderId, DAY, "admin1").getId();
        dispatchService.dispatch(orderId, "admin1");
        receivables.recordPaymentOldestFirst(retailerA.getId(), DAY, com.orderaudit.domain.PaymentMethod.CASH,
                "UPI-1", "admin1", 400_000);
        userService.create("ravi", "Ravi", "Secret123", com.orderaudit.domain.Role.SALESMAN, "admin1");
    }

    private static RequestPostProcessor owner() {
        return user("boss").roles("OWNER");
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void expectOk(String path) throws Exception {
        int statusCode = mvc.perform(get(path).with(owner())).andReturn().getResponse().getStatus();
        assertThat(statusCode).as("GET %s", path).isEqualTo(200);
    }

    @Test
    void everyScreenOpensForTheOwner() throws Exception {
        List<String> paths = new ArrayList<>(List.of(
                "/", "/sales/orders", "/sales/orders/new", "/sales/orders/" + orderId,
                "/admin/orders", "/admin/orders/" + orderId,
                "/admin/invoices", "/admin/invoices/" + invoiceId,
                "/admin/payments", "/admin/customers", "/admin/prices", "/admin/items", "/admin/setup",
                "/users", "/audit",
                "/production", "/production/new", "/production/runs/" + runId,
                "/stock", "/stock/items/" + itemId, "/stock/purchases/new",
                "/reports", "/owner"));
        for (String path : paths) {
            expectOk(path);
        }
    }

    @Test
    void theProductionPlanScreenOpensWithAChosenProduct() throws Exception {
        expectOk("/production/new?packConfigId=" + f.config.getId() + "&batches=1");
    }

    @Test
    void everyOneOfTheTwelveReportsOpens() throws Exception {
        LocalDate today = ReportPeriod.today();
        for (ReportKey key : ReportKey.values()) {
            String query = switch (key.kind()) {
                case AS_OF -> "?asOf=" + today;
                case ITEM_PERIOD -> "?from=" + today.minusDays(30) + "&to=" + today + "&itemId=" + itemId;
                case PERIOD -> "?from=" + today.minusDays(30) + "&to=" + today;
            };
            expectOk("/reports/" + key.slug() + query);
        }
    }

    @Test
    void everyLinkInTheMenuAndOnTheHomePageOpens() throws Exception {
        Set<String> links = new LinkedHashSet<>();
        List<String> pagesToCrawl = List.of("/", "/sales/orders", "/sales/orders/" + orderId,
                "/admin/orders", "/admin/orders/" + orderId, "/admin/invoices", "/admin/invoices/" + invoiceId,
                "/admin/payments", "/admin/customers", "/admin/prices", "/admin/items", "/admin/setup",
                "/users", "/audit", "/production", "/production/runs/" + runId,
                "/stock", "/stock/items/" + itemId, "/stock/purchases/new", "/reports", "/owner");
        for (String page : pagesToCrawl) {
            Matcher matcher = INTERNAL_LINK.matcher(body(get(page).with(owner())));
            while (matcher.find()) {
                links.add(matcher.group(1));
            }
        }
        assertThat(links).as("the menu should offer plenty of links").hasSizeGreaterThan(10);
        for (String link : links) {
            expectOk(link);
        }
    }

    @Test
    void theRoleRulesStillHold() throws Exception {
        for (String forbidden : List.of("/admin/orders", "/production", "/reports", "/owner", "/users", "/audit")) {
            mvc.perform(get(forbidden).with(user("ravi").roles("SALESMAN")))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/stock").with(user("acc").roles("ACCOUNTANT"))).andExpect(status().isOk());
        mvc.perform(post("/stock/purchases").with(user("acc").roles("ACCOUNTANT")).with(csrf()))
                .andExpect(status().isForbidden());
    }
}
