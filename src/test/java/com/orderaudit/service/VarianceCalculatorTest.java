package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.Variance;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Variance maths. The first four tests are the OFFICIAL benchmark checks (tolerance 3%). */
class VarianceCalculatorTest {

    private static final BigDecimal THREE = new BigDecimal("3");

    @Test
    void officialYieldIs188Against200AndMinusSixPercentFlagged() {
        Variance v = VarianceCalculator.compute(200, 188, THREE);
        assertThat(v.percent()).isEqualByComparingTo("-6.00");
        assertThat(v.flagged()).isTrue();
    }

    @Test
    void officialOilIsPlusFivePercentFlagged() {
        Variance v = VarianceCalculator.compute(10_000, 10_500, THREE);
        assertThat(v.percent()).isEqualByComparingTo("5.00");
        assertThat(v.flagged()).isTrue();
    }

    @Test
    void officialPouchesAreMinusThreePercentAndNotFlaggedBecauseThreeIsNotBeyondThree() {
        Variance v = VarianceCalculator.compute(200, 194, THREE);
        assertThat(v.percent()).isEqualByComparingTo("-3.00");
        assertThat(v.flagged()).isFalse();
    }

    @Test
    void officialCartonsNineAgainstTenIsMinusTenPercentFlagged() {
        Variance v = VarianceCalculator.compute(10, 9, THREE);
        assertThat(v.percent()).isEqualByComparingTo("-10.00");
        assertThat(v.flagged()).isTrue();
    }

    @Test
    void usingExactlyThePlannedQuantityIsZeroAndNotFlagged() {
        Variance v = VarianceCalculator.compute(40_000, 40_000, THREE);
        assertThat(v.percent()).isEqualByComparingTo("0.00");
        assertThat(v.flagged()).isFalse();
    }

    @Test
    void theFlagUsesExactArithmeticNotTheRoundedPercentage() {
        // 34,333 vs 33,333 is 3.0000300...% : it DISPLAYS as 3.00 but is truly beyond 3%, so it is flagged.
        Variance v = VarianceCalculator.compute(33_333, 34_333, THREE);
        assertThat(v.percent()).isEqualByComparingTo("3.00");
        assertThat(v.flagged()).isTrue();
    }

    @Test
    void justUnderTheToleranceIsNotFlagged() {
        Variance v = VarianceCalculator.compute(33_333, 34_332, THREE);   // 2.99970...%
        assertThat(v.flagged()).isFalse();
    }

    @Test
    void plusThreePercentExactlyIsNotFlagged() {
        assertThat(VarianceCalculator.compute(1_000, 1_030, THREE).flagged()).isFalse();
        assertThat(VarianceCalculator.compute(1_000, 1_031, THREE).flagged()).isTrue();
    }

    @Test
    void aZeroToleranceFlagsAnyDifferenceAtAll() {
        assertThat(VarianceCalculator.compute(1_000, 1_001, BigDecimal.ZERO).flagged()).isTrue();
        assertThat(VarianceCalculator.compute(1_000, 1_000, BigDecimal.ZERO).flagged()).isFalse();
    }

    @Test
    void nothingPlannedAndNothingUsedIsZeroAndNotFlagged() {
        Variance v = VarianceCalculator.compute(0, 0, THREE);
        assertThat(v.percent()).isEqualByComparingTo("0.00");
        assertThat(v.flagged()).isFalse();
    }

    @Test
    void usingSomethingThatWasNotPlannedIsFlaggedWithNoPercentage() {
        Variance v = VarianceCalculator.compute(0, 5, THREE);
        assertThat(v.percent()).isNull();
        assertThat(v.flagged()).isTrue();
    }

    @Test
    void rejectsNegativeInputs() {
        assertThatThrownBy(() -> VarianceCalculator.compute(-1, 5, THREE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VarianceCalculator.compute(5, -1, THREE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VarianceCalculator.compute(5, 5, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VarianceCalculator.compute(5, 5, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
