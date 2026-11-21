package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.Unit;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Cost arithmetic with the OFFICIAL benchmark rates and the figures derived from them. */
class CostMathTest {

    @Test
    void officialRupeeRatesBecomePaisePerBaseUnit() {
        assertThat(CostMath.paisePerBaseUnit(new BigDecimal("42"), Unit.KILOGRAM)).isEqualByComparingTo("4.2");
        assertThat(CostMath.paisePerBaseUnit(new BigDecimal("45"), Unit.KILOGRAM)).isEqualByComparingTo("4.5");
        assertThat(CostMath.paisePerBaseUnit(new BigDecimal("140"), Unit.LITRE)).isEqualByComparingTo("14");
        assertThat(CostMath.paisePerBaseUnit(new BigDecimal("200"), Unit.KILOGRAM)).isEqualByComparingTo("20");
        assertThat(CostMath.paisePerBaseUnit(new BigDecimal("2.50"), Unit.PIECE)).isEqualByComparingTo("250");
        assertThat(CostMath.paisePerBaseUnit(new BigDecimal("15"), Unit.PIECE)).isEqualByComparingTo("1500");
    }

    @Test
    void aRateIsKeptToFourDecimalsHalfUp() {
        // Rs 1 per kg = 100 paise / 1000 g = 0.1 paise per gram; Rs 0.01 per kg = 0.001 paise per gram.
        assertThat(CostMath.paisePerBaseUnit(new BigDecimal("1"), Unit.KILOGRAM)).isEqualByComparingTo("0.1");
        assertThat(CostMath.paisePerBaseUnit(new BigDecimal("0.01"), Unit.KILOGRAM)).isEqualByComparingTo("0.001");
    }

    @Test
    void officialLineCosts() {
        assertThat(CostMath.lineCostPaise(40_000, new BigDecimal("4.3125"))).isEqualTo(172_500);   // flour
        assertThat(CostMath.lineCostPaise(10_500, new BigDecimal("14"))).isEqualTo(147_000);       // oil
        assertThat(CostMath.lineCostPaise(1_200, new BigDecimal("20"))).isEqualTo(24_000);         // spice
        assertThat(CostMath.lineCostPaise(194, new BigDecimal("250"))).isEqualTo(48_500);          // pouches
        assertThat(CostMath.lineCostPaise(9, new BigDecimal("1500"))).isEqualTo(13_500);           // cartons
    }

    @Test
    void aLineCostRoundsHalfUpToWholePaise() {
        assertThat(CostMath.lineCostPaise(1, new BigDecimal("0.5"))).isEqualTo(1);
        assertThat(CostMath.lineCostPaise(1, new BigDecimal("0.4999"))).isEqualTo(0);
        assertThat(CostMath.lineCostPaise(3, new BigDecimal("0.1667"))).isEqualTo(1);   // 0.5001 -> 1
    }

    @Test
    void costPerPacketForTheBenchmarkRunIs3114Point3617Paise() {
        // materials 405,500 + labour 120,000 + gas/power 60,000 = 585,500 paise over 188 good packets
        BigDecimal perPacket = CostMath.costPerPacket(585_500, 188);
        assertThat(perPacket).isEqualByComparingTo("3114.3617");
        assertThat(CostMath.roundToPaise(perPacket)).isEqualTo(3_114);   // Rs 31.14
    }

    @Test
    void ifTenCartonsHadBeenUsedTheCostPerPacketWouldBeHigher() {
        assertThat(CostMath.costPerPacket(587_000, 188)).isEqualByComparingTo("3122.3404");
    }

    @Test
    void costPerPacketNeedsAtLeastOneGoodPacket() {
        assertThatThrownBy(() -> CostMath.costPerPacket(1_000, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANegativeRate() {
        assertThatThrownBy(() -> CostMath.paisePerBaseUnit(new BigDecimal("-1"), Unit.KILOGRAM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void marginAgainstThe50RupeePriceIs37Point71Percent() {
        BigDecimal cost = CostMath.costPerPacket(585_500, 188);
        BigDecimal margin = new BigDecimal("5000").subtract(cost);
        assertThat(margin).isEqualByComparingTo("1885.6383");
        assertThat(margin.multiply(BigDecimal.valueOf(100)).divide(new BigDecimal("5000"), 2,
                java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("37.71");
    }
}
