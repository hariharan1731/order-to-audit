package com.orderaudit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ItemTypeTest {

    @Test
    void rawMaterialsUseGramsOrMillilitres() {
        assertThat(ItemType.RAW.allows(Unit.GRAM)).isTrue();       // flour, spice
        assertThat(ItemType.RAW.allows(Unit.MILLILITRE)).isTrue(); // oil
        assertThat(ItemType.RAW.allows(Unit.PIECE)).isFalse();
        assertThat(ItemType.RAW.allows(Unit.KILOGRAM)).isFalse();  // base units only
    }

    @Test
    void packagingIsCountedInPieces() {
        assertThat(ItemType.PACKAGING.allows(Unit.PIECE)).isTrue(); // pouch, carton
        assertThat(ItemType.PACKAGING.allows(Unit.PACKET)).isFalse();
    }

    @Test
    void finishedGoodsAreCountedInPackets() {
        assertThat(ItemType.FINISHED.allows(Unit.PACKET)).isTrue();
        assertThat(ItemType.FINISHED.allows(Unit.GRAM)).isFalse();
    }
}
