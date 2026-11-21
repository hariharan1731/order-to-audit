package com.orderaudit.repository;

import com.orderaudit.domain.ItemPrice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemPriceRepository extends JpaRepository<ItemPrice, Long> {
}
