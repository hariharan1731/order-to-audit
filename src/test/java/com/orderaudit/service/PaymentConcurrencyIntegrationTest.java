package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderaudit.domain.AllocationRequest;
import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.PaymentMethod;
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

/**
 * Real threads, real transactions. Eight payments of Rs 100 are recorded at the SAME instant against
 * one invoice of Rs 525. Only five fit (5 x 100 = 500 <= 525; a sixth would be 600), so exactly five
 * must succeed and Rs 25 must remain outstanding: the invoice can never be over-paid.
 */
@SpringBootTest
@Testcontainers
class PaymentConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired ItemService itemService;
    @Autowired CustomerService customerService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired InvoiceService invoiceService;
    @Autowired ReceivablesService receivables;

    @Test
    void eightSimultaneousPaymentsOf100AgainstAnInvoiceOf525LetExactlyFiveThrough() throws Exception {
        Item product = itemService.create("Payment Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 100, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
        Customer customer = customerService.create("Payment Customer", null, "33", null);
        SalesOrder order = orderService.submit(customer.getId(), "salesman", null, null,
                List.of(new OrderLineRequest(product.getId(), 10)));
        orderService.approve(order.getId(), "admin");
        Invoice invoice = invoiceService.raise(order.getId(), LocalDate.of(2026, 11, 21), "admin");
        assertThat(invoice.getTotalPaise()).isEqualTo(52_500);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    receivables.recordPayment(customer.getId(), LocalDate.of(2026, 11, 21), PaymentMethod.UPI, null,
                            "admin", List.of(new AllocationRequest(invoice.getId(), 10_000)));
                    return true;
                } catch (IllegalArgumentException e) {
                    return false;
                }
            }));
        }
        ready.await();
        go.countDown();
        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(5);
        assertThat(receivables.outstandingOfInvoice(invoice.getId())).isEqualTo(2_500);
    }
}
