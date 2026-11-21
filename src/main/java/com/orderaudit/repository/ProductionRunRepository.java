package com.orderaudit.repository;

import com.orderaudit.domain.ProductionRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionRunRepository extends JpaRepository<ProductionRun, Long> {
}
