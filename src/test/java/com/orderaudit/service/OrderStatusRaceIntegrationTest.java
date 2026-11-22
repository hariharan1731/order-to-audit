package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.OrderStatus;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.SourceType;
import com.orderaudit.domain.Unit;
import com.orderaudit.repository.InvoiceRepository;
import com.orderaudit.repository.SalesOrderRepository;
import com.orderaudit.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
 * Audit finding C1. Real threads, real transactions (NOT @Transactional): several admins press the same
 * button on the SAME order at the same instant. Because every status change locks the order row first,
 * exactly one of them wins and the others are told the order has already moved on.
 * Without the lock, two dispatches would both take 150 packets out of stock.
 */
@SpringBootTest
@Testcontainers
class OrderStatusRaceIntegrationTest {

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
    @Autowired DispatchService dispatchService;
    @Autowired AuditService audit;
    @Autowired StockService stock;
    @Autowired SalesOrderRepository orderRepository;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired StockMovementRepository movements;

    private Item product;
    private Customer customer;

    /** One product with 400 packets (plenty, so only the lock - not a stock shortage - can stop a second dispatch). */
    private SalesOrder submittedOrderOf150(String productName, String customerName) {
        product = itemService.create(productName, ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 400, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
        customer = customerService.create(customerName, null, "33", null);
        return orderService.submit(customer.getId(), "salesman", null, null,
                List.of(new OrderLineRequest(product.getId(), 150)));
    }

    /** Runs the same action on N threads released at the same instant; returns how many succeeded. */
    private int raceWith(int threads, Callable<Object> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    action.call();
                    return true;
                } catch (IllegalStateException e) {
                    return false;   // "Cannot ... an order that is DISPATCHED" etc.
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
        return succeeded;
    }

    @Test
    void fourSimultaneousDispatchesOfOneOrderTakeTheStockOutOnlyOnce() throws Exception {
        SalesOrder order = submittedOrderOf150("Race Murukku A", "Race Customer A");
        orderService.approve(order.getId(), "admin");
        invoiceService.raise(order.getId(), DAY, "admin");

        int succeeded = raceWith(4, () -> dispatchService.dispatch(order.getId(), "admin"));

        assertThat(succeeded).isEqualTo(1);
        assertThat(stock.balanceOf(product.getId())).isEqualTo(250);          // 400 - 150, not 400 - 600
        assertThat(stock.reconcile()).isEmpty();                               // cache still equals the ledger
        assertThat(movements.findBySourceTypeAndSourceIdOrderByIdAsc(SourceType.DISPATCH, order.getId()))
                .extracting(m -> m.getMovementType()).containsExactly(MovementType.SALE);
        assertThat(audit.forEntity("ORDER", order.getId())).extracting(AuditEntry::action)
                .containsOnlyOnce("ORDER_DISPATCHED");
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.DISPATCHED);
    }

    @Test
    void fourSimultaneousInvoiceClicksRaiseExactlyOneInvoice() throws Exception {
        SalesOrder order = submittedOrderOf150("Race Murukku B", "Race Customer B");
        orderService.approve(order.getId(), "admin");

        int succeeded = raceWith(4, () -> invoiceService.raise(order.getId(), DAY, "admin"));

        assertThat(succeeded).isEqualTo(1);
        assertThat(invoiceRepository.findByOrderId(order.getId())).isPresent();
        assertThat(audit.forEntity("ORDER", order.getId())).extracting(AuditEntry::action)
                .containsExactly("ORDER_SUBMITTED", "ORDER_APPROVED");
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.INVOICED);
    }

    @Test
    void anApprovalAndARejectionAtTheSameMomentLeaveOneConsistentAnswer() throws Exception {
        SalesOrder order = submittedOrderOf150("Race Murukku C", "Race Customer C");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<Boolean> approve = pool.submit(() -> {
            go.await();
            try {
                orderService.approve(order.getId(), "admin1");
                return true;
            } catch (IllegalStateException e) {
                return false;
            }
        });
        Future<Boolean> reject = pool.submit(() -> {
            go.await();
            try {
                orderService.reject(order.getId(), "admin2", "Customer cancelled");
                return true;
            } catch (IllegalStateException e) {
                return false;
            }
        });
        go.countDown();
        boolean approved = approve.get(60, TimeUnit.SECONDS);
        boolean rejected = reject.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(approved ^ rejected).as("exactly one of approve / reject wins").isTrue();
        OrderStatus status = orderRepository.findById(order.getId()).orElseThrow().getStatus();
        List<String> trail = audit.forEntity("ORDER", order.getId()).stream().map(AuditEntry::action).toList();
        if (approved) {
            assertThat(status).isEqualTo(OrderStatus.APPROVED);
            assertThat(trail).containsExactly("ORDER_SUBMITTED", "ORDER_APPROVED");
        } else {
            assertThat(status).isEqualTo(OrderStatus.REJECTED);
            assertThat(trail).containsExactly("ORDER_SUBMITTED", "ORDER_REJECTED");
        }
    }
}
