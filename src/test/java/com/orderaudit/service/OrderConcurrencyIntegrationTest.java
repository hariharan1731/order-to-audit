package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.OrderStatus;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.Unit;
import com.orderaudit.repository.SalesOrderRepository;
import java.math.BigDecimal;
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
 * Real threads, real transactions (NOT @Transactional). Eight admins approve eight orders of 30 packets
 * at the SAME instant against 100 packets in stock. The reservation must let exactly three through
 * (3 x 30 = 90 <= 100; a fourth would promise 120), never overselling.
 */
@SpringBootTest
@Testcontainers
class OrderConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired ItemService itemService;
    @Autowired CustomerService customerService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired SalesOrderRepository orderRepository;
    @Autowired StockService stock;

    @Test
    void eightSimultaneousApprovalsOfThirtyAgainstAHundredLetExactlyThreeThrough() throws Exception {
        Item product = itemService.create("Concurrency Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 100, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
        Customer customer = customerService.create("Concurrency Customer", null, "33", null);
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            SalesOrder order = orderService.submit(customer.getId(), "salesman", null, null,
                    List.of(new OrderLineRequest(product.getId(), 30)));
            orderIds.add(order.getId());
        }

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (Long orderId : orderIds) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    orderService.approve(orderId, "admin");
                    return true;
                } catch (ShortageException e) {
                    return false;
                }
            }));
        }
        ready.await();
        go.countDown();
        int approved = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                approved++;
            }
        }
        pool.shutdown();

        assertThat(approved).isEqualTo(3);
        assertThat(orderIds.stream()
                .filter(id -> orderRepository.findById(id).orElseThrow().getStatus() == OrderStatus.APPROVED)
                .count()).isEqualTo(3);
        assertThat(orderIds.stream()
                .filter(id -> orderRepository.findById(id).orElseThrow().getStatus() == OrderStatus.SUBMITTED)
                .count()).isEqualTo(5);
        assertThat(stock.balanceOf(product.getId())).isEqualTo(100);   // approving never moves stock
    }
}
