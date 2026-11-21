package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.ProductionRun;
import com.orderaudit.domain.PurchaseLineSpec;
import com.orderaudit.domain.RunLine;
import com.orderaudit.domain.RunStatus;
import com.orderaudit.domain.SourceType;
import com.orderaudit.domain.StockMovement;
import com.orderaudit.repository.RunLineRepository;
import com.orderaudit.repository.RunOverheadRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real PostgreSQL, rolled back after every test. Runs benchmark steps 1-4 and 8 through the real services
 * and checks the OFFICIAL results (flour 120 kg, oil 19.5 L, pouches 306, finished goods 238, yield -6%
 * flagged, oil +5% flagged) plus the figures DERIVED from the official rates.
 */
@SpringBootTest
@Testcontainers
@Transactional
class ProductionBenchmarkIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Autowired ItemService itemService;
    @Autowired RecipeService recipeService;
    @Autowired PackConfigService packConfigService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired SupplierService supplierService;
    @Autowired PurchaseService purchaseService;
    @Autowired ProductionService productionService;
    @Autowired CostingService costing;
    @Autowired StockService stock;
    @Autowired RunLineRepository runLineRepository;
    @Autowired RunOverheadRepository overheadRepository;

    private BenchmarkFixture fixture(String suffix, BigDecimal recipeTolerance, boolean spiceCostKnown) {
        return BenchmarkFixture.create(suffix, recipeTolerance, 30, spiceCostKnown, itemService, recipeService,
                packConfigService, adjustments, supplierService);
    }

    /** Step 1: buy 60 kg flour at Rs 45/kg (4.5 paise per gram). */
    private void buyFlour(BenchmarkFixture f) {
        purchaseService.record(f.supplier.getId(), DAY, "PO-1",
                List.of(new PurchaseLineSpec(f.flour.getId(), 60_000, new BigDecimal("4.5"))), "admin");
    }

    /** Steps 2, 3 and 8: 188 good packets, 6 damaged pouches, oil 10.5 L, 194 pouches, 9 cartons, labour 1,200, gas 600. */
    private ProductionRun officialRun(BenchmarkFixture f) {
        return productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(new ActualLine(f.oil.getId(), 10_500),
                        new ActualLine(f.pouch.getId(), 194),
                        new ActualLine(f.carton.getId(), 9)),
                List.of(new OverheadInput("Labour", 120_000), new OverheadInput("Gas and power", 60_000)),
                "production");
    }

    @Test
    void purchaseRaisesFlourAndSetsTheWeightedAverageCost() {
        BenchmarkFixture f = fixture("", null, true);
        buyFlour(f);

        assertThat(stock.balanceOf(f.flour.getId())).isEqualTo(160_000);
        assertThat(costing.weightedAverageCostPaise(f.flour.getId())).isEqualByComparingTo("4.3125");
        assertThat(costing.weightedAverageCostPaise(f.murukku.getId())).isNull();   // finished goods stay unvalued
    }

    @Test
    void officialRunGivesTheOfficialClosingStock() {
        BenchmarkFixture f = fixture("", null, true);
        buyFlour(f);

        officialRun(f);

        assertThat(stock.balanceOf(f.flour.getId())).isEqualTo(120_000);    // OFFICIAL 120 kg
        assertThat(stock.balanceOf(f.oil.getId())).isEqualTo(19_500);       // OFFICIAL 19.5 L
        assertThat(stock.balanceOf(f.pouch.getId())).isEqualTo(306);        // OFFICIAL 306
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(238);      // OFFICIAL 238
        assertThat(stock.balanceOf(f.spice.getId())).isEqualTo(3_800);      // derived 3.8 kg
        assertThat(stock.balanceOf(f.carton.getId())).isEqualTo(21);        // derived 30 - 9
        assertThat(stock.reconcile()).isEmpty();
    }

    @Test
    void officialYieldAndConsumptionVariancesAreFlaggedCorrectly() {
        BenchmarkFixture f = fixture("", null, true);
        buyFlour(f);

        ProductionRun run = officialRun(f);

        assertThat(run.getExpectedPackets()).isEqualTo(200);
        assertThat(run.getActualGoodPackets()).isEqualTo(188);
        assertThat(run.getDamagedPouches()).isEqualTo(6);
        assertThat(run.getTolerancePct()).isEqualByComparingTo("3");
        assertThat(run.getYieldVariancePct()).isEqualByComparingTo("-6.00");   // OFFICIAL -6%
        assertThat(run.isYieldFlagged()).isTrue();                            // OFFICIAL flagged

        Map<Long, RunLine> byItem = runLineRepository.findByRunIdOrderByItemIdAsc(run.getId()).stream()
                .collect(Collectors.toMap(RunLine::getItemId, Function.identity()));
        assertThat(byItem).hasSize(5);

        RunLine oilLine = byItem.get(f.oil.getId());
        assertThat(oilLine.getPlannedQty()).isEqualTo(10_000);
        assertThat(oilLine.getActualQty()).isEqualTo(10_500);
        assertThat(oilLine.getVariancePct()).isEqualByComparingTo("5.00");    // OFFICIAL +5%
        assertThat(oilLine.isFlagged()).isTrue();                             // OFFICIAL flagged

        RunLine pouchLine = byItem.get(f.pouch.getId());
        assertThat(pouchLine.getPlannedQty()).isEqualTo(200);
        assertThat(pouchLine.getActualQty()).isEqualTo(194);
        assertThat(pouchLine.getVariancePct()).isEqualByComparingTo("-3.00");
        assertThat(pouchLine.isFlagged()).isFalse();                          // exactly 3% is not beyond 3%

        RunLine cartonLine = byItem.get(f.carton.getId());
        assertThat(cartonLine.getPlannedQty()).isEqualTo(10);
        assertThat(cartonLine.getActualQty()).isEqualTo(9);
        assertThat(cartonLine.getVariancePct()).isEqualByComparingTo("-10.00");
        assertThat(cartonLine.isFlagged()).isTrue();

        RunLine flourLine = byItem.get(f.flour.getId());
        assertThat(flourLine.getActualQty()).isEqualTo(40_000);
        assertThat(flourLine.getVariancePct()).isEqualByComparingTo("0.00");
        assertThat(flourLine.isFlagged()).isFalse();
        RunLine spiceLine = byItem.get(f.spice.getId());
        assertThat(spiceLine.getActualQty()).isEqualTo(1_200);                // not listed -> used as planned
        assertThat(spiceLine.isFlagged()).isFalse();
    }

    @Test
    void costPerPacketUsesWeightedAverageMaterialsPlusOverheadsOverGoodPackets() {
        BenchmarkFixture f = fixture("", null, true);
        buyFlour(f);

        ProductionRun run = officialRun(f);

        Map<Long, RunLine> byItem = runLineRepository.findByRunIdOrderByItemIdAsc(run.getId()).stream()
                .collect(Collectors.toMap(RunLine::getItemId, Function.identity()));
        assertThat(byItem.get(f.flour.getId()).getUnitCost()).isEqualByComparingTo("4.3125");
        assertThat(byItem.get(f.flour.getId()).getLineCostPaise()).isEqualTo(172_500);   // Rs 1,725
        assertThat(byItem.get(f.oil.getId()).getLineCostPaise()).isEqualTo(147_000);      // Rs 1,470
        assertThat(byItem.get(f.spice.getId()).getLineCostPaise()).isEqualTo(24_000);     // Rs 240
        assertThat(byItem.get(f.pouch.getId()).getLineCostPaise()).isEqualTo(48_500);     // Rs 485
        assertThat(byItem.get(f.carton.getId()).getLineCostPaise()).isEqualTo(13_500);    // Rs 135

        assertThat(run.getTotalMaterialPaise()).isEqualTo(405_500);                        // Rs 4,055
        assertThat(run.getTotalOverheadPaise()).isEqualTo(180_000);                        // labour 1,200 + gas/power 600
        assertThat(run.getTotalCostPaise()).isEqualTo(585_500);                            // Rs 5,855
        assertThat(run.getCostPerPacket()).isEqualByComparingTo("3114.3617");              // about Rs 31.14
        assertThat(overheadRepository.findByRunId(run.getId())).hasSize(2);

        // Margin against the Rs 50 selling price
        BigDecimal margin = new BigDecimal("5000").subtract(run.getCostPerPacket());
        assertThat(margin).isEqualByComparingTo("1885.6383");
    }

    @Test
    void everyMovementTracesBackToTheRunAndFinishedGoodsCarryTheCostPerPacket() {
        BenchmarkFixture f = fixture("", null, true);
        buyFlour(f);

        ProductionRun run = officialRun(f);

        List<StockMovement> flourLedger = stock.ledgerFor(f.flour.getId());
        assertThat(flourLedger).extracting(StockMovement::getMovementType)
                .containsExactly(MovementType.OPENING, MovementType.PURCHASE, MovementType.PROD_ISSUE);
        StockMovement flourIssue = flourLedger.get(2);
        assertThat(flourIssue.getSourceType()).isEqualTo(SourceType.PRODUCTION_RUN);
        assertThat(flourIssue.getSourceId()).isEqualTo(run.getId());
        assertThat(flourIssue.getQtySigned()).isEqualTo(-40_000);
        assertThat(flourIssue.getUnitCost()).isEqualByComparingTo("4.3125");

        StockMovement output = stock.ledgerFor(f.murukku.getId()).get(1);
        assertThat(output.getMovementType()).isEqualTo(MovementType.PROD_OUTPUT);
        assertThat(output.getSourceId()).isEqualTo(run.getId());
        assertThat(output.getQtySigned()).isEqualTo(188);
        assertThat(output.getUnitCost()).isEqualByComparingTo("3114.3617");
    }

    @Test
    void aRecipeToleranceOverridesTheGlobalSetting() {
        BenchmarkFixture f = fixture("", new BigDecimal("10"), true);
        buyFlour(f);

        ProductionRun run = officialRun(f);

        assertThat(run.getTolerancePct()).isEqualByComparingTo("10");
        assertThat(run.isYieldFlagged()).isFalse();   // -6% is within 10%
        List<RunLine> lines = runLineRepository.findByRunIdOrderByItemIdAsc(run.getId());
        // Oil +5%, yield -6% and pouches -3% are inside 10%; cartons are exactly -10%, and exactly 10% is NOT beyond 10%.
        assertThat(lines.stream().filter(RunLine::isFlagged)).isEmpty();
    }

    @Test
    void aRunWithNoGoodPacketsHasNoCostPerPacketAndAddsNoFinishedGoods() {
        BenchmarkFixture f = fixture("", null, true);

        ProductionRun run = productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 0, 0,
                List.of(), List.of(), "production");

        assertThat(run.getCostPerPacket()).isNull();
        assertThat(run.getYieldVariancePct()).isEqualByComparingTo("-100.00");
        assertThat(run.isYieldFlagged()).isTrue();
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(50);   // unchanged
        assertThat(stock.ledgerFor(f.murukku.getId())).hasSize(1);
    }

    @Test
    void aMaterialWithNoKnownCostBlocksTheRun() {
        BenchmarkFixture f = fixture("", null, false);   // spice opened WITHOUT a cost

        assertThatThrownBy(() -> productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(), List.of(), "production"))
                .isInstanceOf(CostUnknownException.class);
    }

    @Test
    void anActualForAnItemOutsideTheBomIsRejected() {
        BenchmarkFixture f = fixture("", null, true);

        assertThatThrownBy(() -> productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(new ActualLine(f.murukku.getId(), 5)), List.of(), "production"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("BOM");
    }

    @Test
    void reversingARunRestoresEveryBalanceAndKeepsEveryRowInTheLedger() {
        BenchmarkFixture f = fixture("", null, true);
        buyFlour(f);
        ProductionRun run = officialRun(f);

        ProductionRun reversed = productionService.reverse(run.getId(), "admin");

        assertThat(reversed.getStatus()).isEqualTo(RunStatus.REVERSED);
        assertThat(stock.balanceOf(f.flour.getId())).isEqualTo(160_000);
        assertThat(stock.balanceOf(f.oil.getId())).isEqualTo(30_000);
        assertThat(stock.balanceOf(f.pouch.getId())).isEqualTo(500);
        assertThat(stock.balanceOf(f.carton.getId())).isEqualTo(30);
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(50);
        assertThat(stock.ledgerFor(f.flour.getId())).hasSize(4);   // opening, purchase, issue, reversal: nothing deleted
        assertThat(costing.weightedAverageCostPaise(f.flour.getId())).isEqualByComparingTo("4.3125");
        assertThat(stock.reconcile()).isEmpty();
    }

    @Test
    void aRunCannotBeReversedTwice() {
        BenchmarkFixture f = fixture("", null, true);
        ProductionRun run = productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(), List.of(), "production");
        productionService.reverse(run.getId(), "admin");

        assertThatThrownBy(() -> productionService.reverse(run.getId(), "admin"))
                .isInstanceOf(IllegalStateException.class);
    }
}
