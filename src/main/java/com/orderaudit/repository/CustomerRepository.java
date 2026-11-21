package com.orderaudit.repository;

import com.orderaudit.domain.Customer;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByGstin(String gstin);

    List<Customer> findByActiveTrueOrderByNameAsc();
}
