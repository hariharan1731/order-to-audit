package com.orderaudit.service;

import com.orderaudit.domain.Customer;
import com.orderaudit.domain.GstBreakdown;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.InvoiceLine;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.Money;
import com.orderaudit.domain.OrderLine;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.repository.CustomerRepository;
import com.orderaudit.repository.InvoiceLineRepository;
import com.orderaudit.repository.InvoiceRepository;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.repository.OrderLineRepository;
import com.orderaudit.repository.SalesOrderRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an APPROVED order into a GST tax invoice. Rules: place of supply by state codes (same state:
 * CGST + SGST, otherwise IGST); tax per line, rounded half-up; invoice totals are sums of the lines;
 * everything copied onto the invoice as a snapshot.
 */
@Service
public class InvoiceService {

    private final SalesOrderRepository orders;
    private final OrderLineRepository orderLines;
    private final CustomerRepository customers;
    private final ItemRepository items;
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository invoiceLines;
    private final InvoiceNumberService numbers;
    private final SettingService settings;
    private final AuditService audit;

    public InvoiceService(SalesOrderRepository orders, OrderLineRepository orderLines, CustomerRepository customers,
                          ItemRepository items, InvoiceRepository invoices, InvoiceLineRepository invoiceLines,
                          InvoiceNumberService numbers, SettingService settings, AuditService audit) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.customers = customers;
        this.items = items;
        this.invoices = invoices;
        this.invoiceLines = invoiceLines;
        this.numbers = numbers;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional
    public Invoice raise(long orderId, LocalDate invoiceDate, String createdBy) {
        if (invoiceDate == null) {
            throw new IllegalArgumentException("Invoice date is required");
        }
        if (createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("createdBy is required");
        }
        SalesOrder order = orders.findForUpdate(orderId).orElseThrow(   // locked: one invoice per order
                () -> new IllegalArgumentException("Order " + orderId + " does not exist"));
        order.markInvoiced();   // throws unless the order is APPROVED, so it can never be invoiced twice

        Customer customer = customers.findById(order.getCustomerId()).orElseThrow(
                () -> new IllegalStateException("Customer " + order.getCustomerId() + " is missing"));
        String factoryState = settings.factoryStateCode();
        boolean intra = GstCalculator.isIntraState(factoryState, customer.getStateCode());

        List<OrderLine> lines = orderLines.findByOrderIdOrderByItemIdAsc(orderId);
        List<Item> lineItems = new ArrayList<>();
        List<GstBreakdown> lineTax = new ArrayList<>();
        GstBreakdown total = GstBreakdown.ZERO;
        for (OrderLine line : lines) {
            Item item = items.findById(line.getItemId()).orElseThrow(
                    () -> new IllegalStateException("Item " + line.getItemId() + " is missing"));
            Money taxable = Money.ofPaise(Math.multiplyExact(line.getQuantity(), line.getUnitPricePaise()));
            GstBreakdown tax = GstCalculator.calculate(taxable, item.getGstRatePercent(), intra);
            lineItems.add(item);
            lineTax.add(tax);
            total = total.plus(tax);
        }

        String number = numbers.next(invoiceDate);
        Invoice invoice = invoices.save(new Invoice(number, FinancialYear.label(invoiceDate), orderId, customer,
                factoryState, intra, invoiceDate, total, createdBy.trim()));
        for (int i = 0; i < lines.size(); i++) {
            OrderLine line = lines.get(i);
            invoiceLines.save(new InvoiceLine(invoice.getId(), lineItems.get(i), line.getQuantity(),
                    line.getUnitPricePaise(), lineTax.get(i)));
        }
        audit.record(createdBy, "INVOICE_RAISED", "INVOICE", invoice.getId(),
                number + " total " + total.invoiceTotal().paise() + " paise");
        return invoice;
    }

    @Transactional(readOnly = true)
    public Invoice get(long invoiceId) {
        return invoices.findById(invoiceId).orElseThrow(
                () -> new IllegalArgumentException("Invoice " + invoiceId + " does not exist"));
    }

    /** The 100 newest invoices, newest first. */
    @Transactional(readOnly = true)
    public List<Invoice> recent() {
        return invoices.findTop100ByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public Invoice forOrder(long orderId) {
        return invoices.findByOrderId(orderId).orElseThrow(
                () -> new IllegalArgumentException("Order " + orderId + " has no invoice"));
    }

    @Transactional(readOnly = true)
    public List<InvoiceLine> linesOf(long invoiceId) {
        return invoiceLines.findByInvoiceIdOrderByIdAsc(invoiceId);
    }
}
