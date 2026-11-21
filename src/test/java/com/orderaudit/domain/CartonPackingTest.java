package com.orderaudit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CartonPackingTest {

    /** OFFICIAL BOM: 200 planned packets at 20 per carton = 10 cartons. */
    @Test
    void plannedBatchNeedsTenCartons() {
        CartonPacking planned = new CartonPacking(200, 20);
        assertThat(planned.fullCartons()).isEqualTo(10);
        assertThat(planned.loosePackets()).isZero();
        assertThat(planned.cartonsNeededToBoxAll()).isEqualTo(10);
    }

    /** OFFICIAL inputs: 188 good packets, 9 cartons recorded. 188 / 20 = 9.4. */
    @Test
    void actualOutputExplainsTheCartonGap() {
        CartonPacking actual = new CartonPacking(188, 20);
        assertThat(actual.fullCartons()).isEqualTo(9);
        assertThat(actual.loosePackets()).isEqualTo(8);
        assertThat(actual.cartonsNeededToBoxAll()).isEqualTo(10);
        assertThat(actual.uncartonedPackets(9)).isEqualTo(8);   // the warning shown to the supervisor
        assertThat(actual.uncartonedPackets(10)).isZero();
    }

    @Test
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> new CartonPacking(10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CartonPacking(-1, 20)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroPacketsNeedZeroCartons() {
        assertThat(new CartonPacking(0, 20).cartonsNeededToBoxAll()).isZero();
    }
}
