package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.AllocationRequest;
import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.PaymentMethod;
import com.orderaudit.domain.PurchaseLineSpec;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.report.ExportedFile;
import com.orderaudit.report.ReportExportService;
import com.orderaudit.report.ReportFormat;
import com.orderaudit.report.ReportPeriod;
import com.orderaudit.report.ReportService;
import com.orderaudit.report.ReportTable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real PostgreSQL, rolled back after each test. Runs the OFFICIAL benchmark through the real services, then
 * checks that every report shows exactly the official figures.
 */
@SpringBootTest
@Testcontainers
@Transactional
class ReportServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);

    @Autowired ItemService itemService;
    @Autowired RecipeService recipeService;
    @Autowired PackConfigService packConfigService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired SupplierService supplierService;
    @Autowired CustomerService customerService;
    @Autowired PurchaseService purchaseService;
    @Autowired ProductionService productionService;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired InvoiceService invoiceService;
    @Autowired DispatchService dispatchService;
    @Autowired ReceivablesService receivables;
    @Autowired ReportService reports;
    @Autowired ReportExportService exporter;

    private BenchmarkFixture f;
    private Invoice invoiceA;

    /** Purchase, production run, Retailer A order (150) invoiced and dispatched, Rs 4,000 paid. */
    private void runBenchmark() {
        f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
        purchaseService.record(f.supplier.getId(), DAY, "PO-1",
                List.of(new PurchaseLineSpec(f.flour.getId(), 60_000, new BigDecimal("4.5"))), "admin");
        productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(new ActualLine(f.oil.getId(), 10_500), new ActualLine(f.pouch.getId(), 194),
                        new ActualLine(f.carton.getId(), 9)),
                List.of(new OverheadInput("Labour", 120_000), new OverheadInput("Gas and power", 60_000)), "production");
        Customer retailerA = customerService.create("Retailer A", null, "33", null);
        priceService.setListPrice(f.murukku.getId(), 5_000);
        SalesOrder order = orderService.submit(retailerA.getId(), "salesman-a", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), 150)));
        orderService.approve(order.getId(), "admin");
        invoiceA = invoiceService.raise(order.getId(), DAY, "admin");
        dispatchService.dispatch(order.getId(), "admin");
        receivables.recordPayment(retailerA.getId(), DAY, PaymentMethod.CASH, "R-1", "admin",
                List.of(new AllocationRequest(invoiceA.getId(), 400_000)));
    }

    private static ReportPeriod dayOfBenchmark() {
        return new ReportPeriod(DAY, DAY);
    }

    /** Movements are stamped with the real clock, so stock reports use a window around today. */
    private static ReportPeriod aroundToday() {
        LocalDate today = ReportPeriod.today();
        return new ReportPeriod(today.minusDays(1), today.plusDays(1));
    }

    private static List<Object> rowWhere(ReportTable table, int column, Object value) {
        return table.rows().stream().filter(r -> value.equals(r.get(column))).findFirst().orElseThrow();
    }

    @Test
    void salesRegisterShowsTheOfficialInvoice() {
        runBenchmark();

        ReportTable table = reports.salesRegister(dayOfBenchmark());

        assertThat(table.rows()).hasSize(1);
        List<Object> row = table.rows().get(0);
        assertThat(row.get(1)).isEqualTo("INV/2026-27/0001");
        assertThat(row.get(2)).isEqualTo("Retailer A");
        assertThat(row.get(5)).isEqualTo(750_000L);
        assertThat(row.get(6)).isEqualTo(18_750L);
        assertThat(row.get(7)).isEqualTo(18_750L);
        assertThat(row.get(8)).isEqualTo(0L);
        assertThat(row.get(9)).isEqualTo(787_500L);
        assertThat(table.totals().get(9)).isEqualTo(787_500L);
    }

    @Test
    void customerAndProductSalesAgreeWithTheInvoice() {
        runBenchmark();

        ReportTable byCustomer = reports.customerWiseSales(dayOfBenchmark());
        ReportTable byProduct = reports.productWiseSales(dayOfBenchmark());

        assertThat(byCustomer.rows().get(0).subList(0, 5)).containsExactly("Retailer A", 1L, 750_000L, 37_500L, 787_500L);
        List<Object> product = byProduct.rows().get(0);
        assertThat(product.get(0)).isEqualTo("Murukku 200 g");
        assertThat(product.get(2)).isEqualTo(150L);
        assertThat(product.get(3)).isEqualTo(750_000L);
        assertThat(product.get(5)).isEqualTo(787_500L);
    }

    @Test
    void gstSummaryReportsOutwardSuppliesAtFivePercent() {
        runBenchmark();

        ReportTable table = reports.gstSummary(dayOfBenchmark());

        assertThat(table.rows()).hasSize(1);
        List<Object> row = table.rows().get(0);
        assertThat(row.get(0)).isEqualTo("B2C");                       // Retailer A has no GSTIN in the test data
        assertThat((BigDecimal) row.get(1)).isEqualByComparingTo("5");
        assertThat(row.get(2)).isEqualTo(750_000L);
        assertThat(row.get(3)).isEqualTo(18_750L);
        assertThat(row.get(4)).isEqualTo(18_750L);
        assertThat(row.get(5)).isEqualTo(0L);
        assertThat(table.totals().get(7)).isEqualTo(787_500L);
    }

    @Test
    void purchaseRegisterShowsTheFlourPurchase() {
        runBenchmark();

        ReportTable table = reports.purchaseRegister(dayOfBenchmark());

        List<Object> row = table.rows().get(0);
        assertThat(row.get(3)).isEqualTo("Flour");
        assertThat(row.get(4)).isEqualTo(60_000L);
        assertThat((BigDecimal) row.get(6)).isEqualByComparingTo("4.5");
        assertThat(row.get(7)).isEqualTo(270_000L);                     // 60 kg x Rs 45 = Rs 2,700
    }

    @Test
    void stockSummaryMatchesTheOfficialClosingStock() {
        runBenchmark();

        ReportTable table = reports.stockSummary(aroundToday());

        List<Object> flour = rowWhere(table, 0, "Flour");
        assertThat(flour.subList(3, 8)).containsExactly(100_000L, 60_000L, 40_000L, 0L, 120_000L);   // OFFICIAL 120 kg
        List<Object> oil = rowWhere(table, 0, "Oil");
        assertThat(oil.get(7)).isEqualTo(19_500L);                                                    // OFFICIAL 19.5 L
        List<Object> pouch = rowWhere(table, 0, "Pouch 200 g");
        assertThat(pouch.get(7)).isEqualTo(306L);                                                     // OFFICIAL 306
        List<Object> finished = rowWhere(table, 0, "Murukku 200 g");
        assertThat(finished.subList(3, 8)).containsExactly(50L, 188L, 150L, 0L, 88L);                 // OFFICIAL 88
    }

    @Test
    void aPeriodAfterAllActivityHasOpeningEqualToClosing() {
        runBenchmark();
        LocalDate tomorrow = ReportPeriod.today().plusDays(2);

        ReportTable table = reports.stockSummary(new ReportPeriod(tomorrow, tomorrow));

        List<Object> finished = rowWhere(table, 0, "Murukku 200 g");
        assertThat(finished.subList(3, 8)).containsExactly(88L, 0L, 0L, 0L, 88L);
    }

    @Test
    void stockLedgerShowsRunningBalancesOfFinishedGoods() {
        runBenchmark();

        ReportTable table = reports.stockLedger(f.murukku.getId(), aroundToday());

        List<Object> balances = table.rows().stream().map(r -> r.get(6)).toList();
        assertThat(balances).containsExactly(0L, 50L, 238L, 88L);       // brought forward, opening, run, dispatch
        assertThat(table.totals().get(6)).isEqualTo(88L);
    }

    @Test
    void yieldReportFlagsTheOfficialMinusSixPercent() {
        runBenchmark();

        ReportTable table = reports.productionYield(dayOfBenchmark());

        List<Object> run = table.rows().get(0);
        assertThat(run.get(3)).isEqualTo(200L);
        assertThat(run.get(4)).isEqualTo(188L);
        assertThat((BigDecimal) run.get(5)).isEqualByComparingTo("-6");
        assertThat(run.get(6)).isEqualTo("FLAGGED");
        assertThat(run.get(7)).isEqualTo(6L);
    }

    @Test
    void consumptionReportFlagsOilAtPlusFivePercent() {
        runBenchmark();

        ReportTable table = reports.productionConsumption(dayOfBenchmark());

        List<Object> oil = rowWhere(table, 3, "Oil");
        assertThat(oil.get(5)).isEqualTo(10_000L);
        assertThat(oil.get(6)).isEqualTo(10_500L);
        assertThat((BigDecimal) oil.get(7)).isEqualByComparingTo("5");
        assertThat(oil.get(8)).isEqualTo("FLAGGED");
    }

    @Test
    void costPerPacketAndMarginMatchTheVerifiedFigures() {
        runBenchmark();

        ReportTable table = reports.costPerPacket(dayOfBenchmark());

        List<Object> row = table.rows().get(0);
        assertThat(row.get(4)).isEqualTo(405_500L);
        assertThat(row.get(5)).isEqualTo(180_000L);
        assertThat(row.get(6)).isEqualTo(585_500L);
        assertThat((BigDecimal) row.get(7)).isEqualByComparingTo("3114.3617");
        assertThat(row.get(8)).isEqualTo(3_114L);
        assertThat(row.get(9)).isEqualTo(5_000L);
        assertThat((BigDecimal) row.get(11)).isEqualByComparingTo("37.71");
    }

    @Test
    void collectionsAndOutstandingMatchTheOfficialFigures() {
        runBenchmark();

        ReportTable collections = reports.collections(dayOfBenchmark());
        ReportTable outstanding = reports.outstandingAndAgeing(DAY);

        assertThat(collections.rows()).hasSize(1);
        assertThat(collections.rows().get(0).get(4)).isEqualTo("INV/2026-27/0001");
        assertThat(collections.totals().get(5)).isEqualTo(400_000L);
        assertThat(outstanding.rows().get(0).get(0)).isEqualTo("Retailer A");
        assertThat(outstanding.totals().get(5)).isEqualTo(387_500L);        // 7,875 - 4,000 = 3,875
    }

    @Test
    void aPeriodWithNoActivityGivesEmptyReports() {
        runBenchmark();
        ReportPeriod quiet = new ReportPeriod(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 31));

        assertThat(reports.salesRegister(quiet).rows()).isEmpty();
        assertThat(reports.purchaseRegister(quiet).rows()).isEmpty();
        assertThat(reports.productionYield(quiet).rows()).isEmpty();
        assertThat(reports.collections(quiet).rows()).isEmpty();
        assertThat(reports.salesRegister(quiet).totals().get(9)).isEqualTo(0L);
    }

    @Test
    void everyReportExportsInEveryFormat() {
        runBenchmark();
        ReportPeriod day = dayOfBenchmark();
        List<ReportTable> all = List.of(reports.salesRegister(day), reports.customerWiseSales(day),
                reports.productWiseSales(day), reports.gstSummary(day), reports.purchaseRegister(day),
                reports.stockSummary(aroundToday()), reports.stockLedger(f.flour.getId(), aroundToday()),
                reports.productionYield(day), reports.productionConsumption(day), reports.costPerPacket(day),
                reports.collections(day), reports.outstandingAndAgeing(DAY));

        assertThat(all).hasSize(12);
        for (ReportTable table : all) {
            for (ReportFormat format : ReportFormat.values()) {
                ExportedFile file = exporter.export(table, format);
                assertThat(file.content()).as(table.title() + " as " + format).isNotEmpty();
                assertThat(file.fileName()).endsWith("." + format.extension());
            }
        }
    }
}
