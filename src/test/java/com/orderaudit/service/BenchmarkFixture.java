package com.orderaudit.service;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.PackBasis;
import com.orderaudit.domain.PackConfig;
import com.orderaudit.domain.PackLineSpec;
import com.orderaudit.domain.Recipe;
import com.orderaudit.domain.RecipeLineSpec;
import com.orderaudit.domain.Supplier;
import com.orderaudit.domain.Unit;
import java.math.BigDecimal;
import java.util.List;

/**
 * Builds the OFFICIAL master data and opening stock (problem statement section 5) so several test
 * classes can share it. A suffix keeps item names unique when a class creates it more than once.
 */
final class BenchmarkFixture {

    Item flour, oil, spice, pouch, carton, murukku;
    Recipe recipe;
    PackConfig config;
    Supplier supplier;

    static BenchmarkFixture create(String suffix, BigDecimal recipeTolerance, long cartonOpening,
                                   boolean spiceCostKnown, ItemService items, RecipeService recipes,
                                   PackConfigService packConfigs, StockAdjustmentService adjustments,
                                   SupplierService suppliers) {
        BenchmarkFixture f = new BenchmarkFixture();
        BigDecimal zero = BigDecimal.ZERO;
        f.flour = items.create("Flour" + suffix, ItemType.RAW, Unit.GRAM, null, zero);
        f.oil = items.create("Oil" + suffix, ItemType.RAW, Unit.MILLILITRE, null, zero);
        f.spice = items.create("Spice mix" + suffix, ItemType.RAW, Unit.GRAM, null, zero);
        f.pouch = items.create("Pouch 200 g" + suffix, ItemType.PACKAGING, Unit.PIECE, null, zero);
        f.carton = items.create("Carton" + suffix, ItemType.PACKAGING, Unit.PIECE, null, zero);
        f.murukku = items.create("Murukku 200 g" + suffix, ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));

        // OFFICIAL BOM per batch: flour 40 kg, oil 10 L, spice 1.2 kg, 200 pouches, 10 cartons of 20 packets.
        f.recipe = recipes.create("Murukku base" + suffix, 40_000, recipeTolerance, List.of(
                new RecipeLineSpec(f.flour.getId(), 40_000),
                new RecipeLineSpec(f.oil.getId(), 10_000),
                new RecipeLineSpec(f.spice.getId(), 1_200)));
        f.config = packConfigs.create(f.murukku.getId(), f.recipe.getId(), 200, 20, List.of(
                new PackLineSpec(f.pouch.getId(), 1, PackBasis.PER_PACKET),
                new PackLineSpec(f.carton.getId(), 1, PackBasis.PER_CARTON)));

        // OFFICIAL opening stock and rates. Finished goods have NO supplied rate, so they stay unvalued (null).
        adjustments.recordOpening(f.flour.getId(), 100_000, new BigDecimal("4.2"), "Opening stock", "admin");
        adjustments.recordOpening(f.oil.getId(), 30_000, new BigDecimal("14"), "Opening stock", "admin");
        adjustments.recordOpening(f.spice.getId(), 5_000, spiceCostKnown ? new BigDecimal("20") : null,
                "Opening stock", "admin");
        adjustments.recordOpening(f.pouch.getId(), 500, new BigDecimal("250"), "Opening stock", "admin");
        adjustments.recordOpening(f.carton.getId(), cartonOpening, new BigDecimal("1500"), "Opening stock", "admin");
        adjustments.recordOpening(f.murukku.getId(), 50, null, "Opening stock", "admin");

        f.supplier = suppliers.create("Flour Mill" + suffix, null, "33", null);
        return f;
    }
}
