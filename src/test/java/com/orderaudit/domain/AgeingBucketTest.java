package com.orderaudit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgeingBucketTest {

    @Test
    void zeroToThirtyDaysIsCurrent() {
        assertThat(AgeingBucket.of(0)).isEqualTo(AgeingBucket.DAYS_0_30);
        assertThat(AgeingBucket.of(30)).isEqualTo(AgeingBucket.DAYS_0_30);
    }

    @Test
    void thirtyOneToSixtyDays() {
        assertThat(AgeingBucket.of(31)).isEqualTo(AgeingBucket.DAYS_31_60);
        assertThat(AgeingBucket.of(60)).isEqualTo(AgeingBucket.DAYS_31_60);
    }

    @Test
    void sixtyOneToNinetyDays() {
        assertThat(AgeingBucket.of(61)).isEqualTo(AgeingBucket.DAYS_61_90);
        assertThat(AgeingBucket.of(90)).isEqualTo(AgeingBucket.DAYS_61_90);
    }

    @Test
    void overNinetyDays() {
        assertThat(AgeingBucket.of(91)).isEqualTo(AgeingBucket.OVER_90);
        assertThat(AgeingBucket.of(400)).isEqualTo(AgeingBucket.OVER_90);
    }

    @Test
    void aFutureDatedInvoiceIsCurrent() {
        assertThat(AgeingBucket.of(-5)).isEqualTo(AgeingBucket.DAYS_0_30);
    }

    @Test
    void theBucketOrdinalsMatchTheOrderUsedByTheAgeingReport() {
        assertThat(AgeingBucket.DAYS_0_30.ordinal()).isZero();
        assertThat(AgeingBucket.DAYS_31_60.ordinal()).isEqualTo(1);
        assertThat(AgeingBucket.DAYS_61_90.ordinal()).isEqualTo(2);
        assertThat(AgeingBucket.OVER_90.ordinal()).isEqualTo(3);
    }
}
