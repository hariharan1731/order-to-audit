package com.orderaudit.repository;

import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.InvoiceStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    Optional<Invoice> findByOrderId(Long orderId);
    List<Invoice> findTop100ByOrderByIdDesc();
    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);
    List<Invoice> findByStatusOrderByInvoiceDateAscIdAsc(InvoiceStatus status);
    List<Invoice> findByCustomerIdAndStatusOrderByInvoiceDateAscIdAsc(Long customerId, InvoiceStatus status);

    /** Locks the invoice row so two payments cannot both use the same outstanding amount. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findForUpdate(@Param("id") Long id);
}
