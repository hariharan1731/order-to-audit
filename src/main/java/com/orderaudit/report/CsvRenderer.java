package com.orderaudit.report;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV for Excel, Tally imports and auditors. Rules:
 *  - UTF-8 with a byte-order mark so Excel reads rupee and Tamil text correctly
 *  - fields containing a comma, quote or line break are quoted, quotes doubled (RFC 4180)
 *  - TEXT cells starting with = + - @ get a leading apostrophe so a spreadsheet can never run them as a
 *    formula ("CSV injection"); numbers are written plain and are never altered
 */
public final class CsvRenderer {

    private CsvRenderer() {
    }

    public static byte[] render(ReportTable table) {
        StringBuilder out = new StringBuilder("﻿");
        List<ReportColumn> columns = table.columns();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(field(columns.get(i).header()));
        }
        out.append("\r\n");
        for (List<Object> row : table.rows()) {
            appendRow(out, columns, row);
        }
        if (table.hasTotals()) {
            appendRow(out, columns, table.totals());
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendRow(StringBuilder out, List<ReportColumn> columns, List<Object> row) {
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            ColumnType type = columns.get(i).type();
            String text = CellText.plain(type, row.get(i));
            out.append(field(type == ColumnType.TEXT ? neutralizeFormula(text) : text));
        }
        out.append("\r\n");
    }

    static String neutralizeFormula(String text) {
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            return "'" + text;
        }
        return text;
    }

    static String field(String text) {
        boolean needsQuotes = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        return needsQuotes ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }
}
