package com.orderaudit.service;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out gapless invoice numbers per financial year. One UPSERT both creates or increments the
 * counter row and locks it until the transaction ends, so two invoices can never get the same number,
 * and if the invoice fails and rolls back, the number is rolled back too (no gap).
 */
@Service
public class InvoiceNumberService {

    private static final String NEXT_NUMBER = "INSERT INTO invoice_sequence (fy_label, last_number) VALUES (?, 1) "
            + "ON CONFLICT (fy_label) DO UPDATE SET last_number = invoice_sequence.last_number + 1 "
            + "RETURNING last_number";

    private final JdbcTemplate jdbc;

    public InvoiceNumberService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public String next(LocalDate invoiceDate) {
        String fy = FinancialYear.label(invoiceDate);
        Integer number = jdbc.queryForObject(NEXT_NUMBER, Integer.class, fy);
        if (number == null) {
            throw new IllegalStateException("Could not allocate an invoice number");
        }
        return FinancialYear.invoiceNumber(fy, number);
    }
}
