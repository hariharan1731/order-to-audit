package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.ProductionRun;
import com.orderaudit.domain.RunStatus;
import com.orderaudit.domain.SourceType;
import com.orderaudit.repository.ProductionRunRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Deliberately NOT @Transactional: every service call commits (or rolls back) for real, so these tests
 * prove "all or nothing" - a failed run or a failed reversal must leave the database exactly as it was.
 */
@SpringBootTest
@Testcontainers
class ProductionRollbackIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Autowired ItemService itemService;
    @Autowired RecipeService recipeService;
    @Autowired PackConfigService packConfigService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired SupplierService supplierService;
    @Autowired ProductionService productionService;
    @Autowired ProductionRunRepository runRepository;
    @Autowired StockService stock;
    @Autowired JdbcTemplate jdbc;

    private BenchmarkFixture fixture(String suffix, long cartonOpening) {
        return BenchmarkFixture.create(suffix, null, cartonOpening, true, itemService, recipeService,
                packConfigService, adjustments, supplierService);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    @Test
    void aRunThatFailsHalfWayLeavesNothingBehind() {
        BenchmarkFixture f = fixture(" rollback-run", 5);   // only 5 cartons, but the run needs 9
        long runsBefore = count("SELECT count(*) FROM production_run");
        long runLinesBefore = count("SELECT count(*) FROM run_line");
        long runMovementsBefore = count("SELECT count(*) FROM stock_movement WHERE source_type = 'PRODUCTION_RUN'");

        // Flour, oil, spice and pouches would be issued BEFORE the cartons fail; all of that must be undone.
        assertThatThrownBy(() -> productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(new com.orderaudit.domain.ActualLine(f.oil.getId(), 10_500),
                        new com.orderaudit.domain.ActualLine(f.pouch.getId(), 194),
                        new com.orderaudit.domain.ActualLine(f.carton.getId(), 9)),
                List.of(new com.orderaudit.domain.OverheadInput("Labour", 120_000)), "production"))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(count("SELECT count(*) FROM production_run")).isEqualTo(runsBefore);
        assertThat(count("SELECT count(*) FROM run_line")).isEqualTo(runLinesBefore);
        assertThat(count("SELECT count(*) FROM stock_movement WHERE source_type = 'PRODUCTION_RUN'"))
                .isEqualTo(runMovementsBefore);
        assertThat(stock.balanceOf(f.flour.getId())).isEqualTo(100_000);
        assertThat(stock.balanceOf(f.oil.getId())).isEqualTo(30_000);
        assertThat(stock.balanceOf(f.pouch.getId())).isEqualTo(500);
        assertThat(stock.balanceOf(f.carton.getId())).isEqualTo(5);
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(50);
        assertThat(stock.reconcile()).isEmpty();
    }

    @Test
    void reversingARunWhoseGoodsAreAlreadySoldFailsAndChangesNothing() {
        BenchmarkFixture f = fixture(" rollback-reverse", 30);
        ProductionRun run = productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(), List.of(), "production");
        // 50 opening + 188 produced = 238; sell 100 so fewer than 188 packets remain
        stock.post(f.murukku.getId(), MovementType.SALE, -100, null, SourceType.DISPATCH, 1, "admin");
        long ledgerRowsBefore = count("SELECT count(*) FROM stock_movement");

        assertThatThrownBy(() -> productionService.reverse(run.getId(), "admin"))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.POSTED);
        assertThat(count("SELECT count(*) FROM stock_movement")).isEqualTo(ledgerRowsBefore);
        assertThat(count("SELECT count(*) FROM stock_movement WHERE movement_type = 'REVERSAL'")).isZero();
        assertThat(stock.balanceOf(f.flour.getId())).isEqualTo(60_000);       // the issue was NOT undone
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(138);
        assertThat(stock.reconcile()).isEmpty();
    }
}
