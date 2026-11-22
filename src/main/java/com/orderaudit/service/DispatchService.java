package com.orderaudit.service;

import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.OrderLine;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.SourceType;
import com.orderaudit.repository.OrderLineRepository;
import com.orderaudit.repository.SalesOrderRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dispatch is the moment finished-goods stock really falls. It re-checks the real stock (the stock
 * service refuses to go below zero), so even if the reservation was somehow bypassed the ledger stays correct.
 */
@Service
public class DispatchService {

    private final SalesOrderRepository orders;
    private final OrderLineRepository orderLines;
    private final StockService stock;
    private final AuditService audit;

    public DispatchService(SalesOrderRepository orders, OrderLineRepository orderLines, StockService stock,
                           AuditService audit) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.stock = stock;
        this.audit = audit;
    }

    @Transactional
    public SalesOrder dispatch(long orderId, String dispatchedBy) {
        if (dispatchedBy == null || dispatchedBy.isBlank()) {
            throw new IllegalArgumentException("dispatchedBy is required");
        }
        SalesOrder order = orders.findForUpdate(orderId).orElseThrow(   // locked: no double dispatch
                () -> new IllegalArgumentException("Order " + orderId + " does not exist"));
        order.markDispatched(dispatchedBy.trim());   // throws unless INVOICED
        List<OrderLine> lines = orderLines.findByOrderIdOrderByItemIdAsc(orderId);
        for (OrderLine line : lines) {
            stock.post(line.getItemId(), MovementType.SALE, -line.getQuantity(), null,
                    SourceType.DISPATCH, orderId, dispatchedBy);
        }
        audit.record(dispatchedBy, "ORDER_DISPATCHED", "ORDER", orderId, null);
        return order;
    }
}
