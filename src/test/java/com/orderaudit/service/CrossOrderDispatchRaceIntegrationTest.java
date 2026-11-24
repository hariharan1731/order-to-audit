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
import com.orderaudit.repository.SalesOrderRepository;
import com.orderaudit.repository.StockMovementRepository;
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
 * A reviewer's question: the order-row lock stops ONE order being dispatched twice, but what about several
 * DIFFERENT orders for the same product dispatching at the same instant, when the stock only covers some of them?
 * Answer: every dispatch also locks that product's stock-balance row, so they queue up and read the true balance.
 * Real threads, real transactions (NOT @Transactional).
 */
@SpringBootTest
@Testcontainers
class CrossOrderDispatchRaceIntegrationTest {

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
    @Autowired StockService stock;
    @Autowired SalesOrderRepository orderRepository;
    @Autowired StockMovementRepository movements;

    /**
     * Four invoiced orders of 100 packets each for the SAME product. All four were approved while 400 packets were
     * in stock; then 150 packets are written off, so only 250 are left and only two orders can go out.
     */
    @Test
    void fourDifferentOrdersForOneProductDispatchingAtOnceTakeOutOnlyWhatIsInStock() throws Exception {
        Item product = itemService.create("Cross Race Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 400, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
        List<SalesOrder> orders = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            Customer customer = customerService.create("Cross Race Customer " + i, null, "33", null);
            SalesOrder order = orderService.submit(customer.getId(), "salesman", null, null,
                    List.of(new OrderLineRequest(product.getId(), 100)));
            orderService.approve(order.getId(), "admin");
            invoiceService.raise(order.getId(), DAY, "admin");
            orders.add(order);
        }
        adjustments.recordCorrection(product.getId(), -150, null, "Damaged in storage", "admin");   // 400 -> 250
        assertThat(stock.balanceOf(product.getId())).isEqualTo(250);

        ExecutorService pool = Executors.newFixedThreadPool(orders.size());
        CountDownLatch ready = new CountDownLatch(orders.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (SalesOrder order : orders) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    dispatchService.dispatch(order.getId(), "admin");
                    return true;
                } catch (InsufficientStockException e) {
                    return false;   // the stock really was not there for this one
                }
            }));
        }
        ready.await();
        go.countDown();
        int dispatched = 0;
        for (Future<Boolean> result : results) {
            if (result.get(60, TimeUnit.SECONDS)) {
                dispatched++;
            }
        }
        pool.shutdown();

        assertThat(dispatched).isEqualTo(2);                                   // 250 / 100 = two orders
        assertThat(stock.balanceOf(product.getId())).isEqualTo(50);            // never negative, never oversold
        assertThat(stock.reconcile()).isEmpty();                               // cached balance still equals the ledger
        long saleRows = orders.stream()
                .mapToLong(o -> movements.findBySourceTypeAndSourceIdOrderByIdAsc(SourceType.DISPATCH, o.getId()).stream()
                        .filter(m -> m.getMovementType() == MovementType.SALE).count())
                .sum();
        assertThat(saleRows).isEqualTo(2);
        long dispatchedOrders = orders.stream()
                .filter(o -> orderRepository.findById(o.getId()).orElseThrow().getStatus() == OrderStatus.DISPATCHED).count();
        long stillInvoiced = orders.stream()
                .filter(o -> orderRepository.findById(o.getId()).orElseThrow().getStatus() == OrderStatus.INVOICED).count();
        assertThat(dispatchedOrders).isEqualTo(2);
        assertThat(stillInvoiced).isEqualTo(2);                                // the losers stay invoiced, not half-dispatched
    }
}
