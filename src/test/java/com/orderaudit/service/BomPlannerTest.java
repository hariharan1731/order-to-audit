package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.BomPlan;
import com.orderaudit.domain.PackBasis;
import com.orderaudit.domain.PackLineSpec;
import com.orderaudit.domain.RecipeLineSpec;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class BomPlannerTest {

    // Item ids used only inside these tests
    private static final long FLOUR = 1, OIL = 2, SPICE = 3, POUCH = 4, CARTON = 5;

    /** OFFICIAL recipe: flour 40 kg, oil 10 L, spice 1.2 kg per batch, in base units. Bulk yield 200 x 200 g. */
    private static final List<RecipeLineSpec> RECIPE = List.of(
            new RecipeLineSpec(FLOUR, 40_000), new RecipeLineSpec(OIL, 10_000), new RecipeLineSpec(SPICE, 1_200));

    /** OFFICIAL packaging: one pouch per packet, one carton per carton (a carton holds 20 packets). */
    private static final List<PackLineSpec> PACKING = List.of(
            new PackLineSpec(POUCH, 1, PackBasis.PER_PACKET), new PackLineSpec(CARTON, 1, PackBasis.PER_CARTON));

    private static BomPlan plan(int packSizeGrams, int packetsPerCarton, String multiplier) {
        return BomPlanner.plan(40_000, packSizeGrams, packetsPerCarton, RECIPE, PACKING, new BigDecimal(multiplier));
    }

    /** OFFICIAL BOM for Murukku 200 g, one batch: 200 packets; flour 40 kg; oil 10 L; spice 1.2 kg; 200 pouches; 10 cartons. */
    @Test
    void oneBatchOfMurukku200gReproducesTheOfficialBom() {
        BomPlan plan = plan(200, 20, "1");

        assertThat(plan.expectedPackets()).isEqualTo(200);
        assertThat(plan.quantityOf(FLOUR)).isEqualTo(40_000);
        assertThat(plan.quantityOf(OIL)).isEqualTo(10_000);
        assertThat(plan.quantityOf(SPICE)).isEqualTo(1_200);
        assertThat(plan.quantityOf(POUCH)).isEqualTo(200);
        assertThat(plan.quantityOf(CARTON)).isEqualTo(10);
    }

    @Test
    void halfBatchScalesEverything() {
        BomPlan plan = plan(200, 20, "0.5");

        assertThat(plan.expectedPackets()).isEqualTo(100);
        assertThat(plan.quantityOf(FLOUR)).isEqualTo(20_000);
        assertThat(plan.quantityOf(OIL)).isEqualTo(5_000);
        assertThat(plan.quantityOf(SPICE)).isEqualTo(600);
        assertThat(plan.quantityOf(POUCH)).isEqualTo(100);
        assertThat(plan.quantityOf(CARTON)).isEqualTo(5);
    }

    @Test
    void doubleBatchScalesEverything() {
        BomPlan plan = plan(200, 20, "2");

        assertThat(plan.expectedPackets()).isEqualTo(400);
        assertThat(plan.quantityOf(FLOUR)).isEqualTo(80_000);
        assertThat(plan.quantityOf(CARTON)).isEqualTo(20);
    }

    /**
     * Interview scenario (illustration, NOT official numbers): the SAME recipe packed in 500 g packs,
     * assuming 10 packets per carton. Only the pack configuration changes.
     */
    @Test
    void sameRecipeInFiveHundredGramPacksIsOnlyAPackConfigChange() {
        BomPlan plan = plan(500, 10, "1");

        assertThat(plan.expectedPackets()).isEqualTo(80);          // 40,000 g / 500 g
        assertThat(plan.quantityOf(FLOUR)).isEqualTo(40_000);      // recipe unchanged
        assertThat(plan.quantityOf(POUCH)).isEqualTo(80);
        assertThat(plan.quantityOf(CARTON)).isEqualTo(8);          // 80 packets / 10 per carton
    }

    @Test
    void partialPacketsRoundDown() {
        // 40,000 g / 300 g = 133.33 -> 133 whole packets
        assertThat(plan(300, 20, "1").expectedPackets()).isEqualTo(133);
    }

    @Test
    void cartonsRoundUp() {
        // 133 packets at 20 per carton -> 7 cartons (6 full + 13 loose)
        assertThat(plan(300, 20, "1").quantityOf(CARTON)).isEqualTo(7);
    }

    @Test
    void aMultiplierThatNeedsAFractionOfABaseUnitIsRejected() {
        List<RecipeLineSpec> oneGram = List.of(new RecipeLineSpec(FLOUR, 1));
        assertThatThrownBy(() -> BomPlanner.plan(40_000, 200, 20, oneGram, PACKING, new BigDecimal("0.5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroNegativeOrMissingMultiplierIsRejected() {
        assertThatThrownBy(() -> plan(200, 20, "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(200, 20, "-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BomPlanner.plan(40_000, 200, 20, RECIPE, PACKING, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBatchTooSmallToMakeOnePacketIsRejected() {
        assertThatThrownBy(() -> plan(200, 20, "0.000001")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quantityOfAnItemNotInThePlanIsRejected() {
        assertThatThrownBy(() -> plan(200, 20, "1").quantityOf(999)).isInstanceOf(IllegalArgumentException.class);
    }
}
