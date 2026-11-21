package com.orderaudit.repository;

import com.orderaudit.domain.PurchaseLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseLineRepository extends JpaRepository<PurchaseLine, Long> {
    List<PurchaseLine> findByPurchaseId(Long purchaseId);
}
