package com.orderaudit.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Turns a cell into text. Two styles:
 *   plain   : machine friendly, no thousands separators (7875.00)  -> CSV
 *   display : human friendly, with separators (7,875.00)           -> PDF and web
 * Money is exact: paise are placed into a BigDecimal with 2 decimals, never a floating-point number.
 */
public final class CellText {

    private CellText() {
    }

    public static String plain(ColumnType type, Object cell) {
        if (cell == null) {
            return "";
        }
        return switch (type) {
            case TEXT -> (String) cell;
            case DATE -> ((LocalDate) cell).toString();
            case MONEY -> BigDecimal.valueOf((Long) cell, 2).toPlainString();
            case QUANTITY -> cell.toString();
            case PERCENT -> ((BigDecimal) cell).setScale(2, RoundingMode.HALF_UP).toPlainString();
            case DECIMAL4 -> ((BigDecimal) cell).setScale(4, RoundingMode.HALF_UP).toPlainString();
        };
    }

    public static String display(ColumnType type, Object cell) {
        if (cell == null) {
            return "";
        }
        return switch (type) {
            case TEXT -> (String) cell;
            case DATE -> ((LocalDate) cell).toString();
            case MONEY -> String.format(Locale.ENGLISH, "%,.2f", BigDecimal.valueOf((Long) cell, 2));
            case QUANTITY -> String.format(Locale.ENGLISH, "%,d", (Long) cell);
            case PERCENT -> String.format(Locale.ENGLISH, "%,.2f",
                    ((BigDecimal) cell).setScale(2, RoundingMode.HALF_UP));
            case DECIMAL4 -> String.format(Locale.ENGLISH, "%,.4f",
                    ((BigDecimal) cell).setScale(4, RoundingMode.HALF_UP));
        };
    }
}
