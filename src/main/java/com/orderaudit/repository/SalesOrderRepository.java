package com.orderaudit.repository;

import com.orderaudit.domain.OrderStatus;
import com.orderaudit.domain.SalesOrder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {
    Optional<SalesOrder> findByClientRequestId(String clientRequestId);
    List<SalesOrder> findByStatusOrderByIdAsc(OrderStatus status);
    List<SalesOrder> findBySalesmanOrderByIdDesc(String salesman);
}
