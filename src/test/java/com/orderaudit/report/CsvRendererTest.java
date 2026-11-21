package com.orderaudit.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvRendererTest {

    private static List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }

    private static String[] lines(ReportTable table) {
        return new String(CsvRenderer.render(table), StandardCharsets.UTF_8).split("\r\n");
    }

    private final List<ReportColumn> columns = List.of(ReportColumn.date("Date"), ReportColumn.text("Customer"),
            ReportColumn.money("Total (Rs)"), ReportColumn.percent("Var %"), ReportColumn.decimal4("Rate"),
            ReportColumn.quantity("Qty"));

    @Test
    void startsWithAByteOrderMarkSoExcelReadsUtf8() {
        ReportTable table = new ReportTable("T", null, columns, List.of(), null);

        assertThat(lines(table)[0]).isEqualTo("﻿Date,Customer,Total (Rs),Var %,Rate,Qty");
    }

    @Test
    void writesTypedCellsInPlainMachineFriendlyForm() {
        ReportTable table = new ReportTable("T", null, columns, List.of(
                row(LocalDate.of(2026, 11, 21), "Retailer A", 787_500L, new BigDecimal("-6"), new BigDecimal("4.3125"), 150L)),
                null);

        assertThat(lines(table)[1]).isEqualTo("2026-11-21,Retailer A,7875.00,-6.00,4.3125,150");
    }

    @Test
    void smallAndNegativeMoneyKeepTheirPaise() {
        ReportTable table = new ReportTable("T", null, List.of(ReportColumn.money("M")),
                List.of(row(5L), row(-5L), row(0L), row(100L)), null);

        assertThat(lines(table)).containsExactly("﻿M", "0.05", "-0.05", "0.00", "1.00");
    }

    @Test
    void quotesFieldsWithCommasQuotesAndLineBreaks() {
        ReportTable table = new ReportTable("T", null, List.of(ReportColumn.text("Name")),
                List.of(row("Retailer, \"A\""), row("two\nlines")), null);

        String csv = new String(CsvRenderer.render(table), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"Retailer, \"\"A\"\"\"").contains("\"two\nlines\"");
    }

    @Test
    void textThatLooksLikeAFormulaIsNeutralised() {
        ReportTable table = new ReportTable("T", null, List.of(ReportColumn.text("Name")),
                List.of(row("=HYPERLINK(\"http://evil\")"), row("+1+1"), row("-2"), row("@SUM(A1)"), row("normal")), null);

        String[] out = lines(table);
        assertThat(out[1]).startsWith("\"'=HYPERLINK");
        assertThat(out[2]).isEqualTo("'+1+1");
        assertThat(out[3]).isEqualTo("'-2");
        assertThat(out[4]).isEqualTo("'@SUM(A1)");
        assertThat(out[5]).isEqualTo("normal");
    }

    @Test
    void numbersAreNeverAltered() {
        ReportTable table = new ReportTable("T", null, List.of(ReportColumn.money("M")),
                List.of(row(-12_345L)), null);

        assertThat(lines(table)[1]).isEqualTo("-123.45");   // a negative NUMBER keeps its minus sign
    }

    @Test
    void nullCellsAreBlankAndTheTotalsRowComesLast() {
        ReportTable table = new ReportTable("T", null, columns, List.of(
                row(LocalDate.of(2026, 11, 22), "X", 5L, null, null, null)),
                row(null, "TOTAL", 787_505L, null, null, 150L));

        String[] out = lines(table);
        assertThat(out[1]).isEqualTo("2026-11-22,X,0.05,,,");
        assertThat(out[2]).isEqualTo(",TOTAL,7875.05,,,150");
    }
}
