package com.orderaudit.repository;

import com.orderaudit.domain.PaymentReversal;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentReversalRepository extends JpaRepository<PaymentReversal, Long> {

    boolean existsByPaymentId(Long paymentId);

    List<PaymentReversal> findByPaymentIdIn(Collection<Long> paymentIds);
}
