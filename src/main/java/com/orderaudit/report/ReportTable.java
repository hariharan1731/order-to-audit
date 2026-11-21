package com.orderaudit.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A finished report: title, subtitle (the period and rules used), columns, rows and an optional totals row.
 * Cells may be null (shown blank). The constructor checks that every cell has the right Java type for its
 * column, so a wrong report is caught immediately instead of printing garbage in a file.
 */
public record ReportTable(String title, String subtitle, List<ReportColumn> columns,
                          List<List<Object>> rows, List<Object> totals) {

    public ReportTable {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A report needs a title");
        }
        columns = List.copyOf(columns);
        for (List<Object> row : rows) {
            check(columns, row);
        }
        rows = List.copyOf(rows);
        if (totals != null) {
            check(columns, totals);
        }
    }

    private static void check(List<ReportColumn> columns, List<Object> row) {
        if (row.size() != columns.size()) {
            throw new IllegalArgumentException("Row has " + row.size() + " cells but the report has "
                    + columns.size() + " columns");
        }
        for (int i = 0; i < columns.size(); i++) {
            Object cell = row.get(i);
            if (cell == null) {
                continue;
            }
            boolean ok = switch (columns.get(i).type()) {
                case TEXT -> cell instanceof String;
                case DATE -> cell instanceof LocalDate;
                case MONEY, QUANTITY -> cell instanceof Long;
                case PERCENT, DECIMAL4 -> cell instanceof BigDecimal;
            };
            if (!ok) {
                throw new IllegalArgumentException("Column '" + columns.get(i).header() + "' ("
                        + columns.get(i).type() + ") cannot hold " + cell.getClass().getSimpleName());
            }
        }
    }

    public boolean hasTotals() {
        return totals != null;
    }
}
