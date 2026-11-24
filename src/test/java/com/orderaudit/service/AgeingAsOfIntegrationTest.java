package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Audit finding M2: ageing "as of" a past date must show the books as they stood that day. A payment received
 * later, or a reversal made later, does not change what was owed then. Retailer A's invoice is Rs 7,875.00.
 */
@SpringBootTest
@Testcontainers
@Transactional
class AgeingAsOfIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);     // invoice date
    private static final LocalDate PAID = DAY.plusDays(2);              // payment of Rs 4,000.00
    private static final LocalDate REVERSED = DAY.plusDays(3);          // the cheque bounces

    @Autowired ItemService itemService;
    @Autowired CustomerService customerService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired InvoiceService invoiceService;
    @Autowired ReceivablesService receivables;

    Customer retailerA;
    Invoice invoice;
    Payment payment;

    @BeforeEach
    void setUp() {
        Item product = itemService.create("Ageing Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 10_000, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
        retailerA = customerService.create("Retailer A", null, "33", null);
        SalesOrder order = orderService.submit(retailerA.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(product.getId(), 150)));
        orderService.approve(order.getId(), "admin");
        invoice = invoiceService.raise(order.getId(), DAY, "admin");
        payment = receivables.recordPayment(retailerA.getId(), PAID, PaymentMethod.CASH, "CH-1", "admin",
                List.of(new AllocationRequest(invoice.getId(), 400_000)));
    }

    private long owedOn(LocalDate day) {
        return receivables.ageing(day).stream().mapToLong(r -> r.total()).sum();
    }

    @Test
    void aPaymentReceivedLaterDoesNotReduceWhatWasOwedEarlier() {
        assertThat(owedOn(DAY)).isEqualTo(787_500);
        assertThat(owedOn(PAID.minusDays(1))).isEqualTo(787_500);
        assertThat(owedOn(PAID)).isEqualTo(387_500);          // from the day it was received
    }

    @Test
    void aLaterReversalDoesNotChangeTheEarlierPicture() {
        receivables.reversePayment(payment.getId(), REVERSED, "Cheque bounced", "admin");

        assertThat(owedOn(PAID)).isEqualTo(387_500);          // the cheque still counted that day
        assertThat(owedOn(REVERSED.minusDays(1))).isEqualTo(387_500);
        assertThat(owedOn(REVERSED)).isEqualTo(787_500);      // owed again from the day it bounced
    }

    @Test
    void todaysPictureIsUnchanged() {
        assertThat(owedOn(REVERSED)).isEqualTo(387_500);
        receivables.reversePayment(payment.getId(), REVERSED, "Cheque bounced", "admin");
        assertThat(owedOn(REVERSED)).isEqualTo(787_500);
    }
}
