package com.orderaudit.service;

import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.OrderLine;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.OrderStatus;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.StockCheckLine;
import com.orderaudit.repository.CustomerRepository;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.repository.OrderLineRepository;
import com.orderaudit.repository.SalesOrderRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders from submission to approval.
 *
 * SOFT RESERVATION (derived, never stored): available to promise = stock on hand - packets promised to
 * orders that are APPROVED or INVOICED but not yet dispatched. Stock itself only falls at dispatch.
 * Approval locks the stock rows first, so two admins approving at the same moment are served one by one
 * and the second sees the first one's promise.
 */
@Service
public class OrderService {

    static final List<OrderStatus> RESERVING = List.of(OrderStatus.APPROVED, OrderStatus.INVOICED);

    private final SalesOrderRepository orders;
    private final OrderLineRepository lines;
    private final CustomerRepository customers;
    private final ItemRepository items;
    private final PriceService prices;
    private final StockService stock;
    private final AuditService audit;

    public OrderService(SalesOrderRepository orders, OrderLineRepository lines, CustomerRepository customers,
                        ItemRepository items, PriceService prices, StockService stock, AuditService audit) {
        this.orders = orders;
        this.lines = lines;
        this.customers = customers;
        this.items = items;
        this.prices = prices;
        this.stock = stock;
        this.audit = audit;
    }

    /**
     * A salesman submits an order. clientRequestId (optional) makes re-sending safe: the same id returns
     * the order that was already created instead of making a duplicate (weak mobile networks retry).
     */
    @Transactional
    public SalesOrder submit(long customerId, String salesman, String clientRequestId, String note,
                             List<OrderLineRequest> requests) {
        if (salesman == null || salesman.isBlank() || salesman.length() > 100) {
            throw new IllegalArgumentException("Salesman is required (at most 100 characters)");
        }
        String requestId = clientRequestId == null || clientRequestId.isBlank() ? null : clientRequestId.trim();
        if (requestId != null) {
            if (requestId.length() > 60) {
                throw new IllegalArgumentException("Client request id must be at most 60 characters");
            }
            Optional<SalesOrder> existing = orders.findByClientRequestId(requestId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        if (note != null && note.length() > 300) {
            throw new IllegalArgumentException("Note must be at most 300 characters");
        }
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        Customer customer = customers.findById(customerId).orElseThrow(
                () -> new IllegalArgumentException("Customer " + customerId + " does not exist"));
        if (!customer.isActive()) {
            throw new IllegalArgumentException("Customer '" + customer.getName() + "' is not active");
        }
        Set<Long> seen = new HashSet<>();
        for (OrderLineRequest request : requests) {
            if (request.quantity() <= 0) {
                throw new IllegalArgumentException("Quantities must be greater than zero");
            }
            if (!seen.add(request.itemId())) {
                throw new IllegalArgumentException("Item " + request.itemId() + " appears twice in the order");
            }
            Item item = items.findById(request.itemId()).orElseThrow(
                    () -> new IllegalArgumentException("Item " + request.itemId() + " does not exist"));
            if (item.getItemType() != ItemType.FINISHED || !item.isActive()) {
                throw new IllegalArgumentException("'" + item.getName() + "' cannot be ordered");
            }
        }

        SalesOrder order = orders.save(new SalesOrder(customerId, salesman.trim(), requestId,
                note == null || note.isBlank() ? null : note.trim()));
        for (OrderLineRequest request : requests) {
            long price = prices.priceFor(customerId, request.itemId());   // fails clearly if no price is set
            lines.save(new OrderLine(order.getId(), request.itemId(), request.quantity(), price));
        }
        audit.record(salesman.trim(), "ORDER_SUBMITTED", "ORDER", order.getId(),
                requests.size() + " line(s) for customer " + customerId);
        return order;
    }

    /** For each line: what was asked, what is on hand, what is already promised, what is left, what is missing. */
    @Transactional(readOnly = true)
    public List<StockCheckLine> stockCheck(long orderId) {
        loadOrder(orderId);
        return check(lines.findByOrderIdOrderByItemIdAsc(orderId));
    }

    private List<StockCheckLine> check(List<OrderLine> orderLines) {
        List<StockCheckLine> result = new ArrayList<>();
        for (OrderLine line : orderLines) {
            long onHand = stock.balanceOf(line.getItemId());
            Long reservedOrNull = lines.reservedQuantity(line.getItemId(), RESERVING);
            long reserved = reservedOrNull == null ? 0 : reservedOrNull;
            long available = Math.max(0, onHand - reserved);
            long shortage = Math.max(0, line.getQuantity() - available);
            result.add(new StockCheckLine(line.getItemId(), line.getQuantity(), onHand, reserved, available, shortage));
        }
        return result;
    }

    /** Admin edit before approval: change one line's quantity and price. */
    @Transactional
    public void changeLine(long orderId, long itemId, long newQuantity, long newUnitPricePaise) {
        SalesOrder order = lockOrder(orderId);
        order.requireEditable();
        if (newQuantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero (reject the order instead)");
        }
        if (newUnitPricePaise < 0) {
            throw new IllegalArgumentException("Price must not be negative");
        }
        OrderLine line = lines.findByOrderIdAndItemId(orderId, itemId).orElseThrow(
                () -> new IllegalArgumentException("Item " + itemId + " is not on order " + orderId));
        line.change(newQuantity, newUnitPricePaise);
    }

    /**
     * Approve if stock covers EVERY line, otherwise throw ShortageException and change nothing.
     * (The admin can then reduce the quantity with changeLine, or reject the order.)
     */
    @Transactional
    public SalesOrder approve(long orderId, String approvedBy) {
        requireUser(approvedBy);
        SalesOrder order = lockOrder(orderId);
        if (order.getStatus() != OrderStatus.SUBMITTED) {
            throw new IllegalStateException("Cannot approve an order that is " + order.getStatus());
        }
        List<OrderLine> orderLines = lines.findByOrderIdOrderByItemIdAsc(orderId);
        for (OrderLine line : orderLines) {          // ascending item id: no deadlocks between admins
            stock.lock(line.getItemId());
        }
        List<StockCheckLine> shortages = check(orderLines).stream().filter(StockCheckLine::isShort).toList();
        if (!shortages.isEmpty()) {
            throw new ShortageException(shortages);
        }
        order.approve(approvedBy.trim());
        audit.record(approvedBy, "ORDER_APPROVED", "ORDER", orderId, null);
        return order;
    }

    @Transactional
    public SalesOrder reject(long orderId, String rejectedBy, String reason) {
        requireUser(rejectedBy);
        if (reason == null || reason.isBlank() || reason.length() > 300) {
            throw new IllegalArgumentException("A reason is required (at most 300 characters)");
        }
        SalesOrder order = lockOrder(orderId);
        order.reject(rejectedBy.trim(), reason.trim());
        audit.record(rejectedBy, "ORDER_REJECTED", "ORDER", orderId, reason.trim());
        return order;
    }

    @Transactional(readOnly = true)
    public List<SalesOrder> ordersWithStatus(OrderStatus status) {
        return orders.findByStatusOrderByIdAsc(status);
    }

    @Transactional(readOnly = true)
    public List<SalesOrder> ordersOfSalesman(String salesman) {
        return orders.findBySalesmanOrderByIdDesc(salesman);
    }

    @Transactional(readOnly = true)
    public List<OrderLine> linesOf(long orderId) {
        return lines.findByOrderIdOrderByItemIdAsc(orderId);
    }

    /** Read the order AND lock its row, for anything that changes it (see SalesOrderRepository.findForUpdate). */
    private SalesOrder lockOrder(long orderId) {
        return orders.findForUpdate(orderId).orElseThrow(
                () -> new IllegalArgumentException("Order " + orderId + " does not exist"));
    }

    private SalesOrder loadOrder(long orderId) {
        return orders.findById(orderId).orElseThrow(
                () -> new IllegalArgumentException("Order " + orderId + " does not exist"));
    }

    private static void requireUser(String user) {
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("The user making this change is required");
        }
    }
}
