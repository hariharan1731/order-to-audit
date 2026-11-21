package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.SourceType;
import com.orderaudit.domain.Unit;
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
 * Real threads, real transactions. This class is deliberately NOT @Transactional: every call to the
 * service must commit on its own, exactly as two users clicking at the same moment would.
 */
@SpringBootTest
@Testcontainers
class StockConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired ItemService itemService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired StockService stock;

    /** Runs the same action on 8 threads released at the same instant. Returns how many succeeded. */
    private int runConcurrently(java.util.function.IntConsumer action) throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    action.accept(n);
                    return true;
                } catch (InsufficientStockException e) {
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
        return succeeded;
    }

    @Test
    void eightSimultaneousIssuesOfTwentyFromAHundredLetExactlyFiveSucceed() throws Exception {
        Item item = itemService.create("Concurrent flour", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);
        adjustments.recordOpening(item.getId(), 100, null, "test", "tester");

        int succeeded = runConcurrently(n ->
                stock.post(item.getId(), MovementType.SALE, -20, null, SourceType.DISPATCH, n, "tester"));

        assertThat(succeeded).isEqualTo(5);
        assertThat(stock.balanceOf(item.getId())).isZero();
        assertThat(stock.ledgerBalanceOf(item.getId())).isZero();
        assertThat(stock.reconcile()).isEmpty();
    }

    @Test
    void eightSimultaneousFirstReceiptsCreateTheBalanceRowSafelyAndAllCount() throws Exception {
        Item item = itemService.create("Concurrent oil", ItemType.RAW, Unit.MILLILITRE, null, BigDecimal.ZERO);

        int succeeded = runConcurrently(n ->
                stock.post(item.getId(), MovementType.PURCHASE, 10, new BigDecimal("14"), SourceType.PURCHASE, n, "tester"));

        assertThat(succeeded).isEqualTo(8);
        assertThat(stock.balanceOf(item.getId())).isEqualTo(80);
        assertThat(stock.ledgerBalanceOf(item.getId())).isEqualTo(80);
        assertThat(stock.reconcile()).isEmpty();
    }
}
