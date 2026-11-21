package com.orderaudit.web;

import com.orderaudit.report.CellText;
import com.orderaudit.report.ColumnType;
import com.orderaudit.report.ReportColumn;
import com.orderaudit.report.ReportTable;
import java.util.ArrayList;
import java.util.List;

/** A ReportTable turned into plain strings for a web page (numbers formatted, alignment flags). */
public record HtmlTable(String title, String subtitle, List<String> headers, List<Boolean> numeric,
                        List<List<String>> rows, List<String> totals) {

    public static HtmlTable of(ReportTable table) {
        List<String> headers = new ArrayList<>();
        List<Boolean> numeric = new ArrayList<>();
        for (ReportColumn c : table.columns()) {
            headers.add(c.header());
            numeric.add(c.type() != ColumnType.TEXT && c.type() != ColumnType.DATE);
        }
        List<List<String>> rows = new ArrayList<>();
        for (List<Object> row : table.rows()) {
            rows.add(format(table.columns(), row));
        }
        return new HtmlTable(table.title(), table.subtitle(), headers, numeric, rows,
                table.hasTotals() ? format(table.columns(), table.totals()) : null);
    }

    private static List<String> format(List<ReportColumn> columns, List<Object> row) {
        List<String> cells = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            cells.add(CellText.display(columns.get(i).type(), row.get(i)));
        }
        return cells;
    }
}
