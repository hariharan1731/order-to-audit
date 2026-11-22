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
import com.orderaudit.domain.PaymentRow;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.Unit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real threads, real transactions: four people press "Reverse" on the same payment at the same instant. */
@SpringBootTest
@Testcontainers
class PaymentReversalRaceIntegrationTest {

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
    @Autowired AuditService audit;

    @Test
    void fourSimultaneousReversalsOfOnePaymentLeaveExactlyOneReversal() throws Exception {
        Item product = itemService.create("Race Reversal Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 1_000, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
        Customer customer = customerService.create("Race Reversal Customer", null, "33", null);
        SalesOrder order = orderService.submit(customer.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(product.getId(), 150)));
        orderService.approve(order.getId(), "admin");
        Invoice invoice = invoiceService.raise(order.getId(), DAY, "admin");
        Payment payment = receivables.recordPayment(customer.getId(), DAY, PaymentMethod.CASH, "R-1", "admin",
                List.of(new AllocationRequest(invoice.getId(), 400_000)));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    receivables.reversePayment(payment.getId(), DAY, "Cheque bounced", "admin");
                    return true;
                } catch (IllegalStateException e) {
                    return false;   // "already been reversed"
                }
            }));
        }
        ready.await();
        go.countDown();
        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get(60, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(receivables.outstandingOfInvoice(invoice.getId())).isEqualTo(787_500);   // reversed once, not "twice"
        assertThat(receivables.paymentsOf(customer.getId())).extracting(PaymentRow::reversed).containsExactly(true);
        assertThat(audit.forEntity("PAYMENT", payment.getId())).extracting(AuditEntry::action)
                .containsOnlyOnce("PAYMENT_REVERSED");
    }
}
