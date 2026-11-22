package com.orderaudit.service;

import com.orderaudit.domain.CreditNote;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.InvoiceLine;
import com.orderaudit.domain.InvoiceStatus;
import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.OrderStatus;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.SourceType;
import com.orderaudit.repository.CreditNoteRepository;
import com.orderaudit.repository.InvoiceLineRepository;
import com.orderaudit.repository.InvoiceRepository;
import com.orderaudit.repository.PaymentAllocationRepository;
import com.orderaudit.repository.SalesOrderRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels a dispatched invoice because the goods came back.
 * In ONE transaction: the credit note is numbered and saved, the invoice becomes CANCELLED, the packets go
 * back into stock as new RETURN rows (nothing in the ledger is edited), and an audit row is written.
 * Rule: an invoice that already has payments against it cannot be credited yet (refund the payment first).
 */
@Service
public class CreditNoteService {

    private final InvoiceRepository invoices;
    private final InvoiceLineRepository invoiceLines;
    private final SalesOrderRepository orders;
    private final PaymentAllocationRepository allocations;
    private final CreditNoteRepository creditNotes;
    private final CreditNoteNumberService numbers;
    private final StockService stock;
    private final AuditService audit;

    public CreditNoteService(InvoiceRepository invoices, InvoiceLineRepository invoiceLines,
                             SalesOrderRepository orders, PaymentAllocationRepository allocations,
                             CreditNoteRepository creditNotes, CreditNoteNumberService numbers,
                             StockService stock, AuditService audit) {
        this.invoices = invoices;
        this.invoiceLines = invoiceLines;
        this.orders = orders;
        this.allocations = allocations;
        this.creditNotes = creditNotes;
        this.numbers = numbers;
        this.stock = stock;
        this.audit = audit;
    }

    @Transactional
    public CreditNote issue(long invoiceId, LocalDate creditDate, String reason, String createdBy) {
        if (creditDate == null) {
            throw new IllegalArgumentException("Credit note date is required");
        }
        if (reason == null || reason.isBlank() || reason.length() > 300) {
            throw new IllegalArgumentException("A reason is required (at most 300 characters)");
        }
        if (createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("createdBy is required");
        }
        Invoice invoice = invoices.findForUpdate(invoiceId).orElseThrow(
                () -> new IllegalArgumentException("Invoice " + invoiceId + " does not exist"));
        if (invoice.getStatus() != InvoiceStatus.ISSUED) {
            throw new IllegalStateException("Invoice " + invoice.getInvoiceNumber() + " is already " + invoice.getStatus());
        }
        if (creditDate.isBefore(invoice.getInvoiceDate())) {
            throw new IllegalArgumentException("Credit note date cannot be before the invoice date");
        }
        SalesOrder order = orders.findById(invoice.getOrderId()).orElseThrow(
                () -> new IllegalStateException("Order " + invoice.getOrderId() + " is missing"));
        if (order.getStatus() != OrderStatus.DISPATCHED) {
            throw new IllegalStateException("Only dispatched goods can be returned: the order is " + order.getStatus());
        }
        Long paid = allocations.paidAgainst(invoiceId);
        if (paid != null && paid > 0) {
            throw new IllegalStateException("Invoice " + invoice.getInvoiceNumber() + " has " + paid
                    + " paise paid against it; refund the payment (reverse it under Payments) before issuing a credit note");
        }

        String number = numbers.next(creditDate);
        CreditNote note = creditNotes.save(new CreditNote(number, FinancialYear.label(creditDate), invoice,
                creditDate, reason.trim(), createdBy.trim()));
        invoice.cancel();
        invoices.saveAndFlush(invoice);   // write the status change now, so SQL reports in this transaction see it

        List<InvoiceLine> lines = invoiceLines.findByInvoiceIdOrderByIdAsc(invoiceId).stream()
                .sorted(Comparator.comparingLong(InvoiceLine::getItemId)).toList();   // fixed order: no deadlocks
        for (InvoiceLine line : lines) {
            stock.post(line.getItemId(), MovementType.RETURN, line.getQuantity(), null,
                    SourceType.SALES_RETURN, note.getId(), createdBy);
        }
        audit.record(createdBy, "CREDIT_NOTE_ISSUED", "INVOICE", invoiceId,
                number + " for " + invoice.getInvoiceNumber() + ": " + reason.trim());
        return note;
    }

    @Transactional(readOnly = true)
    public java.util.Optional<CreditNote> creditNoteOf(long invoiceId) {
        return creditNotes.findByInvoiceId(invoiceId);
    }

    @Transactional(readOnly = true)
    public CreditNote forInvoice(long invoiceId) {
        return creditNotes.findByInvoiceId(invoiceId).orElseThrow(
                () -> new IllegalArgumentException("Invoice " + invoiceId + " has no credit note"));
    }

    @Transactional(readOnly = true)
    public List<CreditNote> all() {
        return creditNotes.findAllByOrderByIdDesc();
    }
}
