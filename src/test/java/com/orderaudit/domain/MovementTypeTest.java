package com.orderaudit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MovementTypeTest {

    @Test
    void stockInTypesAcceptOnlyPositiveQuantities() {
        for (MovementType type : new MovementType[]{
                MovementType.OPENING, MovementType.PURCHASE, MovementType.PROD_OUTPUT, MovementType.RETURN}) {
            assertThat(type.allows(1)).as(type + " +1").isTrue();
            assertThat(type.allows(-1)).as(type + " -1").isFalse();
            assertThat(type.allows(0)).as(type + " 0").isFalse();
        }
    }

    @Test
    void stockOutTypesAcceptOnlyNegativeQuantities() {
        for (MovementType type : new MovementType[]{MovementType.PROD_ISSUE, MovementType.SALE}) {
            assertThat(type.allows(-1)).as(type + " -1").isTrue();
            assertThat(type.allows(1)).as(type + " +1").isFalse();
            assertThat(type.allows(0)).as(type + " 0").isFalse();
        }
    }

    @Test
    void adjustmentAndReversalAcceptEitherDirectionButNeverZero() {
        for (MovementType type : new MovementType[]{MovementType.ADJUST, MovementType.REVERSAL}) {
            assertThat(type.allows(5)).isTrue();
            assertThat(type.allows(-5)).isTrue();
            assertThat(type.allows(0)).isFalse();
        }
    }
}
