package com.orderaudit.repository;

import com.orderaudit.domain.CreditNote;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditNoteRepository extends JpaRepository<CreditNote, Long> {

    Optional<CreditNote> findByInvoiceId(Long invoiceId);

    List<CreditNote> findAllByOrderByIdDesc();
}
