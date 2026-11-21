package com.orderaudit.domain;

/** How old an unpaid invoice is, counted in days from the invoice date: 0-30, 31-60, 61-90, over 90. */
public enum AgeingBucket {
    DAYS_0_30, DAYS_31_60, DAYS_61_90, OVER_90;

    public static AgeingBucket of(long daysOld) {
        if (daysOld <= 30) {          // includes future-dated invoices (negative days): they are "current"
            return DAYS_0_30;
        }
        if (daysOld <= 60) {
            return DAYS_31_60;
        }
        if (daysOld <= 90) {
            return DAYS_61_90;
        }
        return OVER_90;
    }
}
