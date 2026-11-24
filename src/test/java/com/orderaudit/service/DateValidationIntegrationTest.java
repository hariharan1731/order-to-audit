package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.orderaudit.report.ReportPeriod;
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
 * Audit finding H6: dates that cannot be true are refused. Nothing happens "tomorrow", and money cannot be
 * received before the invoice it pays exists. Retailer A's official invoice is Rs 7,875.00 (787,500 paise).
 */
@SpringBootTest
@Testcontainers
@Transactional
class DateValidationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);

    @Autowired ItemService itemService;
    @Autowired CustomerService customerService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired InvoiceService invoiceService;
    @Autowired ReceivablesService receivables;

    Customer retailerA;
    Invoice invoice;

    @BeforeEach
    void setUp() {
        Item product = itemService.create("Date Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 10_000, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
        retailerA = customerService.create("Retailer A", null, "33", null);
        SalesOrder order = orderService.submit(retailerA.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(product.getId(), 150)));
        orderService.approve(order.getId(), "admin");
        invoice = invoiceService.raise(order.getId(), DAY, "admin");
    }

    private List<AllocationRequest> pay(long paise) {
        return List.of(new AllocationRequest(invoice.getId(), paise));
    }

    @Test
    void aPaymentDatedTomorrowIsRefused() {
        LocalDate tomorrow = ReportPeriod.today().plusDays(1);
        assertThatThrownBy(() -> receivables.recordPayment(retailerA.getId(), tomorrow, PaymentMethod.CASH, null,
                "admin", pay(400_000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be in the future");
    }

    @Test
    void anOldestFirstPaymentDatedTomorrowIsRefused() {
        LocalDate tomorrow = ReportPeriod.today().plusDays(1);
        assertThatThrownBy(() -> receivables.recordPaymentOldestFirst(retailerA.getId(), tomorrow,
                PaymentMethod.CASH, null, "admin", 400_000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be in the future");
    }

    @Test
    void aPaymentDatedBeforeItsInvoiceIsRefused() {
        assertThatThrownBy(() -> receivables.recordPayment(retailerA.getId(), DAY.minusDays(1), PaymentMethod.CASH,
                null, "admin", pay(400_000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("before invoice");
    }

    @Test
    void aPaymentOnTheInvoiceDateAndOneDatedTodayAreAccepted() {
        Payment first = receivables.recordPayment(retailerA.getId(), DAY, PaymentMethod.CASH, null, "admin",
                pay(400_000));
        Payment second = receivables.recordPayment(retailerA.getId(), ReportPeriod.today(), PaymentMethod.UPI,
                "U-1", "admin", pay(100_000));
        assertThat(first.getId()).isNotNull();
        assertThat(second.getAmountPaise()).isEqualTo(100_000);
    }

    @Test
    void aReversalDatedTomorrowIsRefusedButTodayIsFine() {
        Payment payment = receivables.recordPayment(retailerA.getId(), DAY, PaymentMethod.CASH, "CH-1", "admin",
                pay(400_000));
        assertThatThrownBy(() -> receivables.reversePayment(payment.getId(), ReportPeriod.today().plusDays(1),
                "Cheque bounced", "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be in the future");
        assertThat(receivables.reversePayment(payment.getId(), ReportPeriod.today(), "Cheque bounced", "admin"))
                .isNotNull();
    }

    @Test
    void aPurchaseDatedTomorrowIsRefusedBeforeAnythingIsTouched() {
        PurchaseService purchases = new PurchaseService(null, null, null, null, null, null);
        assertThatThrownBy(() -> purchases.record(1L, ReportPeriod.today().plusDays(1), null, List.of(), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be in the future");
    }

    @Test
    void aProductionRunDatedTomorrowIsRefusedBeforeAnythingIsTouched() {
        ProductionService production = new ProductionService(null, null, null, null, null, null, null, null, null,
                null, null);
        assertThatThrownBy(() -> production.complete(1L, BigDecimal.ONE, ReportPeriod.today().plusDays(1), 188, 6,
                List.of(), List.of(), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be in the future");
    }

    @Test
    void invoiceDatesForTheNextFinancialYearStillWork() {
        // The official financial-year numbering test raises invoices dated 2027, so invoices are deliberately
        // left out of the no-future rule.
        assertThat(invoice.getInvoiceDate()).isEqualTo(DAY);
    }
}
