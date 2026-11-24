package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.ProductionRun;
import com.orderaudit.domain.PurchaseLineSpec;
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

/** Audit finding M1: a reversed production run is not a live problem, so the dashboard must not count it as flagged. */
@SpringBootTest
@Testcontainers
@Transactional
class DashboardFlaggedRunsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);
    private static final ReportPeriod NOVEMBER = new ReportPeriod(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));

    @Autowired ItemService itemService;
    @Autowired RecipeService recipeService;
    @Autowired PackConfigService packConfigService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired SupplierService supplierService;
    @Autowired PurchaseService purchaseService;
    @Autowired ProductionService productionService;
    @Autowired DashboardService dashboardService;
    @Autowired jakarta.persistence.EntityManager entityManager;

    BenchmarkFixture f;
    ProductionRun run;

    @BeforeEach
    void setUp() {
        f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
        purchaseService.record(f.supplier.getId(), DAY, "PO-1",
                List.of(new PurchaseLineSpec(f.flour.getId(), 60_000, new BigDecimal("4.5"))), "admin");
        // The official run: 188 good packets against 190 expected is outside the tolerance, so it is flagged.
        run = productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(new ActualLine(f.oil.getId(), 10_500), new ActualLine(f.pouch.getId(), 194),
                        new ActualLine(f.carton.getId(), 9)),
                List.of(new OverheadInput("Labour", 120_000), new OverheadInput("Gas and power", 60_000)), "production");
    }

    @Test
    void aFlaggedLiveRunIsCounted() {
        assertThat(dashboardService.compute(NOVEMBER).flaggedRuns()).isEqualTo(1);
    }

    @Test
    void aReversedFlaggedRunIsNotCounted() {
        productionService.reverse(run.getId(), "production");
        entityManager.flush();   // the report reads with plain SQL, so write the status change to the database first
        assertThat(dashboardService.compute(NOVEMBER).flaggedRuns()).isZero();
    }
}
