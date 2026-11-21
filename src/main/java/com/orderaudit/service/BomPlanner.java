package com.orderaudit.service;

import com.orderaudit.domain.BomPlan;
import com.orderaudit.domain.CartonPacking;
import com.orderaudit.domain.PackLineSpec;
import com.orderaudit.domain.PlannedLine;
import com.orderaudit.domain.PlannedSource;
import com.orderaudit.domain.RecipeLineSpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a recipe + pack configuration + batch multiplier into planned quantities. Pure maths, no database.
 *   expected packets = floor(bulk yield grams x multiplier / pack size grams)
 *   recipe line      = quantity x multiplier              (must be a whole number of base units)
 *   PER_PACKET line  = quantity x expected packets        (e.g. one pouch per packet)
 *   PER_CARTON line  = quantity x cartons needed to box all expected packets (rounded up)
 */
public final class BomPlanner {

    private BomPlanner() {
    }

    public static BomPlan plan(long bulkYieldGrams, int packSizeGrams, int packetsPerCarton,
                               List<RecipeLineSpec> recipeLines, List<PackLineSpec> packLines,
                               BigDecimal batchMultiplier) {
        if (batchMultiplier == null || batchMultiplier.signum() <= 0) {
            throw new IllegalArgumentException("Batch multiplier must be greater than zero");
        }
        long expectedPackets = BigDecimal.valueOf(bulkYieldGrams)
                .multiply(batchMultiplier)
                .divide(BigDecimal.valueOf(packSizeGrams), 0, RoundingMode.FLOOR)
                .longValueExact();
        if (expectedPackets < 1) {
            throw new IllegalArgumentException("This batch size would make less than one packet");
        }

        List<PlannedLine> planned = new ArrayList<>();
        for (RecipeLineSpec line : recipeLines) {
            long quantity;
            try {
                quantity = BigDecimal.valueOf(line.quantityBase()).multiply(batchMultiplier).longValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(
                        "Item " + line.itemId() + " would need a fraction of a base unit at multiplier "
                                + batchMultiplier.toPlainString(), e);
            }
            planned.add(new PlannedLine(line.itemId(), PlannedSource.RECIPE, quantity));
        }

        long cartons = new CartonPacking(expectedPackets, packetsPerCarton).cartonsNeededToBoxAll();
        for (PackLineSpec line : packLines) {
            long multiplierBase = switch (line.basis()) {
                case PER_PACKET -> expectedPackets;
                case PER_CARTON -> cartons;
            };
            planned.add(new PlannedLine(line.itemId(), PlannedSource.PACK,
                    Math.multiplyExact(line.quantity(), multiplierBase)));
        }
        return new BomPlan(expectedPackets, List.copyOf(planned));
    }
}
