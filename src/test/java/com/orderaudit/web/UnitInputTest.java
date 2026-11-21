package com.orderaudit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.Unit;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UnitInputTest {

    @Test
    void kilogramsTypedByStaffBecomeWholeGrams() {
        assertThat(UnitInput.toBase("1.2", Unit.GRAM)).isEqualTo(1_200);
        assertThat(UnitInput.toBase("40", Unit.GRAM)).isEqualTo(40_000);
    }

    @Test
    void litresBecomeMillilitresAndPiecesStayAsTheyAre() {
        assertThat(UnitInput.toBase("10.5", Unit.MILLILITRE)).isEqualTo(10_500);
        assertThat(UnitInput.toBase("194", Unit.PIECE)).isEqualTo(194);
    }

    @Test
    void aFractionOfAGramIsRefusedNotRounded() {
        assertThatThrownBy(() -> UnitInput.toBase("0.0005", Unit.GRAM))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too many decimals");
    }

    @Test
    void textThatIsNotANumberIsRefused() {
        assertThatThrownBy(() -> UnitInput.toBase("abc", Unit.GRAM))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a number");
    }

    @Test
    void displayAndLabelUseTheReadableUnit() {
        assertThat(UnitInput.toDisplay(1_200, Unit.GRAM)).isEqualTo("1.2");
        assertThat(UnitInput.toDisplay(40_000, Unit.GRAM)).isEqualTo("40");
        assertThat(UnitInput.label(Unit.GRAM)).isEqualTo("kg");
        assertThat(UnitInput.label(Unit.MILLILITRE)).isEqualTo("L");
    }

    @Test
    void rupeesPerKilogramBecomePaisePerGram() {
        assertThat(UnitInput.ratePerBaseUnit("45", Unit.GRAM)).isEqualByComparingTo(new BigDecimal("4.5"));
        assertThat(UnitInput.ratePerBaseUnit("140", Unit.MILLILITRE)).isEqualByComparingTo(new BigDecimal("14"));
    }
}
