package com.orderaudit.web;

import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.InvoiceStatus;
import com.orderaudit.report.ReportPeriod;
import com.orderaudit.service.CreditNoteService;
import com.orderaudit.service.InvoiceService;
import com.orderaudit.service.ReceivablesService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Invoices: list, one invoice with its lines, and the credit note (goods returned) action. */
@Controller
@RequestMapping("/admin/invoices")
public class AdminInvoiceController {

    // Seller details printed on the invoice PDF; set app.seller.* in application.yml (or env vars) per deployment.
    @org.springframework.beans.factory.annotation.Value("${app.seller.name:Murukku Foods (demo)}")
    private String sellerName;
    @org.springframework.beans.factory.annotation.Value("${app.seller.gstin:33AAAAA0000A1Z5}")
    private String sellerGstin;
    @org.springframework.beans.factory.annotation.Value("${app.seller.state:Tamil Nadu (33)}")
    private String sellerState;

    private final InvoiceService invoices;
    private final CreditNoteService creditNotes;
    private final ReceivablesService receivables;

    public AdminInvoiceController(InvoiceService invoices, CreditNoteService creditNotes,
                                  ReceivablesService receivables) {
        this.invoices = invoices;
        this.creditNotes = creditNotes;
        this.receivables = receivables;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("invoices", invoices.recent());
        return "admin/invoices";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        Invoice invoice;
        try {
            invoice = invoices.get(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        model.addAttribute("invoice", invoice);
        model.addAttribute("lines", invoices.linesOf(id));
        model.addAttribute("creditNote", creditNotes.creditNoteOf(id).orElse(null));
        model.addAttribute("whatsAppUrl", InvoiceShare.whatsAppUrl(invoice));
        model.addAttribute("mailtoUrl", InvoiceShare.mailtoUrl(invoice));
        if (invoice.getStatus() == InvoiceStatus.ISSUED) {
            long outstanding = receivables.outstandingOfInvoice(id);
            model.addAttribute("outstanding", outstanding);
            model.addAttribute("hasPayments", outstanding < invoice.getTotalPaise());
        }
        model.addAttribute("today", ReportPeriod.today());
        return "admin/invoice";
    }

    @GetMapping("/{id}/pdf")
    public org.springframework.http.ResponseEntity<byte[]> pdf(@PathVariable long id) {
        Invoice invoice;
        try {
            invoice = invoices.get(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        byte[] bytes = com.orderaudit.report.InvoicePdfRenderer.render(invoice, invoices.linesOf(id),
                new com.orderaudit.report.InvoicePdfRenderer.Seller(sellerName, sellerGstin, sellerState));
        String fileName = invoice.getInvoiceNumber().replace('/', '-') + ".pdf";
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment().filename(fileName).build().toString())
                .body(bytes);
    }

    @PostMapping("/{id}/credit-note")
    public String issueCreditNote(@PathVariable long id,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate creditDate,
                                  @RequestParam String reason, Authentication auth, RedirectAttributes redirect) {
        try {
            var note = creditNotes.issue(id, creditDate, reason, auth.getName());
            redirect.addFlashAttribute("message", "Credit note " + note.getCreditNoteNumber() + " issued. The packets are back in stock.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/invoices/" + id;
    }
}
