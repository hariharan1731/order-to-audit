package com.orderaudit.repository;

import com.orderaudit.domain.Payment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findByCustomerIdOrderByIdAsc(Long customerId);

    Optional<Payment> findByClientRequestId(String clientRequestId);
}
