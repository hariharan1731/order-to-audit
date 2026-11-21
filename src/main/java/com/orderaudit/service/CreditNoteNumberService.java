package com.orderaudit.service;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gapless credit note numbers per financial year, exactly like invoice numbers (CN/2026-27/0001). */
@Service
public class CreditNoteNumberService {

    private static final String NEXT_NUMBER = "INSERT INTO credit_note_sequence (fy_label, last_number) VALUES (?, 1) "
            + "ON CONFLICT (fy_label) DO UPDATE SET last_number = credit_note_sequence.last_number + 1 "
            + "RETURNING last_number";

    private final JdbcTemplate jdbc;

    public CreditNoteNumberService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public String next(LocalDate creditDate) {
        String fy = FinancialYear.label(creditDate);
        Integer number = jdbc.queryForObject(NEXT_NUMBER, Integer.class, fy);
        if (number == null) {
            throw new IllegalStateException("Could not allocate a credit note number");
        }
        return FinancialYear.creditNoteNumber(fy, number);
    }
}
