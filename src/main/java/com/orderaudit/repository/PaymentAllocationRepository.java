package com.orderaudit.repository;

import com.orderaudit.domain.PaymentAllocation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {

    List<PaymentAllocation> findByPaymentId(Long paymentId);

    /** Total paid against one invoice (null if nothing yet). Reversed payments do not count. */
    @Query("select sum(a.amountPaise) from PaymentAllocation a where a.invoiceId = :invoiceId "
            + "and not exists (select 1 from PaymentReversal r where r.paymentId = a.paymentId)")
    Long paidAgainst(@Param("invoiceId") Long invoiceId);

    /** Rows of {invoiceId, totalPaid} for every invoice that has any live (not reversed) payment. */
    @Query("select a.invoiceId, sum(a.amountPaise) from PaymentAllocation a "
            + "where not exists (select 1 from PaymentReversal r where r.paymentId = a.paymentId) "
            + "group by a.invoiceId")
    List<Object[]> paidPerInvoice();

    /**
     * Like paidPerInvoice, but as the books stood on a past date: only payments received by then count, and a
     * reversal only cancels a payment from the day it was made.
     */
    @Query("select a.invoiceId, sum(a.amountPaise) from PaymentAllocation a, Payment p "
            + "where p.id = a.paymentId and p.paymentDate <= :asOf "
            + "and not exists (select 1 from PaymentReversal r where r.paymentId = a.paymentId "
            + "and r.reversalDate <= :asOf) "
            + "group by a.invoiceId")
    List<Object[]> paidPerInvoiceAsOf(@Param("asOf") java.time.LocalDate asOf);
}
