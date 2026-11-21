package com.orderaudit.service;

import java.time.LocalDate;

/** Indian financial year: 1 April to 31 March. 3 Nov 2026 -> "2026-27"; 15 Feb 2027 -> "2026-27". */
public final class FinancialYear {

    private FinancialYear() {
    }

    public static String label(LocalDate date) {
        int startYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return startYear + "-" + String.format("%02d", (startYear + 1) % 100);
    }

    /** INV/2026-27/0001 */
    public static String invoiceNumber(String fyLabel, int sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("Invoice sequence starts at 1");
        }
        return String.format("INV/%s/%04d", fyLabel, sequence);
    }

    /** CN/2026-27/0001 */
    public static String creditNoteNumber(String fyLabel, int sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("Credit note sequence starts at 1");
        }
        return String.format("CN/%s/%04d", fyLabel, sequence);
    }
}
