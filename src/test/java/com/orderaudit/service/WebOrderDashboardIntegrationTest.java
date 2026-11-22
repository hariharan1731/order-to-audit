package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.Customer;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.OrderStatus;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.report.ReportPeriod;
import jakarta.persistence.EntityManager;
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
 * The order dashboard: summary cards, search, status filter, date filter and sorting, on the official data.
 * Retailer A's order is dispatched and invoiced (Rs 7,875 with CGST+SGST); Distributor B's 120 packets are
 * still waiting and would carry IGST (Karnataka), so the two rows exercise both tax cases.
 */
@SpringBootTest
@Testcontainers
@Transactional
class WebOrderDashboardIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
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
    @Autowired OrderQueryService queries;

    MockMvc mvc;
    BenchmarkFixture f;
    SalesOrder retailerOrder;
    SalesOrder distributorOrder;
    String invoiceNumber;

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
        Customer distributorB = customerService.create("Distributor B", null, "29", null);
        priceService.setListPrice(f.murukku.getId(), 5_000);

        retailerOrder = orderService.submit(retailerA.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), 150)));
        orderService.approve(retailerOrder.getId(), "admin1");
        invoiceNumber = invoiceService.raise(retailerOrder.getId(), DAY, "admin1").getInvoiceNumber();
        dispatchService.dispatch(retailerOrder.getId(), "admin1");

        distributorOrder = orderService.submit(distributorB.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), 120)));
    }

    private static RequestPostProcessor ravi() {
        return user("ravi").roles("SALESMAN");
    }

    private static RequestPostProcessor admin() {
        return user("admin1").roles("ADMIN");
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    // ---------- summary cards ----------

    @Test
    void theSalesmansCardsCountHisOwnOrders() throws Exception {
        String page = body(get("/sales/orders").with(ravi()));
        assertThat(page).contains("id=\"stat-total\">2<")          // both orders are his
                .contains("id=\"stat-submitted\">1<")              // Distributor B waits for approval
                .contains("id=\"stat-pending\">1<")                // pending = not dispatched yet
                .contains("id=\"stat-approved\">0<")
                .contains("id=\"stat-completed\">1<");             // Retailer A has been dispatched
    }

    @Test
    void theSalesmanSeesHisSalesCustomerWise() throws Exception {
        String page = body(get("/sales/orders").with(ravi()));
        String table = page.substring(page.indexOf("id=\"customer-sales\""));
        // Retailer A: invoiced and dispatched, Rs 7,875 incl. CGST+SGST. Distributor B: waiting, 120 x 50 + 5% IGST.
        assertThat(table).contains("Retailer A").contains("Rs 7,875.00")
                .contains("Distributor B").contains("Rs 6,300.00");
        assertThat(table.indexOf("Retailer A")).isLessThan(table.indexOf("Distributor B"));   // biggest billed first

        List<OrderQueryService.CustomerSales> rows =
                OrderQueryService.customerWise(queries.search("ravi", null, null, null, null, null));
        assertThat(rows).extracting(OrderQueryService.CustomerSales::customerName)
                .containsExactly("Retailer A", "Distributor B");
        assertThat(rows.get(0).billedPaise()).isEqualTo(787_500);
        assertThat(rows.get(0).pendingPaise()).isZero();
        assertThat(rows.get(1).packets()).isEqualTo(120);
        assertThat(rows.get(1).billedPaise()).isZero();
        assertThat(rows.get(1).pendingPaise()).isEqualTo(630_000);
    }

    @Test
    void aRejectedOrderIsNotCountedInCustomerWiseSales() {
        orderService.reject(distributorOrder.getId(), "admin1", "Not enough stock");
        List<OrderQueryService.CustomerSales> rows =
                OrderQueryService.customerWise(queries.search("ravi", null, null, null, null, null));
        assertThat(rows).extracting(OrderQueryService.CustomerSales::customerName).containsExactly("Retailer A");
    }

    @Test
    void theDeskCardsCountEveryOrderInTheFactory() throws Exception {
        String page = body(get("/admin/orders").with(admin()));
        assertThat(page).contains("id=\"stat-total\">2<").contains("id=\"stat-submitted\">1<")
                .contains("id=\"stat-completed\">1<").contains("id=\"stat-rejected\">0<");
    }

    @Test
    void theCardsFollowTheWorkflow() {
        OrderQueryService.OrderStats before = queries.stats(null);
        assertThat(before.submitted()).isEqualTo(1);
        assertThat(before.approved()).isZero();
        assertThat(before.rejected()).isZero();
        assertThat(before.pending()).isEqualTo(1);

        // Only 88 packets are left, so this order of 120 is cut to 88, approved, and the cards move with it.
        orderService.changeLine(distributorOrder.getId(), f.murukku.getId(), 88, 5_000);
        orderService.approve(distributorOrder.getId(), "admin1");

        OrderQueryService.OrderStats afterApproval = queries.stats(null);
        assertThat(afterApproval.submitted()).isZero();
        assertThat(afterApproval.approved()).isEqualTo(1);
        assertThat(afterApproval.pending()).isEqualTo(1);
        assertThat(afterApproval.completed()).isEqualTo(1);
        assertThat(afterApproval.total()).isEqualTo(2);
    }

    @Test
    void aRejectedOrderIsCountedSeparatelyAndIsNotPending() {
        orderService.reject(distributorOrder.getId(), "admin1", "No stock this week");

        OrderQueryService.OrderStats stats = queries.stats(null);
        assertThat(stats.rejected()).isEqualTo(1);
        assertThat(stats.submitted()).isZero();
        assertThat(stats.pending()).isZero();
        assertThat(stats.total()).isEqualTo(2);
    }

    // ---------- the order row ----------

    @Test
    void eachRowShowsProductQuantityAmountGstAndTotal() throws Exception {
        String page = body(get("/sales/orders").with(ravi()));
        assertThat(page).contains("Murukku 200 g").contains("150 packets").contains("Rs 7,500.00")
                .contains("Rs 7,875.00")            // Retailer A, invoiced: 7,500 + 187.50 + 187.50
                .contains("Rs 6,300.00")            // Distributor B, expected: 6,000 + 300 IGST
                .contains(invoiceNumber);
    }

    @Test
    void gstIsTheRealInvoiceOnceRaisedAndAnExpectationBeforeThat() {
        List<OrderQueryService.OrderSummary> rows = queries.mine("ravi");
        OrderQueryService.OrderSummary invoiced = rows.stream()
                .filter(o -> o.id() == retailerOrder.getId()).findFirst().orElseThrow();
        OrderQueryService.OrderSummary waiting = rows.stream()
                .filter(o -> o.id() == distributorOrder.getId()).findFirst().orElseThrow();

        assertThat(invoiced.gstEstimated()).isFalse();
        assertThat(invoiced.gstPaise()).isEqualTo(37_500);          // 187.50 + 187.50
        assertThat(invoiced.totalWithGstPaise()).isEqualTo(787_500);
        assertThat(invoiced.invoiceNumber()).isEqualTo(invoiceNumber);

        assertThat(waiting.gstEstimated()).isTrue();
        assertThat(waiting.gstPaise()).isEqualTo(30_000);           // 5% IGST on Rs 6,000
        assertThat(waiting.totalWithGstPaise()).isEqualTo(630_000);
        assertThat(waiting.invoiceNumber()).isNull();
    }

    // ---------- search ----------

    @Test
    void searchingByCustomerNameNarrowsTheList() throws Exception {
        String page = body(get("/sales/orders").with(ravi()).param("q", "distributor"));
        assertThat(page).contains("Distributor B").doesNotContain("Retailer A");
    }

    @Test
    void searchingByOrderNumberFindsExactlyOneOrder() throws Exception {
        String page = body(get("/sales/orders").with(ravi()).param("q", "#" + retailerOrder.getId()));
        assertThat(page).contains("Retailer A").doesNotContain("Distributor B");
        assertThat(queries.search("ravi", String.valueOf(retailerOrder.getId()), null, null, null, "newest"))
                .hasSize(1);
    }

    @Test
    void searchingByInvoiceNumberFindsTheInvoicedOrder() {
        List<OrderQueryService.OrderSummary> rows =
                queries.search(null, invoiceNumber, null, null, null, "newest");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).id()).isEqualTo(retailerOrder.getId());
    }

    @Test
    void aSearchThatMatchesNothingShowsTheEmptyState() throws Exception {
        String page = body(get("/sales/orders").with(ravi()).param("q", "nobody-by-this-name"));
        assertThat(page).contains("No orders to show").doesNotContain("Retailer A");
    }

    @Test
    void aSalesmanNeverSeesAnotherSalesmansOrder() throws Exception {
        Customer walkIn = customerService.create("Walk-in shop", null, "33", null);
        orderService.submit(walkIn.getId(), "meena", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), 2)));

        assertThat(body(get("/sales/orders").with(ravi()))).doesNotContain("Walk-in shop");
        assertThat(queries.search("ravi", "Walk-in", null, null, null, "newest")).isEmpty();
        assertThat(queries.search("meena", null, null, null, null, "newest")).hasSize(1);
        assertThat(body(get("/admin/orders").with(admin()).param("q", "Walk-in"))).contains("Walk-in shop");
    }

    // ---------- filters and sorting ----------

    @Test
    void filteringByStatusShowsOnlyThatStatus() throws Exception {
        String submitted = body(get("/sales/orders").with(ravi()).param("status", "SUBMITTED"));
        assertThat(submitted).contains("Distributor B").doesNotContain("Retailer A");

        String dispatched = body(get("/sales/orders").with(ravi()).param("status", "DISPATCHED"));
        assertThat(dispatched).contains("Retailer A").doesNotContain("Distributor B");
    }

    @Test
    void anUnknownStatusIsTreatedAsNoFilterInsteadOfCrashing() throws Exception {
        String page = body(get("/sales/orders").with(ravi()).param("status", "NOT_A_STATUS"));
        assertThat(page).contains("Retailer A").contains("Distributor B");
    }

    @Test
    void theDateFilterKeepsTodayAndDropsOtherDays() {
        em.flush();
        em.clear();                       // the database fills created_at, so read the orders back to get it
        LocalDate today = ReportPeriod.today();

        assertThat(queries.search("ravi", null, null, today, today, "newest")).hasSize(2);
        assertThat(queries.search("ravi", null, null, today.minusDays(5), today.minusDays(5), "newest")).isEmpty();
        assertThat(queries.search("ravi", null, null, today.plusDays(1), null, "newest")).isEmpty();
    }

    @Test
    void sortingPutsTheBiggestOrderOrTheFirstCustomerOnTop() {
        List<OrderQueryService.OrderSummary> byValue = queries.search("ravi", null, null, null, null, "amount");
        assertThat(byValue.get(0).id()).isEqualTo(retailerOrder.getId());        // Rs 7,875 beats Rs 6,300

        List<OrderQueryService.OrderSummary> byName = queries.search("ravi", null, null, null, null, "customer");
        assertThat(byName.get(0).customerName()).isEqualTo("Distributor B");     // D before R

        List<OrderQueryService.OrderSummary> newest = queries.search("ravi", null, null, null, null, "newest");
        assertThat(newest.get(0).id()).isEqualTo(distributorOrder.getId());
        List<OrderQueryService.OrderSummary> oldest = queries.search("ravi", null, null, null, null, "oldest");
        assertThat(oldest.get(0).id()).isEqualTo(retailerOrder.getId());
    }

    @Test
    void searchingTheDeskReplacesTheQueuesWithResults() throws Exception {
        String filtered = body(get("/admin/orders").with(admin()).param("status", "DISPATCHED"));
        assertThat(filtered).contains("Search results").doesNotContain("To approve");

        String plain = body(get("/admin/orders").with(admin()));
        assertThat(plain).contains("To approve").contains("To invoice").contains("To dispatch")
                .doesNotContain("Search results");
    }

    @Test
    void theMenuHighlightsThePageYouAreOn() throws Exception {
        assertThat(body(get("/sales/orders").with(ravi()))).contains("nav-link active");
        assertThat(body(get("/admin/orders").with(admin()))).contains("nav-link active");
        assertThat(body(get("/").with(ravi()))).doesNotContain("nav-link active");   // home has no menu entry
    }

    @Test
    void anEmptyFilterShowsEverythingAgain() throws Exception {
        String page = body(get("/sales/orders").with(ravi()).param("q", "").param("status", "")
                .param("sort", ""));
        assertThat(page).contains("Retailer A").contains("Distributor B");
        assertThat(queries.search("ravi", "", null, null, null, "")).hasSize(2);
    }
}
