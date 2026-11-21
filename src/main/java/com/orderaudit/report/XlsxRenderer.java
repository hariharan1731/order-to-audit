package com.orderaudit.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Excel (.xlsx) with real typed cells: money, quantities and dates are numbers/dates (sortable and
 * summable in Excel), text stays text (never evaluated as a formula).
 * NOTE: an Excel cell can only store a number as IEEE floating point. We build every figure from exact
 * integers (paise) or BigDecimal and convert ONLY here, at the very last step before writing the file;
 * no calculation is ever done on those converted values.
 */
public final class XlsxRenderer {

    private XlsxRenderer() {
    }

    public static byte[] render(ReportTable table) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName(table.title()));
            Styles styles = new Styles(workbook);
            List<ReportColumn> columns = table.columns();

            Cell title = sheet.createRow(0).createCell(0);
            title.setCellValue(table.title());
            title.setCellStyle(styles.title);
            if (table.subtitle() != null) {
                sheet.createRow(1).createCell(0).setCellValue(table.subtitle());
            }

            Row header = sheet.createRow(3);
            for (int c = 0; c < columns.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(columns.get(c).header());
                cell.setCellStyle(styles.header);
            }
            int rowIndex = 4;
            for (List<Object> values : table.rows()) {
                writeRow(sheet.createRow(rowIndex++), columns, values, styles, false);
            }
            if (table.hasTotals()) {
                writeRow(sheet.createRow(rowIndex), columns, table.totals(), styles, true);
            }
            for (int c = 0; c < columns.size(); c++) {
                sheet.setColumnWidth(c, width(table, c) * 256);
            }
            sheet.createFreezePane(0, 4);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeRow(Row row, List<ReportColumn> columns, List<Object> values, Styles styles, boolean bold) {
        for (int c = 0; c < columns.size(); c++) {
            Object value = values.get(c);
            ColumnType type = columns.get(c).type();
            Cell cell = row.createCell(c);
            if (value == null) {
                cell.setCellStyle(styles.of(type, bold));
                continue;
            }
            switch (type) {
                case TEXT -> cell.setCellValue((String) value);
                case DATE -> cell.setCellValue((LocalDate) value);
                case MONEY -> cell.setCellValue(BigDecimal.valueOf((Long) value, 2).doubleValue());
                case QUANTITY -> cell.setCellValue((Long) value);
                case PERCENT, DECIMAL4 -> cell.setCellValue(((BigDecimal) value).doubleValue());
            }
            cell.setCellStyle(styles.of(type, bold));
        }
    }

    private static int width(ReportTable table, int column) {
        ColumnType type = table.columns().get(column).type();
        int longest = table.columns().get(column).header().length();
        for (List<Object> row : table.rows()) {
            longest = Math.max(longest, CellText.display(type, row.get(column)).length());
        }
        if (table.hasTotals()) {
            longest = Math.max(longest, CellText.display(type, table.totals().get(column)).length());
        }
        return Math.min(60, Math.max(10, longest + 2));
    }

    static String sheetName(String title) {
        String cleaned = title.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    /** Cell styles are created once per workbook (Excel limits how many styles a file may have). */
    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        private final CellStyle[] normal = new CellStyle[ColumnType.values().length];
        private final CellStyle[] bold = new CellStyle[ColumnType.values().length];

        Styles(Workbook workbook) {
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title = workbook.createCellStyle();
            title.setFont(titleFont);
            header = workbook.createCellStyle();
            header.setFont(boldFont);
            header.setBorderBottom(BorderStyle.THIN);
            DataFormat format = workbook.createDataFormat();
            for (ColumnType type : ColumnType.values()) {
                normal[type.ordinal()] = numberStyle(workbook, format, type, null);
                bold[type.ordinal()] = numberStyle(workbook, format, type, boldFont);
            }
        }

        private static CellStyle numberStyle(Workbook workbook, DataFormat format, ColumnType type, Font font) {
            CellStyle style = workbook.createCellStyle();
            if (font != null) {
                style.setFont(font);
            }
            String pattern = switch (type) {
                case TEXT -> null;
                case DATE -> "yyyy-mm-dd";
                case MONEY -> "#,##0.00";
                case QUANTITY -> "#,##0";
                case PERCENT -> "0.00";
                case DECIMAL4 -> "0.0000";
            };
            if (pattern != null) {
                style.setDataFormat(format.getFormat(pattern));
            }
            return style;
        }

        CellStyle of(ColumnType type, boolean isBold) {
            return isBold ? bold[type.ordinal()] : normal[type.ordinal()];
        }
    }
}
