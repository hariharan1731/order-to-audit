package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.Unit;
import com.orderaudit.report.ReportPeriod;
import com.orderaudit.report.ReportService;
import com.orderaudit.report.ReportTable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Audit finding M9: credit notes get their own rows in the GST summary instead of being netted into the sales rows.
 * The TOTAL row is still the net figure for GSTR-3B. Each invoice here is Rs 7,875.00 (taxable Rs 7,500.00 + 5% GST).
 */
@SpringBootTest
@Testcontainers
@Transactional
class GstSummaryCreditNoteRowsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);
    private static final ReportPeriod PERIOD = new ReportPeriod(DAY, DAY);

    @Autowired ItemService itemService;
    @Autowired CustomerService customerService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired InvoiceService invoiceService;
    @Autowired DispatchService dispatchService;
    @Autowired CreditNoteService creditNotes;
    @Autowired ReportService reports;
    @Autowired jakarta.persistence.EntityManager entityManager;

    Item product;

    @BeforeEach
    void setUp() {
        product = itemService.create("Gst Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 10_000, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
    }

    private Invoice invoiceFor(Customer customer) {
        SalesOrder order = orderService.submit(customer.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(product.getId(), 150)));
        orderService.approve(order.getId(), "admin");
        Invoice invoice = invoiceService.raise(order.getId(), DAY, "admin");
        dispatchService.dispatch(order.getId(), "admin");
        return invoice;
    }

    private ReportTable summary() {
        entityManager.flush();   // the report reads with plain SQL
        return reports.gstSummary(PERIOD);
    }

    private static List<String> kinds(ReportTable t) {
        return t.rows().stream().map(r -> (String) r.get(0)).toList();
    }

    @Test
    void withoutCreditNotesTheRowsAreAsBefore() {
        invoiceFor(customerService.create("Shop Without Gstin", null, "33", null));

        ReportTable t = summary();

        assertThat(kinds(t)).containsExactly("B2C");
        assertThat(t.totals().get(2)).isEqualTo(750_000L);
        assertThat(t.totals().get(7)).isEqualTo(787_500L);
    }

    @Test
    void aCreditNoteGetsItsOwnNegativeRow() {
        Invoice invoice = invoiceFor(customerService.create("Shop Without Gstin", null, "33", null));
        creditNotes.issue(invoice.getId(), DAY, "Goods returned", "admin");

        ReportTable t = summary();

        assertThat(kinds(t)).containsExactly("B2C", "B2C credit notes");
        List<Object> sale = t.rows().get(0);
        List<Object> credit = t.rows().get(1);
        assertThat(sale.get(2)).isEqualTo(750_000L);                 // the sale is still shown in full
        assertThat(sale.get(7)).isEqualTo(787_500L);
        assertThat(credit.get(2)).isEqualTo(-750_000L);
        assertThat(credit.get(3)).isEqualTo(-18_750L);
        assertThat(credit.get(4)).isEqualTo(-18_750L);
        assertThat(credit.get(7)).isEqualTo(-787_500L);
        assertThat(t.totals().get(2)).isEqualTo(0L);                 // net for GSTR-3B
        assertThat(t.totals().get(6)).isEqualTo(0L);
        assertThat(t.totals().get(7)).isEqualTo(0L);
    }

    @Test
    void businessAndConsumerCreditNotesAreKeptApart() {
        Customer registered = customerService.create("Registered Shop", "33AAAAA0000A1Z5", "33", null);
        Customer consumer = customerService.create("Walk-in Shop", null, "33", null);
        Invoice b2b = invoiceFor(registered);
        invoiceFor(consumer);
        creditNotes.issue(b2b.getId(), DAY, "Goods returned", "admin");

        ReportTable t = summary();

        assertThat(kinds(t)).containsExactly("B2B", "B2B credit notes", "B2C");
        assertThat(t.rows().get(1).get(7)).isEqualTo(-787_500L);
        assertThat(t.totals().get(2)).isEqualTo(750_000L);           // only the consumer sale is left, net
        assertThat(t.totals().get(7)).isEqualTo(787_500L);
    }

    @Test
    void theOtherSalesReportsAreUnchanged() {
        Invoice invoice = invoiceFor(customerService.create("Shop Without Gstin", null, "33", null));
        creditNotes.issue(invoice.getId(), DAY, "Goods returned", "admin");
        entityManager.flush();

        assertThat(reports.productWiseSales(PERIOD).totals().get(3)).isEqualTo(0L);
        assertThat(reports.customerWiseSales(PERIOD).totals().get(4)).isEqualTo(0L);
        assertThat(reports.salesRegister(PERIOD).totals().get(9)).isEqualTo(0L);
    }
}
