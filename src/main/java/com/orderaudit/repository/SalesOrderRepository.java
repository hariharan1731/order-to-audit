package com.orderaudit.repository;

import com.orderaudit.domain.OrderStatus;
import com.orderaudit.domain.SalesOrder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {
    Optional<SalesOrder> findByClientRequestId(String clientRequestId);

    /**
     * Locks the order row until the transaction ends. Every status change (approve, reject, edit, invoice,
     * dispatch) reads the order through this, so two people acting on the same order at the same moment are
     * served one after the other, and the second one sees the first one's result (e.g. "already DISPATCHED")
     * instead of both going ahead and taking the stock out twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.id = :id")
    Optional<SalesOrder> findForUpdate(@Param("id") Long id);
    List<SalesOrder> findByStatusOrderByIdAsc(OrderStatus status);
    List<SalesOrder> findBySalesmanOrderByIdDesc(String salesman);
}
