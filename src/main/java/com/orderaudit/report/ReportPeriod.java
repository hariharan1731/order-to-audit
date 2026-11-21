package com.orderaudit.report;

import java.time.LocalDate;
import java.time.ZoneId;

/** An inclusive date range chosen by the user. Dates are in Indian time. */
public record ReportPeriod(LocalDate from, LocalDate to) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    public ReportPeriod {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both dates are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("The end date must not be before the start date");
        }
    }

    public String label() {
        return from + " to " + to;
    }

    /** Today in Indian time. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
