package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.AgeingRow;
import com.orderaudit.domain.AllocationRequest;
import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.Payment;
import com.orderaudit.domain.PaymentMethod;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.Unit;
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
 * Real PostgreSQL, rolled back after every test. Payments, outstanding and ageing.
 * Each test packet costs Rs 50 + 5% GST = Rs 52.50, so 10 packets = Rs 525.00 = 52,500 paise.
 */
@SpringBootTest
@Testcontainers
@Transactional
class ReceivablesIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);

    @Autowired ItemService itemService;
    @Autowired CustomerService customerService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired InvoiceService invoiceService;
    @Autowired ReceivablesService receivables;

    private Item product;

    private void setUp() {
        product = itemService.create("Receivables Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 10_000, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
    }

    private Customer customer(String name) {
        return customerService.create(name, null, "33", null);
    }

    private Invoice invoice(Customer customer, long packets, LocalDate date) {
        SalesOrder order = orderService.submit(customer.getId(), "salesman", null, null,
                List.of(new OrderLineRequest(product.getId(), packets)));
        orderService.approve(order.getId(), "admin");
        return invoiceService.raise(order.getId(), date, "admin");
    }

    @Test
    void officialPaymentOf4000AgainstTheRetailerAInvoiceLeaves3875Outstanding() {
        setUp();
        Customer retailerA = customer("Retailer A");
        Invoice invoice = invoice(retailerA, 150, TODAY);                       // Rs 7,875
        assertThat(invoice.getTotalPaise()).isEqualTo(787_500);

        Payment payment = receivables.recordPayment(retailerA.getId(), TODAY, PaymentMethod.UPI, "UPI-1", "admin",
                List.of(new AllocationRequest(invoice.getId(), 400_000)));   // Rs 4,000

        assertThat(payment.getAmountPaise()).isEqualTo(400_000);
        assertThat(receivables.outstandingOfInvoice(invoice.getId())).isEqualTo(387_500);   // OFFICIAL Rs 3,875
        assertThat(receivables.customerOutstanding(retailerA.getId())).isEqualTo(387_500);
    }

    @Test
    void payingTheRestClosesTheInvoice() {
        setUp();
        Customer c = customer("Closer");
        Invoice invoice = invoice(c, 10, TODAY);

        receivables.recordPayment(c.getId(), TODAY, PaymentMethod.CASH, null, "admin",
                List.of(new AllocationRequest(invoice.getId(), 20_000)));
        receivables.recordPayment(c.getId(), TODAY, PaymentMethod.CASH, null, "admin",
                List.of(new AllocationRequest(invoice.getId(), 32_500)));

        assertThat(receivables.outstandingOfInvoice(invoice.getId())).isZero();
        assertThat(receivables.openInvoices(c.getId())).isEmpty();
        assertThat(receivables.invoicesOf(c.getId())).hasSize(1);
    }

    @Test
    void invalidPaymentsAreRefused() {
        setUp();
        Customer c = customer("Payer");
        Customer other = customer("Someone else");
        Invoice mine = invoice(c, 10, TODAY);
        Invoice theirs = invoice(other, 10, TODAY);

        assertThatThrownBy(() -> receivables.recordPayment(c.getId(), TODAY, PaymentMethod.CASH, null, "admin",
                List.of(new AllocationRequest(mine.getId(), 52_501))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outstanding");
        assertThatThrownBy(() -> receivables.recordPayment(c.getId(), TODAY, PaymentMethod.CASH, null, "admin",
                List.of(new AllocationRequest(theirs.getId(), 100))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another customer");
        assertThatThrownBy(() -> receivables.recordPayment(c.getId(), TODAY, PaymentMethod.CASH, null, "admin",
                List.of(new AllocationRequest(mine.getId(), 0)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receivables.recordPayment(c.getId(), TODAY, PaymentMethod.CASH, null, "admin",
                List.of(new AllocationRequest(mine.getId(), 10), new AllocationRequest(mine.getId(), 10))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> receivables.recordPayment(c.getId(), TODAY, PaymentMethod.CASH, null, "admin",
                List.of(new AllocationRequest(999_999, 10)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receivables.recordPayment(c.getId(), TODAY, PaymentMethod.CASH, null, "admin",
                List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(receivables.outstandingOfInvoice(mine.getId())).isEqualTo(52_500);   // nothing was recorded
    }

    @Test
    void aPaymentCanBeSpreadOverTheOldestInvoicesFirst() {
        setUp();
        Customer c = customer("Spreader");
        Invoice oldest = invoice(c, 10, LocalDate.of(2026, 10, 1));     // 52,500
        Invoice newest = invoice(c, 10, LocalDate.of(2026, 11, 1));     // 52,500

        receivables.recordPaymentOldestFirst(c.getId(), TODAY, PaymentMethod.BANK, "NEFT-9", "admin", 70_000);

        assertThat(receivables.outstandingOfInvoice(oldest.getId())).isZero();
        assertThat(receivables.outstandingOfInvoice(newest.getId())).isEqualTo(35_000);
        assertThat(receivables.customerOutstanding(c.getId())).isEqualTo(35_000);
    }

    @Test
    void ageingPutsEachUnpaidInvoiceInTheRightBucketIncludingTheBoundaries() {
        setUp();
        Customer c = customer("Aged Customer");
        invoice(c, 10, LocalDate.of(2026, 11, 1));     // 20 days  -> 0-30
        invoice(c, 10, LocalDate.of(2026, 10, 22));    // 30 days  -> 0-30 (boundary)
        invoice(c, 10, LocalDate.of(2026, 10, 21));    // 31 days  -> 31-60 (boundary)
        invoice(c, 10, LocalDate.of(2026, 8, 31));     // 82 days  -> 61-90
        invoice(c, 10, LocalDate.of(2026, 6, 1));      // 173 days -> over 90
        Customer paid = customer("Fully Paid");
        Invoice settled = invoice(paid, 10, LocalDate.of(2026, 6, 1));
        receivables.recordPayment(paid.getId(), TODAY, PaymentMethod.CASH, null, "admin",
                List.of(new AllocationRequest(settled.getId(), 52_500)));

        List<AgeingRow> rows = receivables.ageing(TODAY);

        assertThat(rows).hasSize(1);                   // the fully paid customer does not appear
        AgeingRow row = rows.get(0);
        assertThat(row.customerName()).isEqualTo("Aged Customer");
        assertThat(row.days0to30()).isEqualTo(105_000);
        assertThat(row.days31to60()).isEqualTo(52_500);
        assertThat(row.days61to90()).isEqualTo(52_500);
        assertThat(row.over90()).isEqualTo(52_500);
        assertThat(row.total()).isEqualTo(262_500);
    }

    @Test
    void aPartialPaymentReducesTheBucketOfThatInvoice() {
        setUp();
        Customer c = customer("Partial");
        Invoice old = invoice(c, 10, LocalDate.of(2026, 6, 1));
        receivables.recordPayment(c.getId(), TODAY, PaymentMethod.CASH, null, "admin",
                List.of(new AllocationRequest(old.getId(), 12_500)));

        AgeingRow row = receivables.ageing(TODAY).get(0);

        assertThat(row.over90()).isEqualTo(40_000);
        assertThat(row.total()).isEqualTo(40_000);
    }
}
