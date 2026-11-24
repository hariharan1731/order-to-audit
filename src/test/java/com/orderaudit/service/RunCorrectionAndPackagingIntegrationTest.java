package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.ProductionRun;
import com.orderaudit.domain.RunStatus;
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
 * Audit findings M7 and M11.
 * M7: a posted run is never edited (its cost went into the stock ledger, which is append-only). A wrong
 * overhead is fixed by reversing the run and posting it again with the right figures.
 * M11: every packet made, good or damaged, used one pouch, so fewer pouches than packets cannot be true.
 */
@SpringBootTest
@Testcontainers
@Transactional
class RunCorrectionAndPackagingIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);

    @Autowired ItemService itemService;
    @Autowired RecipeService recipeService;
    @Autowired PackConfigService packConfigService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired SupplierService supplierService;
    @Autowired ProductionService productionService;
    @Autowired StockService stock;

    BenchmarkFixture f;

    @BeforeEach
    void setUp() {
        f = BenchmarkFixture.create(" m7m11", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
    }

    private ProductionRun run(long good, long damaged, long pouchesUsed, long labourPaise) {
        return productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, good, damaged,
                List.of(new ActualLine(f.pouch.getId(), pouchesUsed), new ActualLine(f.carton.getId(), 9)),
                List.of(new OverheadInput("Labour", labourPaise)), "production");
    }

    @Test
    void aWrongOverheadIsCorrectedByReversingAndPostingTheRunAgain() {
        ProductionRun wrong = run(188, 6, 194, 180_000);
        ProductionRun reversed = productionService.reverse(wrong.getId(), "admin");
        ProductionRun corrected = run(188, 6, 194, 100_000);

        assertThat(reversed.getStatus()).isEqualTo(RunStatus.REVERSED);
        assertThat(corrected.getStatus()).isEqualTo(RunStatus.POSTED);
        assertThat(corrected.getCostPerPacket()).isLessThan(wrong.getCostPerPacket());
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(50 + 188);   // only the corrected run counts
        assertThat(stock.reconcile()).isEmpty();
    }

    @Test
    void fewerPouchesThanPacketsMadeIsRefused() {
        // 188 good + 6 damaged = 194 packets, but only 190 pouches recorded as used.
        assertThatThrownBy(() -> run(188, 6, 190, 180_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Packaging count does not add up").hasMessageContaining("194");
        assertThat(stock.balanceOf(f.pouch.getId())).isEqualTo(500);          // nothing was posted
    }

    @Test
    void exactlyOnePouchPerPacketIsAccepted() {
        // 188 good + 6 damaged = 194 packets and exactly 194 pouches. (The official Rs 31.14 figure is proven
        // in ProductionBenchmarkIntegrationTest, which also has the 60 kg flour purchase and both overheads.)
        ProductionRun run = run(188, 6, 194, 180_000);
        assertThat(run.getStatus()).isEqualTo(RunStatus.POSTED);
        assertThat(stock.balanceOf(f.pouch.getId())).isEqualTo(500 - 194);
    }

    @Test
    void whenNoPouchCountIsGivenThePlannedCountIsCheckedInstead() {
        // The plan expects 200 packets, so 200 pouches are planned; 201 packets cannot come out of that.
        assertThatThrownBy(() -> productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 195, 6,
                List.of(), List.of(), "production"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Packaging count does not add up");
    }
}
