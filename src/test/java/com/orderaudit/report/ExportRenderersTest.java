package com.orderaudit.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.pdf.PdfReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** Excel and PDF output of a small sample report (the sales register shape). */
class ExportRenderersTest {

    private static List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }

    private ReportTable sample() {
        return new ReportTable("Sales register", "2026-11-01 to 2026-11-30",
                List.of(ReportColumn.date("Date"), ReportColumn.text("Customer"), ReportColumn.money("Total (Rs)"),
                        ReportColumn.percent("Var %"), ReportColumn.decimal4("Rate"), ReportColumn.quantity("Qty")),
                List.of(row(LocalDate.of(2026, 11, 21), "Retailer A", 787_500L, new BigDecimal("-6"),
                                new BigDecimal("4.3125"), 150L),
                        row(LocalDate.of(2026, 11, 22), "=cmd()", 5L, null, null, null)),
                row(null, "TOTAL", 787_505L, null, null, 150L));
    }

    @Test
    void excelHasTypedCellsAndTheRightValues() throws IOException {
        byte[] bytes = XlsxRenderer.render(sample());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Sales register");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("Customer");   // header row
            Row first = sheet.getRow(4);
            assertThat(first.getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 21));
            assertThat(first.getCell(1).getStringCellValue()).isEqualTo("Retailer A");
            assertThat(first.getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(first.getCell(2).getNumericCellValue()).isEqualTo(7875.0);
            assertThat(first.getCell(3).getNumericCellValue()).isEqualTo(-6.0);
            assertThat(first.getCell(4).getNumericCellValue()).isEqualTo(4.3125);
            assertThat(first.getCell(5).getNumericCellValue()).isEqualTo(150.0);
            Row second = sheet.getRow(5);
            assertThat(second.getCell(2).getNumericCellValue()).isEqualTo(0.05);
            assertThat(second.getCell(3).getCellType()).isEqualTo(CellType.BLANK);            // null -> blank
            assertThat(sheet.getRow(6).getCell(1).getStringCellValue()).isEqualTo("TOTAL");
            assertThat(sheet.getRow(6).getCell(2).getNumericCellValue()).isEqualTo(7875.05);
        }
    }

    @Test
    void textThatLooksLikeAFormulaStaysTextInExcel() throws IOException {
        byte[] bytes = XlsxRenderer.render(sample());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var cell = workbook.getSheetAt(0).getRow(5).getCell(1);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);   // not FORMULA
            assertThat(cell.getStringCellValue()).isEqualTo("=cmd()");
        }
    }

    @Test
    void excelSheetNamesAreMadeLegal() {
        assertThat(XlsxRenderer.sheetName("Stock ledger - Murukku 200 g: a/b?*[x]")).doesNotContain("/", "?", "*", "[", "]", ":");
        assertThat(XlsxRenderer.sheetName("A very long report title that is longer than thirty one characters")).hasSize(31);
    }

    @Test
    void pdfIsAValidDocumentWithAtLeastOnePage() throws IOException {
        byte[] bytes = PdfRenderer.render(sample());

        assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        PdfReader reader = new PdfReader(bytes);
        assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        reader.close();
    }

    @Test
    void aVeryLongReportSpansSeveralPdfPages() throws IOException {
        List<List<Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            rows.add(row("Row " + i, (long) i));
        }
        ReportTable table = new ReportTable("Long", null,
                List.of(ReportColumn.text("Name"), ReportColumn.quantity("N")), rows, null);

        PdfReader reader = new PdfReader(PdfRenderer.render(table));

        assertThat(reader.getNumberOfPages()).isGreaterThan(1);
        reader.close();
    }

    @Test
    void anEmptyReportStillProducesAPdfAndAnExcelFile() throws IOException {
        ReportTable empty = new ReportTable("Empty", "none",
                List.of(ReportColumn.text("A"), ReportColumn.money("B")), List.of(), null);

        PdfReader reader = new PdfReader(PdfRenderer.render(empty));
        assertThat(reader.getNumberOfPages()).isEqualTo(1);
        reader.close();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(XlsxRenderer.render(empty)))) {
            assertThat(workbook.getSheetAt(0).getRow(3).getCell(0).getStringCellValue()).isEqualTo("A");
        }
    }

    @Test
    void exportServiceChoosesTheRightRendererAndFileName() {
        ReportExportService service = new ReportExportService();

        ExportedFile csv = service.export(sample(), ReportFormat.CSV);
        ExportedFile xlsx = service.export(sample(), ReportFormat.XLSX);
        ExportedFile pdf = service.export(sample(), ReportFormat.PDF);

        assertThat(csv.fileName()).isEqualTo("sales-register.csv");
        assertThat(csv.contentType()).startsWith("text/csv");
        assertThat(xlsx.fileName()).isEqualTo("sales-register.xlsx");
        assertThat(new String(xlsx.content(), 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");   // a zip container
        assertThat(pdf.fileName()).isEqualTo("sales-register.pdf");
        assertThat(pdf.contentType()).isEqualTo("application/pdf");
    }
}
