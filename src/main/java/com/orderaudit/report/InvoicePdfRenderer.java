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
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.InvoiceLine;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * Draws one tax invoice as an A4 PDF. Amounts come straight from the stored invoice (integer paise),
 * so the printed numbers can never differ from the ones in the database.
 */
public final class InvoicePdfRenderer {

    // Fictional placeholder supplier details (the real GSTIN would be configured per deployment).
    static final String SELLER_NAME = "Murukku Foods (demo)";
    static final String SELLER_GSTIN = "33AAAAA0000A1Z5";
    static final String SELLER_STATE = "Tamil Nadu (33)";

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
    private static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
    private static final Font NORMAL = FontFactory.getFont(FontFactory.HELVETICA, 9);

    private InvoicePdfRenderer() {
    }

    public static byte[] render(Invoice invoice, List<InvoiceLine> lines) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            document.add(new Paragraph("TAX INVOICE" + (invoice.getStatus().name().equals("CANCELLED") ? " (CANCELLED)" : ""), TITLE));
            document.add(new Paragraph(invoice.getInvoiceNumber() + "   |   Date: " + invoice.getInvoiceDate(), BOLD));
            document.add(new Paragraph(" ", NORMAL));

            PdfPTable parties = new PdfPTable(2);
            parties.setWidthPercentage(100);
            parties.addCell(partyCell("Supplier", SELLER_NAME, "GSTIN: " + SELLER_GSTIN, "State: " + SELLER_STATE));
            parties.addCell(partyCell("Bill to", invoice.getCustomerName(),
                    "GSTIN: " + (invoice.getCustomerGstin() == null ? "unregistered" : invoice.getCustomerGstin()),
                    "State: " + invoice.getCustomerState()));
            document.add(parties);
            document.add(new Paragraph(" ", NORMAL));

            // Relative column widths as whole numbers (same proportions as 4 : 1.6 : 1.2 : 1.6 : 1.6 : 1.2 : 1.6).
            PdfPTable table = new PdfPTable(7);
            table.setWidths(new int[] {40, 16, 12, 16, 16, 12, 16});
            table.setWidthPercentage(100);
            for (String h : new String[] {"Item", "HSN", "Qty", "Rate (Rs)", "Taxable (Rs)", "GST %", "Tax (Rs)"}) {
                header(table, h);
            }
            for (InvoiceLine l : lines) {
                body(table, l.getItemName(), Element.ALIGN_LEFT);
                body(table, l.getHsnCode() == null ? "-" : l.getHsnCode(), Element.ALIGN_LEFT);
                body(table, String.valueOf(l.getQuantity()), Element.ALIGN_RIGHT);
                body(table, rupees(l.getUnitPricePaise()), Element.ALIGN_RIGHT);
                body(table, rupees(l.getTaxablePaise()), Element.ALIGN_RIGHT);
                body(table, l.getGstRatePercent().stripTrailingZeros().toPlainString(), Element.ALIGN_RIGHT);
                body(table, rupees(l.getCgstPaise() + l.getSgstPaise() + l.getIgstPaise()), Element.ALIGN_RIGHT);
            }
            document.add(table);
            document.add(new Paragraph(" ", NORMAL));

            PdfPTable totals = new PdfPTable(2);
            totals.setWidths(new int[] {30, 15});
            totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totals.setWidthPercentage(45);
            total(totals, "Taxable value", invoice.getTaxablePaise(), false);
            if (invoice.isIntraState()) {
                total(totals, "CGST", invoice.getCgstPaise(), false);
                total(totals, "SGST", invoice.getSgstPaise(), false);
            } else {
                total(totals, "IGST", invoice.getIgstPaise(), false);
            }
            total(totals, "Invoice total", invoice.getTotalPaise(), true);
            document.add(totals);
            document.add(new Paragraph(" ", NORMAL));
            document.add(new Paragraph("This is a computer-generated invoice.", NORMAL));
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not draw the invoice PDF", e);
        }
        return out.toByteArray();
    }

    /** 787500 paise -> "7,875.00" (exact, no floating point). */
    static String rupees(long paise) {
        BigDecimal value = BigDecimal.valueOf(paise, 2);
        return String.format(java.util.Locale.ENGLISH, "%,.2f", value);
    }

    private static PdfPCell partyCell(String label, String... rows) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(6);
        cell.addElement(new Paragraph(label, BOLD));
        for (String r : rows) {
            cell.addElement(new Paragraph(r, NORMAL));
        }
        return cell;
    }

    private static void header(PdfPTable t, String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, BOLD));
        c.setBackgroundColor(new java.awt.Color(230, 230, 230));
        c.setPadding(4);
        t.addCell(c);
    }

    private static void body(PdfPTable t, String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, NORMAL));
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        t.addCell(c);
    }

    private static void total(PdfPTable t, String label, long paise, boolean strong) {
        Font f = strong ? BOLD : NORMAL;
        PdfPCell a = new PdfPCell(new Phrase(label, f));
        PdfPCell b = new PdfPCell(new Phrase("Rs " + rupees(paise), f));
        b.setHorizontalAlignment(Element.ALIGN_RIGHT);
        a.setPadding(4);
        b.setPadding(4);
        t.addCell(a);
        t.addCell(b);
    }
}
