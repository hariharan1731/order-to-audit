package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.AllocationRequest;
import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.PaymentMethod;
import com.orderaudit.domain.ProductionRun;
import com.orderaudit.domain.SalesOrder;
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

/** Every important business action leaves an audit row, written in the same transaction. */
@SpringBootTest
@Testcontainers
@Transactional
class AuditAcrossWorkflowIntegrationTest {

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
    @Autowired ProductionService productionService;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired InvoiceService invoiceService;
    @Autowired DispatchService dispatchService;
    @Autowired ReceivablesService receivables;
    @Autowired AuditService audit;

    private BenchmarkFixture f;

    private ProductionRun run() {
        f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
        return productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(new ActualLine(f.oil.getId(), 10_500), new ActualLine(f.pouch.getId(), 194),
                        new ActualLine(f.carton.getId(), 9)),
                List.of(new OverheadInput("Labour", 120_000), new OverheadInput("Gas and power", 60_000)), "production");
    }

    private SalesOrder order(Customer customer, long packets) {
        return orderService.submit(customer.getId(), "salesman-a", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), packets)));
    }

    @Test
    void productionPostingAndReversalAreAudited() {
        ProductionRun run = run();
        productionService.reverse(run.getId(), "supervisor2");

        List<AuditEntry> trail = audit.forEntity("RUN", run.getId());

        assertThat(trail).extracting(AuditEntry::action).containsExactly("PRODUCTION_POSTED", "PRODUCTION_REVERSED");
        assertThat(trail.get(0).actor()).isEqualTo("production");
        assertThat(trail.get(0).details()).contains("188 good packets");
        assertThat(trail.get(1).actor()).isEqualTo("supervisor2");
    }

    @Test
    void theWholeSalesChainIsAuditedWithWhoDidEachStep() {
        run();
        Customer retailerA = customerService.create("Retailer A", null, "33", null);
        priceService.setListPrice(f.murukku.getId(), 5_000);
        SalesOrder order = order(retailerA, 150);
        orderService.approve(order.getId(), "admin1");
        Invoice invoice = invoiceService.raise(order.getId(), DAY, "admin2");
        dispatchService.dispatch(order.getId(), "admin3");
        long paymentId = receivables.recordPayment(retailerA.getId(), DAY, PaymentMethod.CASH, "R-1", "accountant1",
                List.of(new AllocationRequest(invoice.getId(), 400_000))).getId();

        List<AuditEntry> orderTrail = audit.forEntity("ORDER", order.getId());
        assertThat(orderTrail).extracting(AuditEntry::action)
                .containsExactly("ORDER_SUBMITTED", "ORDER_APPROVED", "ORDER_DISPATCHED");
        assertThat(orderTrail).extracting(AuditEntry::actor).containsExactly("salesman-a", "admin1", "admin3");

        AuditEntry invoiceEntry = audit.forEntity("INVOICE", invoice.getId()).get(0);
        assertThat(invoiceEntry.action()).isEqualTo("INVOICE_RAISED");
        assertThat(invoiceEntry.actor()).isEqualTo("admin2");
        assertThat(invoiceEntry.details()).contains("INV/2026-27/0001").contains("787500");

        AuditEntry paymentEntry = audit.forEntity("PAYMENT", paymentId).get(0);
        assertThat(paymentEntry.actor()).isEqualTo("accountant1");
        assertThat(paymentEntry.details()).contains("400000");
    }

    @Test
    void aRejectedOrderRecordsTheReason() {
        run();
        Customer c = customerService.create("Retailer A", null, "33", null);
        priceService.setListPrice(f.murukku.getId(), 5_000);
        SalesOrder order = order(c, 10);

        orderService.reject(order.getId(), "admin1", "Customer over credit limit");

        List<AuditEntry> trail = audit.forEntity("ORDER", order.getId());
        assertThat(trail).extracting(AuditEntry::action).containsExactly("ORDER_SUBMITTED", "ORDER_REJECTED");
        assertThat(trail.get(1).details()).isEqualTo("Customer over credit limit");
    }

    @Test
    void aFailedActionLeavesNoAuditRow() {
        run();
        Customer c = customerService.create("Retailer A", null, "33", null);
        priceService.setListPrice(f.murukku.getId(), 5_000);
        SalesOrder tooBig = order(c, 500);                          // only 188 + 50 in stock

        assertThatThrownBy(() -> orderService.approve(tooBig.getId(), "admin1")).isInstanceOf(ShortageException.class);

        // Only the submission is logged; the failed approval rolled back together with its audit row.
        assertThat(audit.forEntity("ORDER", tooBig.getId())).extracting(AuditEntry::action)
                .containsExactly("ORDER_SUBMITTED");
    }
}
