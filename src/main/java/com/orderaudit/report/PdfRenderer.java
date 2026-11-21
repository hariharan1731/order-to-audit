package com.orderaudit.report;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * PDF of a report: title, subtitle, then a table with a repeating header row. Wide reports (more than six
 * columns) use a landscape page. Numbers are right-aligned. Amounts are labelled "(Rs)" in the headers
 * because the standard PDF fonts have no rupee sign.
 */
public final class PdfRenderer {

    private PdfRenderer() {
    }

    public static byte[] render(ReportTable table) {
        List<ReportColumn> columns = table.columns();
        boolean wide = columns.size() > 6;
        Document document = new Document(wide ? PageSize.A4.rotate() : PageSize.A4, 24, 24, 28, 28);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            document.add(new Paragraph(table.title(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14)));
            if (table.subtitle() != null) {
                Paragraph subtitle = new Paragraph(table.subtitle(), FontFactory.getFont(FontFactory.HELVETICA, 9));
                subtitle.setSpacingAfter(8);
                document.add(subtitle);
            }
            if (table.rows().isEmpty()) {
                document.add(new Paragraph("No data for this period.", FontFactory.getFont(FontFactory.HELVETICA, 10)));
            } else {
                document.add(buildTable(table, columns.size() > 8 ? 7 : 8));
            }
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not build the PDF", e);
        } finally {
            document.close();
        }
        return out.toByteArray();
    }

    private static PdfPTable buildTable(ReportTable table, int fontSize) {
        List<ReportColumn> columns = table.columns();
        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, fontSize);
        Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, fontSize);
        Font totalFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, fontSize);

        PdfPTable pdf = new PdfPTable(columns.size());
        pdf.setWidthPercentage(100);
        pdf.setHeaderRows(1);
        for (ReportColumn column : columns) {
            PdfPCell cell = new PdfPCell(new Phrase(column.header(), headerFont));
            cell.setBackgroundColor(Color.LIGHT_GRAY);
            cell.setPadding(3);
            cell.setHorizontalAlignment(alignment(column.type()));
            pdf.addCell(cell);
        }
        for (List<Object> row : table.rows()) {
            addRow(pdf, columns, row, bodyFont);
        }
        if (table.hasTotals()) {
            addRow(pdf, columns, table.totals(), totalFont);
        }
        return pdf;
    }

    private static void addRow(PdfPTable pdf, List<ReportColumn> columns, List<Object> row, Font font) {
        for (int i = 0; i < columns.size(); i++) {
            PdfPCell cell = new PdfPCell(new Phrase(CellText.display(columns.get(i).type(), row.get(i)), font));
            cell.setPadding(3);
            cell.setHorizontalAlignment(alignment(columns.get(i).type()));
            pdf.addCell(cell);
        }
    }

    private static int alignment(ColumnType type) {
        return type == ColumnType.TEXT || type == ColumnType.DATE ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT;
    }
}
