package com.orderaudit.service;

import com.orderaudit.domain.CostMovement;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Weighted average cost, by replaying an item's ledger in order.
 *   receipt with a cost : new average = (stock x average + received x cost) / (stock + received)
 *   issue or sale       : the average does NOT change (only the quantity falls)
 *   receipt without cost: stock rises but the average is not affected (the units are "unvalued")
 * A reversed movement and its reversal row are both skipped, so reversing a purchase restores the
 * previous average exactly. Returns null if no valued receipt has happened (cost unknown).
 * The average is kept to 4 decimals, half up, after each receipt.
 */
public final class WeightedAverageCost {

    private WeightedAverageCost() {
    }

    public static BigDecimal replay(List<CostMovement> movements) {
        Set<Long> cancelled = new HashSet<>();
        for (CostMovement m : movements) {
            if (m.reversesId() != null) {
                cancelled.add(m.reversesId());
                cancelled.add(m.id());
            }
        }
        long stock = 0;
        BigDecimal average = null;
        for (CostMovement m : movements) {
            if (cancelled.contains(m.id())) {
                continue;
            }
            if (m.qtySigned() > 0) {
                if (m.unitCost() != null) {
                    if (stock == 0 || average == null) {
                        average = m.unitCost();
                    } else {
                        BigDecimal value = average.multiply(BigDecimal.valueOf(stock))
                                .add(m.unitCost().multiply(BigDecimal.valueOf(m.qtySigned())));
                        average = value.divide(BigDecimal.valueOf(stock + m.qtySigned()), 4, RoundingMode.HALF_UP);
                    }
                }
                stock += m.qtySigned();
            } else {
                stock = Math.max(0, stock + m.qtySigned());
            }
        }
        return average;
    }
}
