package com.orderaudit.service;

import com.orderaudit.report.ReportPeriod;
import java.time.LocalDate;

/** Simple date sanity checks shared by the services. "Today" is today in India (Asia/Kolkata). */
final class DateRules {

    private DateRules() {
    }

    /** Rejects a date that is still to come, e.g. a payment "received" next week. */
    static void notInFuture(LocalDate date, String what) {
        if (date.isAfter(ReportPeriod.today())) {
            throw new IllegalArgumentException(what + " cannot be in the future");
        }
    }
}
