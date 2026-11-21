package com.orderaudit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UnitTest {

    /** OFFICIAL BOM quantities converted to base units. */
    @Test
    void benchmarkQuantitiesConvertToBaseUnits() {
        assertThat(Unit.KILOGRAM.toBaseUnits(new BigDecimal("40"))).isEqualTo(40_000L);   // flour
        assertThat(Unit.LITRE.toBaseUnits(new BigDecimal("10.5"))).isEqualTo(10_500L);    // oil actual
        assertThat(Unit.LITRE.toBaseUnits(new BigDecimal("10"))).isEqualTo(10_000L);      // oil planned
        assertThat(Unit.KILOGRAM.toBaseUnits(new BigDecimal("1.2"))).isEqualTo(1_200L);   // spice
        assertThat(Unit.PIECE.toBaseUnits(new BigDecimal("200"))).isEqualTo(200L);        // pouches
    }

    @Test
    void fractionOfABaseUnitIsRejected() {
        assertThatThrownBy(() -> Unit.KILOGRAM.toBaseUnits(new BigDecimal("0.0005")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convertsBetweenUnitsOfTheSameDimension() {
        assertThat(Unit.KILOGRAM.convertTo(new BigDecimal("40"), Unit.GRAM)).isEqualByComparingTo("40000");
        assertThat(Unit.GRAM.convertTo(new BigDecimal("1200"), Unit.KILOGRAM)).isEqualByComparingTo("1.2");
        assertThat(Unit.MILLILITRE.convertTo(new BigDecimal("10500"), Unit.LITRE)).isEqualByComparingTo("10.5");
    }

    @Test
    void incompatibleUnitsAreRejected() {
        assertThatThrownBy(() -> Unit.KILOGRAM.convertTo(BigDecimal.ONE, Unit.LITRE))
                .isInstanceOf(UnitMismatchException.class);
        assertThatThrownBy(() -> Unit.PIECE.convertTo(BigDecimal.ONE, Unit.PACKET))
                .isInstanceOf(UnitMismatchException.class);
    }

    @Test
    void baseUnitsAreCorrect() {
        assertThat(Unit.KILOGRAM.baseUnit()).isEqualTo(Unit.GRAM);
        assertThat(Unit.LITRE.baseUnit()).isEqualTo(Unit.MILLILITRE);
    }
}
