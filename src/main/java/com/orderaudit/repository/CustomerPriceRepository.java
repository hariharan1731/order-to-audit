package com.orderaudit.repository;

import com.orderaudit.domain.CustomerPrice;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerPriceRepository extends JpaRepository<CustomerPrice, Long> {
    Optional<CustomerPrice> findByCustomerIdAndItemId(Long customerId, Long itemId);
}
