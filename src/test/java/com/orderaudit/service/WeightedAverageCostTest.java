package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderaudit.domain.CostMovement;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeightedAverageCostTest {

    private static CostMovement in(long id, long qty, String cost) {
        return new CostMovement(id, qty, cost == null ? null : new BigDecimal(cost), null);
    }

    private static CostMovement out(long id, long qty) {
        return new CostMovement(id, -qty, null, null);
    }

    private static CostMovement reversal(long id, long qtySigned, long reverses) {
        return new CostMovement(id, qtySigned, null, reverses);
    }

    @Test
    void anItemWithNoHistoryHasUnknownCost() {
        assertThat(WeightedAverageCost.replay(List.of())).isNull();
    }

    @Test
    void officialFlourAverageIs4Point3125Paise() {
        // 100,000 g at 4.2 (Rs 42/kg) + 60,000 g at 4.5 (Rs 45/kg) = 690,000 / 160,000
        BigDecimal avg = WeightedAverageCost.replay(List.of(in(1, 100_000, "4.2"), in(2, 60_000, "4.5")));
        assertThat(avg).isEqualByComparingTo("4.3125");
    }

    @Test
    void anIssueDoesNotChangeTheAverage() {
        BigDecimal avg = WeightedAverageCost.replay(List.of(in(1, 100_000, "4.2"), out(2, 40_000)));
        assertThat(avg).isEqualByComparingTo("4.2");
    }

    @Test
    void aPurchaseAfterAnIssueAveragesWithTheRemainingStock() {
        // 100 at 10, issue 50, buy 50 at 20 -> (50x10 + 50x20) / 100 = 15
        BigDecimal avg = WeightedAverageCost.replay(List.of(in(1, 100, "10"), out(2, 50), in(3, 50, "20")));
        assertThat(avg).isEqualByComparingTo("15");
    }

    @Test
    void whenStockHitsZeroTheNextReceiptStartsAFreshAverage() {
        BigDecimal avg = WeightedAverageCost.replay(List.of(in(1, 100, "10"), out(2, 100), in(3, 50, "20")));
        assertThat(avg).isEqualByComparingTo("20");
    }

    @Test
    void unvaluedReceiptsDoNotChangeTheAverage() {
        BigDecimal avg = WeightedAverageCost.replay(List.of(in(1, 100, "10"), in(2, 100, null)));
        assertThat(avg).isEqualByComparingTo("10");
    }

    @Test
    void ifEveryReceiptIsUnvaluedTheCostStaysUnknown() {
        assertThat(WeightedAverageCost.replay(List.of(in(1, 50, null)))).isNull();
    }

    @Test
    void aValuedReceiptAfterUnvaluedStockSetsTheAverageFromThatReceipt() {
        BigDecimal avg = WeightedAverageCost.replay(List.of(in(1, 50, null), in(2, 100, "10")));
        assertThat(avg).isEqualByComparingTo("10");
    }

    @Test
    void aReversedPurchaseLeavesNoTraceInTheAverage() {
        BigDecimal avg = WeightedAverageCost.replay(List.of(
                in(1, 100_000, "4.2"), in(2, 60_000, "4.5"), reversal(3, -60_000, 2)));
        assertThat(avg).isEqualByComparingTo("4.2");
    }

    @Test
    void anAverageIsKeptToFourDecimalsHalfUp() {
        // (1 x 1 + 2 x 2) / 3 = 1.6666... -> 1.6667
        BigDecimal avg = WeightedAverageCost.replay(List.of(in(1, 1, "1"), in(2, 2, "2")));
        assertThat(avg).isEqualByComparingTo("1.6667");
    }
}
