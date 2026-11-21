package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.InvoiceLine;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.OrderStatus;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.PurchaseLineSpec;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.SourceType;
import com.orderaudit.domain.StockCheckLine;
import com.orderaudit.domain.StockMovement;
import com.orderaudit.domain.Unit;
import com.orderaudit.repository.SalesOrderRepository;
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
 * Real PostgreSQL, rolled back after every test. Benchmark steps 1-6 end to end through the real services:
 * stock after the run is 238; Retailer A (Tamil Nadu) orders 150; Distributor B (Karnataka) orders 120.
 */
@SpringBootTest
@Testcontainers
@Transactional
class SalesBenchmarkIntegrationTest {

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
    @Autowired StockService stock;
    @Autowired SalesOrderRepository orderRepository;

    private BenchmarkFixture f;
    private Customer retailerA, distributorB;

    /** Steps 1-3: purchase, production run (188 good packets) -> finished goods = 238. Plus customers and the Rs 50 price. */
    private void setUpBenchmark() {
        f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
        purchaseService.record(f.supplier.getId(), DAY, "PO-1",
                List.of(new PurchaseLineSpec(f.flour.getId(), 60_000, new BigDecimal("4.5"))), "admin");
        productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(new ActualLine(f.oil.getId(), 10_500), new ActualLine(f.pouch.getId(), 194),
                        new ActualLine(f.carton.getId(), 9)),
                List.of(new OverheadInput("Labour", 120_000), new OverheadInput("Gas and power", 60_000)), "production");
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(238);

        retailerA = customerService.create("Retailer A", null, "33", null);       // Tamil Nadu
        distributorB = customerService.create("Distributor B", null, "29", null); // Karnataka
        priceService.setListPrice(f.murukku.getId(), 5_000);                      // Rs 50 per packet before tax
    }

    private SalesOrder order(Customer customer, long packets) {
        return orderService.submit(customer.getId(), "salesman-a", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), packets)));
    }

    /** Steps 4-5: Retailer A orders 150; admin approves, invoices, dispatches. */
    private Invoice retailerAFlow() {
        SalesOrder order = order(retailerA, 150);
        orderService.approve(order.getId(), "admin");
        Invoice invoice = invoiceService.raise(order.getId(), DAY, "admin");
        dispatchService.dispatch(order.getId(), "admin");
        return invoice;
    }

    @Test
    void retailerAOrderBecomesTheOfficialInvoiceAndDispatchLeaves88() {
        setUpBenchmark();
        SalesOrder order = order(retailerA, 150);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(orderService.linesOf(order.getId()).get(0).getUnitPricePaise()).isEqualTo(5_000);

        StockCheckLine check = orderService.stockCheck(order.getId()).get(0);
        assertThat(check.requested()).isEqualTo(150);
        assertThat(check.onHand()).isEqualTo(238);
        assertThat(check.shortage()).isZero();

        assertThat(orderService.approve(order.getId(), "admin").getStatus()).isEqualTo(OrderStatus.APPROVED);
        Invoice invoice = invoiceService.raise(order.getId(), DAY, "admin");

        assertThat(invoice.getInvoiceNumber()).isEqualTo("INV/2026-27/0001");
        assertThat(invoice.isIntraState()).isTrue();
        assertThat(invoice.getSupplyState()).isEqualTo("33");
        assertThat(invoice.getTaxablePaise()).isEqualTo(750_000);        // OFFICIAL Rs 7,500 taxable
        assertThat(invoice.getCgstPaise()).isEqualTo(18_750);            // OFFICIAL Rs 187.50 CGST
        assertThat(invoice.getSgstPaise()).isEqualTo(18_750);            // OFFICIAL Rs 187.50 SGST
        assertThat(invoice.getIgstPaise()).isZero();
        assertThat(invoice.getTotalPaise()).isEqualTo(787_500);          // OFFICIAL Rs 7,875
        List<InvoiceLine> invoiceLines = invoiceService.linesOf(invoice.getId());
        assertThat(invoiceLines).hasSize(1);
        assertThat(invoiceLines.get(0).getQuantity()).isEqualTo(150);
        assertThat(invoiceLines.get(0).getGstRatePercent()).isEqualByComparingTo("5");
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.INVOICED);
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(238);   // invoicing does not move stock

        dispatchService.dispatch(order.getId(), "admin");

        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(88);    // OFFICIAL 88
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.DISPATCHED);
        StockMovement sale = stock.ledgerFor(f.murukku.getId()).get(2);
        assertThat(sale.getMovementType()).isEqualTo(MovementType.SALE);
        assertThat(sale.getSourceType()).isEqualTo(SourceType.DISPATCH);
        assertThat(sale.getSourceId()).isEqualTo(order.getId());
        assertThat(sale.getQtySigned()).isEqualTo(-150);
        assertThat(stock.reconcile()).isEmpty();
    }

    @Test
    void distributorBOrderOf120ShowsAShortageOf32AndIgstAppliesWhenReducedAndInvoiced() {
        setUpBenchmark();
        retailerAFlow();                                  // finished goods now 88
        SalesOrder orderB = order(distributorB, 120);

        StockCheckLine check = orderService.stockCheck(orderB.getId()).get(0);
        assertThat(check.available()).isEqualTo(88);
        assertThat(check.shortage()).isEqualTo(32);                       // OFFICIAL shortage of 32 flagged

        assertThatThrownBy(() -> orderService.approve(orderB.getId(), "admin"))
                .isInstanceOfSatisfying(ShortageException.class, e -> {
                    assertThat(e.getShortages()).hasSize(1);
                    assertThat(e.getShortages().get(0).shortage()).isEqualTo(32);
                });
        assertThat(orderRepository.findById(orderB.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.SUBMITTED);

        // The admin reduces the order to what is available; it can now be approved and invoiced.
        orderService.changeLine(orderB.getId(), f.murukku.getId(), 88, 5_000);
        orderService.approve(orderB.getId(), "admin");
        Invoice invoice = invoiceService.raise(orderB.getId(), DAY, "admin");

        assertThat(invoice.isIntraState()).isFalse();
        assertThat(invoice.getCustomerState()).isEqualTo("29");
        assertThat(invoice.getTaxablePaise()).isEqualTo(440_000);
        assertThat(invoice.getIgstPaise()).isEqualTo(22_000);             // IGST 5% (CGST/SGST do not apply)
        assertThat(invoice.getCgstPaise()).isZero();
        assertThat(invoice.getSgstPaise()).isZero();
        assertThat(invoice.getTotalPaise()).isEqualTo(462_000);
        assertThat(invoice.getInvoiceNumber()).isEqualTo("INV/2026-27/0002");   // gapless: next number
    }

    @Test
    void anApprovedOrderReservesStockBeforeDispatchAndIsNotCountedTwiceAfterwards() {
        setUpBenchmark();
        SalesOrder orderA = order(retailerA, 150);
        orderService.approve(orderA.getId(), "admin");                    // approved, NOT dispatched yet
        SalesOrder orderB = order(distributorB, 120);

        StockCheckLine before = orderService.stockCheck(orderB.getId()).get(0);
        assertThat(before.onHand()).isEqualTo(238);                       // stock has not fallen...
        assertThat(before.reserved()).isEqualTo(150);                     // ...but 150 are promised
        assertThat(before.available()).isEqualTo(88);
        assertThat(before.shortage()).isEqualTo(32);

        invoiceService.raise(orderA.getId(), DAY, "admin");
        dispatchService.dispatch(orderA.getId(), "admin");

        StockCheckLine after = orderService.stockCheck(orderB.getId()).get(0);
        assertThat(after.onHand()).isEqualTo(88);
        assertThat(after.reserved()).isZero();                            // no longer promised, it is gone
        assertThat(after.available()).isEqualTo(88);                      // still 88, never double-counted
        assertThat(after.shortage()).isEqualTo(32);
    }

    @Test
    void anOrderCannotBeInvoicedBeforeApprovalOrTwice() {
        setUpBenchmark();
        SalesOrder order = order(retailerA, 10);

        assertThatThrownBy(() -> invoiceService.raise(order.getId(), DAY, "admin"))
                .isInstanceOf(IllegalStateException.class);
        orderService.approve(order.getId(), "admin");
        invoiceService.raise(order.getId(), DAY, "admin");

        assertThatThrownBy(() -> invoiceService.raise(order.getId(), DAY, "admin"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anOrderCannotBeDispatchedBeforeInvoiceOrTwice() {
        setUpBenchmark();
        SalesOrder order = order(retailerA, 10);
        orderService.approve(order.getId(), "admin");

        assertThatThrownBy(() -> dispatchService.dispatch(order.getId(), "admin"))
                .isInstanceOf(IllegalStateException.class);
        invoiceService.raise(order.getId(), DAY, "admin");
        dispatchService.dispatch(order.getId(), "admin");

        assertThatThrownBy(() -> dispatchService.dispatch(order.getId(), "admin"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(228);    // sold once only
    }

    @Test
    void dispatchChecksTheRealStockEvenIfTheReservationWasBypassed() {
        setUpBenchmark();
        SalesOrder order = order(retailerA, 200);
        orderService.approve(order.getId(), "admin");
        invoiceService.raise(order.getId(), DAY, "admin");
        // Someone sells 100 packets straight through the stock service, bypassing the order flow.
        stock.post(f.murukku.getId(), MovementType.SALE, -100, null, SourceType.DISPATCH, 999, "admin");

        assertThatThrownBy(() -> dispatchService.dispatch(order.getId(), "admin"))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void rejectingNeedsAReasonAndARejectedOrderCannotBeApproved() {
        setUpBenchmark();
        SalesOrder order = order(retailerA, 10);

        assertThatThrownBy(() -> orderService.reject(order.getId(), "admin", " "))
                .isInstanceOf(IllegalArgumentException.class);
        SalesOrder rejected = orderService.reject(order.getId(), "admin", "Customer cancelled");

        assertThat(rejected.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejected.getRejectReason()).isEqualTo("Customer cancelled");
        assertThatThrownBy(() -> orderService.approve(order.getId(), "admin")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resendingTheSameClientRequestIdDoesNotCreateADuplicateOrder() {
        setUpBenchmark();
        List<OrderLineRequest> lines = List.of(new OrderLineRequest(f.murukku.getId(), 10));

        SalesOrder first = orderService.submit(retailerA.getId(), "salesman-a", "phone-req-1", null, lines);
        SalesOrder again = orderService.submit(retailerA.getId(), "salesman-a", "phone-req-1", null, lines);

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(orderService.ordersOfSalesman("salesman-a")).hasSize(1);
    }

    @Test
    void aCustomerSpecificPriceBeatsTheDefaultPrice() {
        setUpBenchmark();
        priceService.setCustomerPrice(retailerA.getId(), f.murukku.getId(), 4_500);

        SalesOrder forA = order(retailerA, 10);
        SalesOrder forB = order(distributorB, 10);

        assertThat(orderService.linesOf(forA.getId()).get(0).getUnitPricePaise()).isEqualTo(4_500);
        assertThat(orderService.linesOf(forB.getId()).get(0).getUnitPricePaise()).isEqualTo(5_000);
    }

    @Test
    void anAdminPriceEditBeforeApprovalFlowsIntoTheInvoice() {
        setUpBenchmark();
        SalesOrder order = order(retailerA, 100);
        orderService.changeLine(order.getId(), f.murukku.getId(), 100, 4_800);
        orderService.approve(order.getId(), "admin");

        Invoice invoice = invoiceService.raise(order.getId(), DAY, "admin");

        assertThat(invoice.getTaxablePaise()).isEqualTo(480_000);
        assertThat(invoice.getCgstPaise()).isEqualTo(12_000);
        assertThat(invoice.getSgstPaise()).isEqualTo(12_000);
    }

    @Test
    void anOrderForAProductWithNoPriceIsRefused() {
        setUpBenchmark();
        var other = itemService.create("Mixture 200 g", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));

        assertThatThrownBy(() -> orderService.submit(retailerA.getId(), "salesman-a", null, null,
                List.of(new OrderLineRequest(other.getId(), 5))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No price");
    }

    @Test
    void invalidOrdersAreRefused() {
        setUpBenchmark();
        long murukku = f.murukku.getId();

        assertThatThrownBy(() -> orderService.submit(retailerA.getId(), "s", null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderService.submit(retailerA.getId(), "s", null, null,
                List.of(new OrderLineRequest(murukku, 0)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderService.submit(retailerA.getId(), "s", null, null,
                List.of(new OrderLineRequest(murukku, 5), new OrderLineRequest(murukku, 6))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> orderService.submit(retailerA.getId(), "s", null, null,
                List.of(new OrderLineRequest(f.flour.getId(), 5))))                  // raw material, not a product
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be ordered");
        assertThatThrownBy(() -> orderService.submit(999_999L, "s", null, null,
                List.of(new OrderLineRequest(murukku, 5)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderService.submit(retailerA.getId(), " ", null, null,
                List.of(new OrderLineRequest(murukku, 5)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invoiceNumbersRestartInANewFinancialYearAndStayGaplessWithinOne() {
        setUpBenchmark();
        SalesOrder o1 = order(retailerA, 5);
        SalesOrder o2 = order(retailerA, 5);
        SalesOrder o3 = order(retailerA, 5);
        orderService.approve(o1.getId(), "admin");
        orderService.approve(o2.getId(), "admin");
        orderService.approve(o3.getId(), "admin");

        String n1 = invoiceService.raise(o1.getId(), LocalDate.of(2027, 3, 30), "admin").getInvoiceNumber();
        String n2 = invoiceService.raise(o2.getId(), LocalDate.of(2027, 3, 31), "admin").getInvoiceNumber();
        String n3 = invoiceService.raise(o3.getId(), LocalDate.of(2027, 4, 1), "admin").getInvoiceNumber();

        assertThat(n1).isEqualTo("INV/2026-27/0001");
        assertThat(n2).isEqualTo("INV/2026-27/0002");
        assertThat(n3).isEqualTo("INV/2027-28/0001");
    }
}
