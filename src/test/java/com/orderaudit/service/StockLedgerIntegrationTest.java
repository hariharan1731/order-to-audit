package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.SourceType;
import com.orderaudit.domain.StockMovement;
import com.orderaudit.domain.Unit;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real PostgreSQL. Rolled back after every test. Replays the OFFICIAL stock figures through the ledger.
 * Costs are paise per base unit: Rs 42/kg = 4.2 paise per gram, Rs 140/L = 14 paise per ml,
 * Rs 200/kg = 20 paise per gram, Rs 2.50 = 250 paise, Rs 15 = 1500 paise.
 */
@SpringBootTest
@Testcontainers
@Transactional
class StockLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired ItemService itemService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired StockService stock;
    @Autowired JdbcTemplate jdbc;

    private Item flour, oil, spice, pouch, carton, murukku;

    private void openOfficialStock() {
        BigDecimal zero = BigDecimal.ZERO;
        flour = itemService.create("Flour", ItemType.RAW, Unit.GRAM, null, zero);
        oil = itemService.create("Oil", ItemType.RAW, Unit.MILLILITRE, null, zero);
        spice = itemService.create("Spice mix", ItemType.RAW, Unit.GRAM, null, zero);
        pouch = itemService.create("Pouch 200 g", ItemType.PACKAGING, Unit.PIECE, null, zero);
        carton = itemService.create("Carton", ItemType.PACKAGING, Unit.PIECE, null, zero);
        murukku = itemService.create("Murukku 200 g", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));

        // OFFICIAL opening stock. The finished-goods rate is NOT supplied, so it stays unvalued (null).
        adjustments.recordOpening(flour.getId(), 100_000, new BigDecimal("4.2"), "Opening stock", "admin");
        adjustments.recordOpening(oil.getId(), 30_000, new BigDecimal("14"), "Opening stock", "admin");
        adjustments.recordOpening(spice.getId(), 5_000, new BigDecimal("20"), "Opening stock", "admin");
        adjustments.recordOpening(pouch.getId(), 500, new BigDecimal("250"), "Opening stock", "admin");
        adjustments.recordOpening(carton.getId(), 30, new BigDecimal("1500"), "Opening stock", "admin");
        adjustments.recordOpening(murukku.getId(), 50, null, "Opening stock", "admin");
    }

    @Test
    void officialOpeningStockIsInTheLedgerAndTheCacheMatches() {
        openOfficialStock();

        assertThat(stock.balanceOf(flour.getId())).isEqualTo(100_000);
        assertThat(stock.balanceOf(oil.getId())).isEqualTo(30_000);
        assertThat(stock.balanceOf(spice.getId())).isEqualTo(5_000);
        assertThat(stock.balanceOf(pouch.getId())).isEqualTo(500);
        assertThat(stock.balanceOf(carton.getId())).isEqualTo(30);
        assertThat(stock.balanceOf(murukku.getId())).isEqualTo(50);
        assertThat(stock.ledgerBalanceOf(flour.getId())).isEqualTo(100_000);
        assertThat(stock.reconcile()).isEmpty();
        assertThat(stock.ledgerFor(murukku.getId()).get(0).getUnitCost()).isNull();   // unvalued until a rate is supplied
    }

    /** Replays the benchmark quantities as ledger movements and checks the OFFICIAL closing stock. */
    @Test
    void benchmarkStockFlowGivesTheOfficialClosingFigures() {
        openOfficialStock();

        // Step 1: buy 60 kg flour at Rs 45/kg (4.5 paise per gram)
        stock.post(flour.getId(), MovementType.PURCHASE, 60_000, new BigDecimal("4.5"), SourceType.PURCHASE, 1, "admin");
        // Step 2: production run issues the actual materials
        stock.post(flour.getId(), MovementType.PROD_ISSUE, -40_000, null, SourceType.PRODUCTION_RUN, 1, "prod");
        stock.post(oil.getId(), MovementType.PROD_ISSUE, -10_500, null, SourceType.PRODUCTION_RUN, 1, "prod");
        stock.post(spice.getId(), MovementType.PROD_ISSUE, -1_200, null, SourceType.PRODUCTION_RUN, 1, "prod");
        stock.post(pouch.getId(), MovementType.PROD_ISSUE, -194, null, SourceType.PRODUCTION_RUN, 1, "prod"); // 188 good + 6 damaged
        stock.post(carton.getId(), MovementType.PROD_ISSUE, -9, null, SourceType.PRODUCTION_RUN, 1, "prod");
        stock.post(murukku.getId(), MovementType.PROD_OUTPUT, 188, null, SourceType.PRODUCTION_RUN, 1, "prod");

        assertThat(stock.balanceOf(flour.getId())).isEqualTo(120_000);   // OFFICIAL 120 kg
        assertThat(stock.balanceOf(oil.getId())).isEqualTo(19_500);      // OFFICIAL 19.5 L
        assertThat(stock.balanceOf(pouch.getId())).isEqualTo(306);       // OFFICIAL 306
        assertThat(stock.balanceOf(murukku.getId())).isEqualTo(238);     // OFFICIAL 238 after the run
        assertThat(stock.balanceOf(spice.getId())).isEqualTo(3_800);     // DERIVED 3.8 kg
        assertThat(stock.balanceOf(carton.getId())).isEqualTo(21);       // DERIVED 30 - 9

        // Step 5: dispatch 150 packets to Retailer A
        stock.post(murukku.getId(), MovementType.SALE, -150, null, SourceType.DISPATCH, 1, "admin");
        assertThat(stock.balanceOf(murukku.getId())).isEqualTo(88);      // OFFICIAL 88

        assertThat(stock.reconcile()).isEmpty();
    }

    @Test
    void anIssueBeyondTheBalanceFailsAndLeavesStockUnchanged() {
        openOfficialStock();

        assertThatThrownBy(() -> stock.post(carton.getId(), MovementType.PROD_ISSUE, -31, null,
                SourceType.PRODUCTION_RUN, 1, "prod")).isInstanceOf(InsufficientStockException.class);

        assertThat(stock.balanceOf(carton.getId())).isEqualTo(30);
        assertThat(stock.ledgerFor(carton.getId())).hasSize(1);
    }

    @Test
    void aReversalNetsToZeroAndASecondReversalIsRejected() {
        openOfficialStock();
        StockMovement issue = stock.post(flour.getId(), MovementType.PROD_ISSUE, -40_000, null,
                SourceType.PRODUCTION_RUN, 1, "prod");

        stock.reverse(issue.getId(), "admin");

        assertThat(stock.balanceOf(flour.getId())).isEqualTo(100_000);
        assertThat(stock.ledgerFor(flour.getId())).hasSize(3);           // opening, issue, reversal: nothing deleted
        assertThatThrownBy(() -> stock.reverse(issue.getId(), "admin")).isInstanceOf(IllegalStateException.class);
        assertThat(stock.reconcile()).isEmpty();
    }

    @Test
    void theDatabaseRefusesToUpdateALedgerRow() {
        openOfficialStock();
        Long id = stock.ledgerFor(flour.getId()).get(0).getId();

        assertThatThrownBy(() -> jdbc.update("UPDATE stock_movement SET qty_signed = 1 WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void theDatabaseRefusesToDeleteALedgerRow() {
        openOfficialStock();
        Long id = stock.ledgerFor(flour.getId()).get(0).getId();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM stock_movement WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void reconcileDetectsATamperedCacheAndNothingElse() {
        openOfficialStock();
        jdbc.update("UPDATE stock_balance SET qty_base = qty_base + 5 WHERE item_id = ?", flour.getId());

        assertThat(stock.reconcile()).containsExactly(flour.getId());

        jdbc.update("UPDATE stock_balance SET qty_base = qty_base - 5 WHERE item_id = ?", flour.getId());
        assertThat(stock.reconcile()).isEmpty();
    }

    @Test
    void theDatabaseRefusesANegativeBalance() {
        openOfficialStock();

        assertThatThrownBy(() -> jdbc.update("UPDATE stock_balance SET qty_base = -1 WHERE item_id = ?", flour.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRefusesAZeroQuantityMovement() {
        openOfficialStock();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO stock_movement (item_id, qty_signed, movement_type, source_type, source_id, posted_by) "
                        + "VALUES (?, 0, 'PURCHASE', 'PURCHASE', 1, 'x')", flour.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRefusesAMovementWithoutASourceDocument() {
        openOfficialStock();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO stock_movement (item_id, qty_signed, movement_type, source_type, source_id, posted_by) "
                        + "VALUES (?, 5, 'PURCHASE', NULL, 1, 'x')", flour.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
