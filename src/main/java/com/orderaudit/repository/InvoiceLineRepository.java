package com.orderaudit.repository;

import com.orderaudit.domain.InvoiceLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, Long> {
    List<InvoiceLine> findByInvoiceIdOrderByIdAsc(Long invoiceId);
}
