package com.orderaudit.repository;

import com.orderaudit.domain.OrderLine;
import com.orderaudit.domain.OrderStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

    List<OrderLine> findByOrderIdOrderByItemIdAsc(Long orderId);

    Optional<OrderLine> findByOrderIdAndItemId(Long orderId, Long itemId);

    /** Packets promised to orders in the given statuses (null if there are none). This IS the soft reservation. */
    @Query("select sum(l.quantity) from OrderLine l, SalesOrder o "
            + "where l.orderId = o.id and l.itemId = :itemId and o.status in :statuses")
    Long reservedQuantity(@Param("itemId") Long itemId, @Param("statuses") Collection<OrderStatus> statuses);
}
