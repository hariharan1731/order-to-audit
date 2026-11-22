package com.orderaudit.web;

import com.orderaudit.domain.Invoice;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Builds the WhatsApp and e-mail links used to send an invoice to the customer.
 *
 * The customer master has no phone number or e-mail, so the links carry only the message: WhatsApp asks which
 * chat to send it to, and the mail app opens a new message without a recipient. The PDF itself stays behind the
 * login, so the admin downloads it and attaches it; the message says so.
 */
public final class InvoiceShare {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Formats FORMATS = new Formats();

    private InvoiceShare() {
    }

    public static String subject(Invoice invoice) {
        return "Tax invoice " + invoice.getInvoiceNumber();
    }

    /** Plain-text summary, e.g. "Tax invoice INV/2026-27/0001 dated 21 Nov 2026 for Retailer A ...". */
    public static String message(Invoice invoice) {
        long tax = invoice.getCgstPaise() + invoice.getSgstPaise() + invoice.getIgstPaise();
        String taxName = invoice.getIgstPaise() > 0 ? "IGST" : "CGST + SGST";
        return subject(invoice) + " dated " + DATE.format(invoice.getInvoiceDate()) + " for "
                + invoice.getCustomerName() + ". Taxable value " + FORMATS.rupees(invoice.getTaxablePaise())
                + ", " + taxName + " " + FORMATS.rupees(tax) + ", total " + FORMATS.rupees(invoice.getTotalPaise())
                + ". The invoice PDF is attached.";
    }

    public static String whatsAppUrl(Invoice invoice) {
        return "https://wa.me/?text=" + encode(message(invoice));
    }

    public static String mailtoUrl(Invoice invoice) {
        return "mailto:?subject=" + encode(subject(invoice)) + "&body=" + encode(message(invoice));
    }

    /** Percent-encoding for a URL query value: spaces become %20, not "+", which mail apps would show literally. */
    static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
