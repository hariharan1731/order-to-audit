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
import com.orderaudit.domain.PurchaseLineSpec;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.report.ReportPeriod;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Reports (on screen and downloaded), the stock screens and the owner dashboard, on the official benchmark data. */
@SpringBootTest
@Testcontainers
@Transactional
class WebReportsAndDashboardIntegrationTest {

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
    @Autowired PurchaseService purchaseService;
    @Autowired DashboardService dashboardService;

    MockMvc mvc;
    BenchmarkFixture f;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
        // OFFICIAL step 1: buy 60 kg flour at Rs 45/kg (4.5 paise per gram) before the run.
        purchaseService.record(f.supplier.getId(), DAY, "PO-1",
                List.of(new PurchaseLineSpec(f.flour.getId(), 60_000, new BigDecimal("4.5"))), "admin");
        productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(new ActualLine(f.oil.getId(), 10_500), new ActualLine(f.pouch.getId(), 194),
                        new ActualLine(f.carton.getId(), 9)),
                List.of(new OverheadInput("Labour", 120_000), new OverheadInput("Gas and power", 60_000)), "production");
        Customer retailerA = customerService.create("Retailer A", null, "33", null);
        priceService.setListPrice(f.murukku.getId(), 5_000);
        SalesOrder order = orderService.submit(retailerA.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), 150)));
        orderService.approve(order.getId(), "admin1");
        invoiceService.raise(order.getId(), DAY, "admin1");
        dispatchService.dispatch(order.getId(), "admin1");
    }

    private static RequestPostProcessor accountant() { return user("acc").roles("ACCOUNTANT"); }
    private static RequestPostProcessor owner() { return user("boss").roles("OWNER"); }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private MockHttpServletResponse download(String path) throws Exception {
        return mvc.perform(get(path).with(accountant())).andExpect(status().isOk()).andReturn().getResponse();
    }

    private static String aroundToday() {
        LocalDate today = ReportPeriod.today();
        return "from=" + today.minusDays(1) + "&to=" + today.plusDays(1);
    }

    // ---- reports on screen

    @Test
    void theReportsIndexListsAllTwelveReports() throws Exception {
        String page = body(get("/reports").with(accountant()));

        for (String title : new String[] {"Sales register", "Customer-wise sales", "Product-wise sales", "GST summary",
                "Purchase register", "Stock summary", "Stock ledger of one item", "Production yield and wastage",
                "Planned vs actual consumption", "Cost per packet and margin", "Payments and collections",
                "Outstanding receivables with ageing"}) {
            assertThat(page).contains(title);
        }
    }

    @Test
    void theSalesRegisterShowsTheOfficialInvoice() throws Exception {
        String page = body(get("/reports/sales-register?from=2026-11-21&to=2026-11-21").with(accountant()));

        assertThat(page).contains("INV/2026-27/0001").contains("Retailer A").contains("7,500.00").contains("187.50")
                .contains("7,875.00").contains("TOTAL");
    }

    @Test
    void theStockSummaryShowsTheOfficialClosingStock() throws Exception {
        String page = body(get("/reports/stock-summary?" + aroundToday()).with(accountant()));

        assertThat(page).contains("Flour").contains("120 kg").contains("19.5 L").contains("306 pcs").contains("88 packets");
    }

    @Test
    void theCostReportShowsTheVerifiedCostAndMargin() throws Exception {
        String page = body(get("/reports/cost-per-packet?from=2026-11-21&to=2026-11-21").with(accountant()));

        assertThat(page).contains("3,114.3617").contains("31.14").contains("37.71");
    }

    @Test
    void theYieldReportFlagsTheOfficialMinusSixPercent() throws Exception {
        String page = body(get("/reports/production-yield?from=2026-11-21&to=2026-11-21").with(accountant()));

        assertThat(page).contains("-6.00").contains("FLAGGED");
    }

    @Test
    void theOutstandingReportShowsWhatRetailerAOwes() throws Exception {
        String page = body(get("/reports/outstanding?asOf=2026-11-21").with(accountant()));

        assertThat(page).contains("Retailer A").contains("7,875.00");
    }

    @Test
    void anEmptyPeriodShowsAFriendlyMessage() throws Exception {
        String page = body(get("/reports/sales-register?from=2020-01-01&to=2020-01-31").with(accountant()));

        assertThat(page).contains("No data for this period");
    }

    @Test
    void aBackwardsPeriodOrMissingItemGoesBackToTheFormWithAMessage() throws Exception {
        var backwards = mvc.perform(get("/reports/sales-register?from=2026-12-01&to=2026-11-01").with(accountant()))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(backwards.getFlashMap().get("error")).isNotNull();

        var noItem = mvc.perform(get("/reports/stock-ledger?from=2026-11-01&to=2026-11-30").with(accountant()))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(noItem.getFlashMap().get("error").toString()).contains("Choose an item");
    }

    @Test
    void anUnknownReportIsA404() throws Exception {
        mvc.perform(get("/reports/nonsense").with(accountant())).andExpect(status().isNotFound());
    }

    // ---- downloads

    @Test
    void theCsvDownloadHasTheRightHeadersAndContent() throws Exception {
        MockHttpServletResponse response = download("/reports/sales-register/download?format=csv&from=2026-11-21&to=2026-11-21");

        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition")).contains("attachment").contains("sales-register.csv");
        String csv = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿").contains("INV/2026-27/0001").contains("7875.00");
    }

    @Test
    void theExcelDownloadIsAZipContainer() throws Exception {
        MockHttpServletResponse response = download("/reports/gst-summary/download?format=xlsx&from=2026-11-21&to=2026-11-21");

        assertThat(response.getHeader("Content-Disposition")).contains(".xlsx");
        assertThat(new String(response.getContentAsByteArray(), 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
    }

    @Test
    void thePdfDownloadIsAPdf() throws Exception {
        MockHttpServletResponse response = download("/reports/outstanding/download?format=pdf&asOf=2026-11-21");

        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(response.getContentAsByteArray(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void anUnknownFormatIsABadRequest() throws Exception {
        mvc.perform(get("/reports/sales-register/download?format=docx").with(accountant()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theFormRedirectsToTheRightAddress() throws Exception {
        String csv = mvc.perform(get("/reports/run?slug=sales-register&format=csv&from=2026-11-01&to=2026-11-30").with(accountant()))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        String screen = mvc.perform(get("/reports/run?slug=sales-register&format=html&from=2026-11-01&to=2026-11-30").with(accountant()))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();

        assertThat(csv).isEqualTo("/reports/sales-register/download?format=csv&from=2026-11-01&to=2026-11-30");
        assertThat(screen).isEqualTo("/reports/sales-register?from=2026-11-01&to=2026-11-30");
    }

    @Test
    void theScreenPageLinksToTheDownloads() throws Exception {
        String page = body(get("/reports/sales-register?from=2026-11-21&to=2026-11-21").with(accountant()));

        assertThat(page).contains("/reports/sales-register/download?from=2026-11-21&amp;to=2026-11-21&amp;format=csv");
    }

    // ---- who may open what

    @Test
    void salesmenAndProductionStaffCannotOpenReports() throws Exception {
        mvc.perform(get("/reports").with(user("s").roles("SALESMAN"))).andExpect(status().isForbidden());
        mvc.perform(get("/reports").with(user("p").roles("PRODUCTION_SUPERVISOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/reports/sales-register/download?format=csv").with(user("s").roles("SALESMAN")))
                .andExpect(status().isForbidden());
    }

    // ---- stock screens

    @Test
    void theStockPageShowsEveryItemInReadableUnits() throws Exception {
        String page = body(get("/stock").with(accountant()));

        assertThat(page).contains("Flour").contains("120 kg").contains("Oil").contains("19.5 L")
                .contains("Murukku 200 g").contains("88 packets");
    }

    @Test
    void theItemHistoryShowsEveryMovementWithARunningBalance() throws Exception {
        String page = body(get("/stock/items/" + f.murukku.getId()).with(user("p").roles("PRODUCTION_SUPERVISOR")));

        assertThat(page).contains("Brought forward").contains("OPENING").contains("PROD_OUTPUT").contains("SALE")
                .contains("238").contains("88");
    }

    @Test
    void theAccountantMayLookAtStockButNotChangeIt() throws Exception {
        mvc.perform(post("/stock/anything").with(accountant()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/stock").with(user("s").roles("SALESMAN"))).andExpect(status().isForbidden());
    }

    // ---- owner dashboard

    @Test
    void theOwnerDashboardShowsTheOfficialNumbers() throws Exception {
        String page = body(get("/owner?from=2026-11-01&to=2026-11-30").with(owner()));

        assertThat(page).contains("id=\"kpi-sales\">Rs 7,875.00<").contains("id=\"kpi-outstanding\">Rs 7,875.00<")
                .contains("id=\"kpi-stock\">88<").contains("id=\"kpi-waiting\">0<").contains("id=\"kpi-flagged\">1<")
                .contains("id=\"kpi-cost\">Rs 31.14<").contains("id=\"kpi-margin\">37.71%<").contains("Retailer A");
    }

    @Test
    void theDashboardIsForTheOwnerOnly() throws Exception {
        mvc.perform(get("/owner").with(user("a").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/owner").with(accountant())).andExpect(status().isForbidden());
    }

    @Test
    void theDashboardDrawsTheSalesAndAgeingCharts() throws Exception {
        String page = body(get("/owner?from=2026-11-01&to=2026-11-30").with(owner()));
        assertThat(page).contains("id=\"chart-sales\"").contains("id=\"chart-ageing\"").contains("Chart");
    }

    @Test
    void theChartNumbersAddUpToTheKpiNumbers() {
        var d = dashboardService.compute(new ReportPeriod(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)));
        assertThat(d.salesAmounts().stream().mapToLong(a -> a.movePointRight(2).longValueExact()).sum())
                .isEqualTo(d.salesInclGstPaise()).isEqualTo(787_500);
        assertThat(d.ageingAmounts().stream().mapToLong(a -> a.movePointRight(2).longValueExact()).sum())
                .isEqualTo(d.outstandingPaise());
        assertThat(d.salesDays()).hasSameSizeAs(d.salesAmounts());
    }

    @Test
    void theHomePageShowsRoleTiles() throws Exception {
        String owner = body(get("/").with(owner()));
        assertThat(owner).contains("Owner dashboard").contains("Reports");
        String salesman = body(get("/").with(user("ravi").roles("SALESMAN")));
        assertThat(salesman).contains("New order").doesNotContain("Owner dashboard");
    }
}
