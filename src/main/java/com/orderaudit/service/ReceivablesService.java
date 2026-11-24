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
import com.orderaudit.domain.PaymentReversal;
import com.orderaudit.domain.PaymentRow;
import com.orderaudit.repository.CustomerRepository;
import com.orderaudit.repository.InvoiceRepository;
import com.orderaudit.repository.PaymentAllocationRepository;
import com.orderaudit.repository.PaymentRepository;
import com.orderaudit.repository.PaymentReversalRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
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
    private final PaymentReversalRepository reversals;
    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final AuditService audit;

    public ReceivablesService(PaymentRepository payments, PaymentAllocationRepository allocations,
                              PaymentReversalRepository reversals, InvoiceRepository invoices,
                              CustomerRepository customers, AuditService audit) {
        this.payments = payments;
        this.allocations = allocations;
        this.reversals = reversals;
        this.invoices = invoices;
        this.customers = customers;
        this.audit = audit;
    }

    /** Record a payment split over specific invoices of this customer. */
    @Transactional
    public Payment recordPayment(long customerId, LocalDate paymentDate, PaymentMethod method, String reference,
                                 String receivedBy, List<AllocationRequest> requested) {
        return record(customerId, paymentDate, method, reference, receivedBy, requested, null);
    }

    private Payment record(long customerId, LocalDate paymentDate, PaymentMethod method, String reference,
                           String receivedBy, List<AllocationRequest> requested, String requestId) {
        if (paymentDate == null || method == null) {
            throw new IllegalArgumentException("Payment date and method are required");
        }
        DateRules.notInFuture(paymentDate, "Payment date");
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
        List<Invoice> locked = new ArrayList<>();
        for (AllocationRequest a : ordered) {
            locked.add(invoices.findForUpdate(a.invoiceId()).orElseThrow(
                    () -> new IllegalArgumentException("Invoice " + a.invoiceId() + " does not exist")));
        }
        if (requestId != null) {
            // Checked again while holding the invoice locks: an identical request that arrived at the same moment
            // has committed by now, so it is found here instead of being recorded a second time.
            Optional<Payment> same = payments.findByClientRequestId(requestId);
            if (same.isPresent()) {
                return same.get();
            }
        }
        long total = 0;
        for (int i = 0; i < ordered.size(); i++) {
            AllocationRequest a = ordered.get(i);
            Invoice invoice = locked.get(i);
            if (invoice.getCustomerId() != customerId) {
                throw new IllegalArgumentException("Invoice " + invoice.getInvoiceNumber() + " belongs to another customer");
            }
            if (invoice.getStatus() != InvoiceStatus.ISSUED) {
                throw new IllegalArgumentException("Invoice " + invoice.getInvoiceNumber() + " is " + invoice.getStatus());
            }
            if (paymentDate.isBefore(invoice.getInvoiceDate())) {
                throw new IllegalArgumentException("Payment date cannot be before invoice "
                        + invoice.getInvoiceNumber() + " was raised (" + invoice.getInvoiceDate() + ")");
            }
            long outstanding = outstandingOf(invoice);
            if (a.amountPaise() > outstanding) {
                throw new IllegalArgumentException("Invoice " + invoice.getInvoiceNumber() + " has only "
                        + outstanding + " paise outstanding, cannot allocate " + a.amountPaise());
            }
            total = Math.addExact(total, a.amountPaise());
        }
        Payment payment = payments.save(new Payment(customerId, paymentDate, total, method,
                reference == null || reference.isBlank() ? null : reference.trim(), receivedBy.trim(), requestId));
        for (AllocationRequest a : ordered) {
            allocations.save(new PaymentAllocation(payment.getId(), a.invoiceId(), a.amountPaise()));
        }
        audit.record(receivedBy, "PAYMENT_RECORDED", "PAYMENT", payment.getId(),
                total + " paise by " + method + " for customer " + customerId);
        return payment;
    }

    /**
     * Cancel a payment that should never have counted (bounced cheque, wrong customer, keyed twice).
     * Nothing is edited or deleted: a payment_reversal row is added, so the payment stays in history and the
     * invoices it paid simply owe that money again. The invoices are locked first (in a fixed order, like
     * recordPayment), so a reversal cannot interleave with a new payment or a credit note on the same invoice.
     */
    @Transactional
    public PaymentReversal reversePayment(long paymentId, LocalDate reversalDate, String reason, String reversedBy) {
        if (reversalDate == null) {
            throw new IllegalArgumentException("Reversal date is required");
        }
        DateRules.notInFuture(reversalDate, "Reversal date");
        if (reason == null || reason.isBlank() || reason.trim().length() > 200) {
            throw new IllegalArgumentException("A reason is required (at most 200 characters)");
        }
        if (reversedBy == null || reversedBy.isBlank()) {
            throw new IllegalArgumentException("reversedBy is required");
        }
        Payment payment = payments.findById(paymentId).orElseThrow(
                () -> new IllegalArgumentException("Payment " + paymentId + " does not exist"));
        if (reversalDate.isBefore(payment.getPaymentDate())) {
            throw new IllegalArgumentException("Reversal date cannot be before the payment date");
        }
        List<PaymentAllocation> spread = allocations.findByPaymentId(paymentId).stream()
                .sorted(Comparator.comparingLong(PaymentAllocation::getInvoiceId)).toList();
        for (PaymentAllocation a : spread) {
            invoices.findForUpdate(a.getInvoiceId());   // lock only; the checks below run after we hold the locks
        }
        if (reversals.existsByPaymentId(paymentId)) {
            throw new IllegalStateException("Payment " + paymentId + " has already been reversed");
        }
        PaymentReversal reversal = reversals.saveAndFlush(
                new PaymentReversal(paymentId, reversalDate, reason.trim(), reversedBy.trim()));
        audit.record(reversedBy, "PAYMENT_REVERSED", "PAYMENT", paymentId,
                payment.getAmountPaise() + " paise by " + payment.getMethod() + " for customer "
                        + payment.getCustomerId() + " reversed: " + reason.trim());
        return reversal;
    }

    /** Every payment of a customer, oldest first, including reversed ones (marked as such). */
    @Transactional(readOnly = true)
    public List<PaymentRow> paymentsOf(long customerId) {
        List<Payment> all = payments.findByCustomerIdOrderByIdAsc(customerId);
        Map<Long, PaymentReversal> reversed = new HashMap<>();
        if (!all.isEmpty()) {
            for (PaymentReversal r : reversals.findByPaymentIdIn(all.stream().map(Payment::getId).toList())) {
                reversed.put(r.getPaymentId(), r);
            }
        }
        List<PaymentRow> rows = new ArrayList<>();
        for (Payment p : all) {
            List<String> numbers = new ArrayList<>();
            for (PaymentAllocation a : allocations.findByPaymentId(p.getId())) {
                invoices.findById(a.getInvoiceId()).ifPresent(i -> numbers.add(i.getInvoiceNumber()));
            }
            numbers.sort(Comparator.naturalOrder());
            PaymentReversal r = reversed.get(p.getId());
            rows.add(new PaymentRow(p.getId(), p.getPaymentDate(), p.getAmountPaise(), p.getMethod(),
                    p.getReference(), p.getReceivedBy(), String.join(", ", numbers), r != null,
                    r == null ? null : r.getReversalDate(), r == null ? null : r.getReason(),
                    r == null ? null : r.getReversedBy()));
        }
        return rows;
    }

    /** Record a payment and spread it over the customer's oldest unpaid invoices first. */
    @Transactional
    public Payment recordPaymentOldestFirst(long customerId, LocalDate paymentDate, PaymentMethod method,
                                            String reference, String receivedBy, long amountPaise) {
        return recordPaymentOldestFirst(customerId, paymentDate, method, reference, receivedBy, amountPaise, null);
    }

    /**
     * Same as above, but safe to send twice: clientRequestId comes from the payment form, and a second request with
     * the same id returns the payment already recorded instead of recording the money again (double-click, re-sent
     * form, browser back + resubmit).
     */
    @Transactional
    public Payment recordPaymentOldestFirst(long customerId, LocalDate paymentDate, PaymentMethod method,
                                            String reference, String receivedBy, long amountPaise,
                                            String clientRequestId) {
        String requestId = clientRequestId == null || clientRequestId.isBlank() ? null : clientRequestId.trim();
        if (requestId != null) {
            if (requestId.length() > 60) {
                throw new IllegalArgumentException("Client request id must be at most 60 characters");
            }
            Optional<Payment> existing = payments.findByClientRequestId(requestId);
            if (existing.isPresent()) {
                if (existing.get().getCustomerId() != customerId) {
                    throw new IllegalArgumentException("This payment request was already used for another customer");
                }
                return existing.get();
            }
        }
        List<AllocationRequest> plan = PaymentAllocator.oldestFirst(amountPaise, openInvoices(customerId));
        return record(customerId, paymentDate, method, reference, receivedBy, plan, requestId);
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
