package com.orderaudit.service;

import com.orderaudit.domain.AgeingBucket;
import com.orderaudit.domain.AgeingRow;
import com.orderaudit.domain.AllocationRequest;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.InvoiceStatus;
import com.orderaudit.domain.OpenInvoice;
import com.orderaudit.domain.Payment;
import com.orderaudit.domain.PaymentAllocation;
import com.orderaudit.domain.PaymentMethod;
import com.orderaudit.repository.CustomerRepository;
import com.orderaudit.repository.InvoiceRepository;
import com.orderaudit.repository.PaymentAllocationRepository;
import com.orderaudit.repository.PaymentRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money coming in: payments allocated to invoices, what each customer still owes, and ageing.
 * Outstanding of an invoice = invoice total - payments allocated to it. Invoice rows are locked while
 * allocating, so two payments can never both spend the same outstanding rupee.
 */
@Service
public class ReceivablesService {

    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final AuditService audit;

    public ReceivablesService(PaymentRepository payments, PaymentAllocationRepository allocations,
                              InvoiceRepository invoices, CustomerRepository customers, AuditService audit) {
        this.payments = payments;
        this.allocations = allocations;
        this.invoices = invoices;
        this.customers = customers;
        this.audit = audit;
    }

    /** Record a payment split over specific invoices of this customer. */
    @Transactional
    public Payment recordPayment(long customerId, LocalDate paymentDate, PaymentMethod method, String reference,
                                 String receivedBy, List<AllocationRequest> requested) {
        if (paymentDate == null || method == null) {
            throw new IllegalArgumentException("Payment date and method are required");
        }
        if (receivedBy == null || receivedBy.isBlank()) {
            throw new IllegalArgumentException("receivedBy is required");
        }
        if (reference != null && reference.length() > 60) {
            throw new IllegalArgumentException("Reference must be at most 60 characters");
        }
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("A payment must be allocated to at least one invoice");
        }
        if (!customers.existsById(customerId)) {
            throw new IllegalArgumentException("Customer " + customerId + " does not exist");
        }
        Set<Long> seen = new HashSet<>();
        for (AllocationRequest a : requested) {
            if (a.amountPaise() <= 0) {
                throw new IllegalArgumentException("Allocated amounts must be greater than zero");
            }
            if (!seen.add(a.invoiceId())) {
                throw new IllegalArgumentException("Invoice " + a.invoiceId() + " appears twice in the allocation");
            }
        }
        List<AllocationRequest> ordered = requested.stream()
                .sorted(Comparator.comparingLong(AllocationRequest::invoiceId)).toList();   // lock in a fixed order
        long total = 0;
        for (AllocationRequest a : ordered) {
            Invoice invoice = invoices.findForUpdate(a.invoiceId()).orElseThrow(
                    () -> new IllegalArgumentException("Invoice " + a.invoiceId() + " does not exist"));
            if (invoice.getCustomerId() != customerId) {
                throw new IllegalArgumentException("Invoice " + invoice.getInvoiceNumber() + " belongs to another customer");
            }
            if (invoice.getStatus() != InvoiceStatus.ISSUED) {
                throw new IllegalArgumentException("Invoice " + invoice.getInvoiceNumber() + " is " + invoice.getStatus());
            }
            long outstanding = outstandingOf(invoice);
            if (a.amountPaise() > outstanding) {
                throw new IllegalArgumentException("Invoice " + invoice.getInvoiceNumber() + " has only "
                        + outstanding + " paise outstanding, cannot allocate " + a.amountPaise());
            }
            total = Math.addExact(total, a.amountPaise());
        }
        Payment payment = payments.save(new Payment(customerId, paymentDate, total, method,
                reference == null || reference.isBlank() ? null : reference.trim(), receivedBy.trim()));
        for (AllocationRequest a : ordered) {
            allocations.save(new PaymentAllocation(payment.getId(), a.invoiceId(), a.amountPaise()));
        }
        audit.record(receivedBy, "PAYMENT_RECORDED", "PAYMENT", payment.getId(),
                total + " paise by " + method + " for customer " + customerId);
        return payment;
    }

    /** Record a payment and spread it over the customer's oldest unpaid invoices first. */
    @Transactional
    public Payment recordPaymentOldestFirst(long customerId, LocalDate paymentDate, PaymentMethod method,
                                            String reference, String receivedBy, long amountPaise) {
        List<AllocationRequest> plan = PaymentAllocator.oldestFirst(amountPaise, openInvoices(customerId));
        return recordPayment(customerId, paymentDate, method, reference, receivedBy, plan);
    }

    @Transactional(readOnly = true)
    public long outstandingOfInvoice(long invoiceId) {
        Invoice invoice = invoices.findById(invoiceId).orElseThrow(
                () -> new IllegalArgumentException("Invoice " + invoiceId + " does not exist"));
        return outstandingOf(invoice);
    }

    private long outstandingOf(Invoice invoice) {
        Long paid = allocations.paidAgainst(invoice.getId());
        return invoice.getTotalPaise() - (paid == null ? 0 : paid);
    }

    /** The customer's issued invoices with paid and outstanding amounts, oldest first (including fully paid ones). */
    @Transactional(readOnly = true)
    public List<OpenInvoice> invoicesOf(long customerId) {
        return invoices.findByCustomerIdAndStatusOrderByInvoiceDateAscIdAsc(customerId, InvoiceStatus.ISSUED).stream()
                .map(i -> {
                    Long paid = allocations.paidAgainst(i.getId());
                    long paidPaise = paid == null ? 0 : paid;
                    return new OpenInvoice(i.getId(), i.getInvoiceNumber(), i.getInvoiceDate(),
                            i.getTotalPaise(), paidPaise, i.getTotalPaise() - paidPaise);
                }).toList();
    }

    /** Only the invoices that still have something outstanding. */
    @Transactional(readOnly = true)
    public List<OpenInvoice> openInvoices(long customerId) {
        return invoicesOf(customerId).stream().filter(i -> i.outstandingPaise() > 0).toList();
    }

    @Transactional(readOnly = true)
    public long customerOutstanding(long customerId) {
        return invoicesOf(customerId).stream().mapToLong(OpenInvoice::outstandingPaise).sum();
    }

    /** Unpaid amounts per customer in age buckets as of a date (age = days since the invoice date). */
    @Transactional(readOnly = true)
    public List<AgeingRow> ageing(LocalDate asOf) {
        Map<Long, Long> paid = new HashMap<>();
        for (Object[] row : allocations.paidPerInvoice()) {
            paid.put((Long) row[0], (Long) row[1]);
        }
        Map<Long, long[]> perCustomer = new LinkedHashMap<>();   // customerId -> {b0, b1, b2, b3}
        Map<Long, String> names = new HashMap<>();
        for (Invoice invoice : invoices.findByStatusOrderByInvoiceDateAscIdAsc(InvoiceStatus.ISSUED)) {
            long outstanding = invoice.getTotalPaise() - paid.getOrDefault(invoice.getId(), 0L);
            if (outstanding <= 0 || invoice.getInvoiceDate().isAfter(asOf)) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(invoice.getInvoiceDate(), asOf);
            long[] buckets = perCustomer.computeIfAbsent(invoice.getCustomerId(), k -> new long[4]);
            buckets[AgeingBucket.of(days).ordinal()] += outstanding;
            names.put(invoice.getCustomerId(), invoice.getCustomerName());
        }
        return perCustomer.entrySet().stream()
                .map(e -> new AgeingRow(e.getKey(), names.get(e.getKey()), e.getValue()[0], e.getValue()[1],
                        e.getValue()[2], e.getValue()[3],
                        e.getValue()[0] + e.getValue()[1] + e.getValue()[2] + e.getValue()[3]))
                .sorted(Comparator.comparing(AgeingRow::customerName))
                .toList();
    }
}
