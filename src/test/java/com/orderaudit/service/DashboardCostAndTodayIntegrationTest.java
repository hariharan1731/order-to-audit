package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.ProductionRun;
import com.orderaudit.domain.PurchaseLineSpec;
import com.orderaudit.report.ReportPeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * Audit finding M3: the dashboard says which run its cost and margin come from, and counts today's orders.
 * The official numbers are unchanged: cost Rs 31.14 per packet and margin 37.71%.
 */
@SpringBootTest
@Testcontainers
@Transactional
class DashboardCostAndTodayIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);
    private static final ReportPeriod NOVEMBER = new ReportPeriod(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));

    @Autowired WebApplicationContext context;
    @Autowired ItemService itemService;
    @Autowired RecipeService recipeService;
    @Autowired PackConfigService packConfigService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired SupplierService supplierService;
    @Autowired CustomerService customerService;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired PurchaseService purchaseService;
    @Autowired ProductionService productionService;
    @Autowired DashboardService dashboardService;
    @Autowired jakarta.persistence.EntityManager entityManager;

    MockMvc mvc;
    BenchmarkFixture f;
    ProductionRun run;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
        purchaseService.record(f.supplier.getId(), DAY, "PO-1",
                List.of(new PurchaseLineSpec(f.flour.getId(), 60_000, new BigDecimal("4.5"))), "admin");
        run = productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(new ActualLine(f.oil.getId(), 10_500), new ActualLine(f.pouch.getId(), 194),
                        new ActualLine(f.carton.getId(), 9)),
                List.of(new OverheadInput("Labour", 120_000), new OverheadInput("Gas and power", 60_000)), "production");
        priceService.setListPrice(f.murukku.getId(), 5_000);
        entityManager.flush();   // the cost report reads with plain SQL, so write everything to the database first
    }

    @Test
    void theCostAndMarginNameTheRunTheyComeFrom() {
        var d = dashboardService.compute(NOVEMBER);
        assertThat(d.latestCostPerPacket()).isEqualTo("Rs 31.14");
        assertThat(d.latestMarginPct()).isEqualTo("37.71%");
        assertThat(d.latestProduct()).contains("Run #" + run.getId());
    }

    @Test
    void thePageShowsWhereTheCostCameFrom() throws Exception {
        String page = mvc.perform(get("/owner?from=2026-11-01&to=2026-11-30").with(user("boss").roles("OWNER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"kpi-cost\">Rs 31.14<").contains("id=\"kpi-cost-product\"")
                .contains("Run #" + run.getId());
    }

    @Test
    void anEmptyPeriodHasNoRunNamed() {
        var d = dashboardService.compute(new ReportPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
        assertThat(d.latestCostPerPacket()).isEqualTo("-");
        assertThat(d.latestProduct()).isEmpty();
    }

    @Test
    void ordersTodayCountsOnlyOrdersPlacedToday() throws Exception {
        long before = dashboardService.compute(NOVEMBER).ordersToday();
        var retailer = customerService.create("Today Retailer", null, "33", null);
        orderService.submit(retailer.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), 10)));
        orderService.submit(retailer.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), 20)));

        assertThat(dashboardService.compute(NOVEMBER).ordersToday()).isEqualTo(before + 2);
        String page = mvc.perform(get("/owner").with(user("boss").roles("OWNER"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"kpi-orders-today\">" + (before + 2) + "<");
    }
}
