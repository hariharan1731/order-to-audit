package com.orderaudit.repository;

import com.orderaudit.domain.StockAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, Long> {
}
