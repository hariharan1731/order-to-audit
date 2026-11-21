package com.orderaudit.service;

import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.OrderLine;
import com.orderaudit.domain.OrderStatus;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.StockCheckLine;
import com.orderaudit.repository.CustomerRepository;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.repository.OrderLineRepository;
import com.orderaudit.repository.SalesOrderRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only: turns orders into the flat, ready-to-show shapes the web pages need (names, totals, stock check). */
@Service
public class OrderQueryService {

    /** One row of an order list. totalPaise is before GST (price x quantity). */
    public record OrderSummary(long id, String customerName, OrderStatus status, Instant createdAt,
                               String salesman, long totalPaise, long packets) {
    }

    /** availableNow and shortage are only filled while the order still waits for approval. */
    public record OrderLineView(long itemId, String itemName, long quantity, long unitPricePaise, long lineTotalPaise,
                                Long onHand, Long availableNow, Long shortage) {
    }

    public record OrderView(OrderSummary summary, String note, String decidedBy, String rejectReason,
                            String dispatchedBy, List<OrderLineView> lines, boolean anyShortage) {
    }

    private final SalesOrderRepository orders;
    private final OrderLineRepository lines;
    private final CustomerRepository customers;
    private final ItemRepository items;
    private final OrderService orderService;

    public OrderQueryService(SalesOrderRepository orders, OrderLineRepository lines, CustomerRepository customers,
                             ItemRepository items, OrderService orderService) {
        this.orders = orders;
        this.lines = lines;
        this.customers = customers;
        this.items = items;
        this.orderService = orderService;
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> mine(String salesman) {
        return summaries(orders.findBySalesmanOrderByIdDesc(salesman));
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> withStatus(OrderStatus status) {
        return summaries(orders.findByStatusOrderByIdAsc(status));
    }

    @Transactional(readOnly = true)
    public OrderView view(long orderId) {
        SalesOrder order = orders.findById(orderId).orElseThrow(
                () -> new IllegalArgumentException("Order " + orderId + " does not exist"));
        List<OrderLine> orderLines = lines.findByOrderIdOrderByItemIdAsc(orderId);
        Map<Long, Item> itemById = items.findAllById(orderLines.stream().map(OrderLine::getItemId).toList()).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        Map<Long, StockCheckLine> checkByItem = Map.of();
        if (order.getStatus() == OrderStatus.SUBMITTED) {
            checkByItem = orderService.stockCheck(orderId).stream()
                    .collect(Collectors.toMap(StockCheckLine::itemId, Function.identity()));
        }
        List<OrderLineView> views = new ArrayList<>();
        boolean anyShort = false;
        for (OrderLine line : orderLines) {
            StockCheckLine check = checkByItem.get(line.getItemId());
            anyShort |= check != null && check.isShort();
            views.add(new OrderLineView(line.getItemId(), itemById.get(line.getItemId()).getName(), line.getQuantity(),
                    line.getUnitPricePaise(), Math.multiplyExact(line.getQuantity(), line.getUnitPricePaise()),
                    check == null ? null : check.onHand(), check == null ? null : check.available(),
                    check == null ? null : check.shortage()));
        }
        return new OrderView(summaries(List.of(order)).get(0), order.getNote(), order.getDecidedBy(),
                order.getRejectReason(), order.getDispatchedBy(), views, anyShort);
    }

    private List<OrderSummary> summaries(List<SalesOrder> list) {
        List<OrderSummary> result = new ArrayList<>();
        for (SalesOrder order : list) {
            String customerName = customers.findById(order.getCustomerId()).map(Customer::getName).orElse("?");
            long total = 0;
            long packets = 0;
            for (OrderLine line : lines.findByOrderIdOrderByItemIdAsc(order.getId())) {
                total += Math.multiplyExact(line.getQuantity(), line.getUnitPricePaise());
                packets += line.getQuantity();
            }
            result.add(new OrderSummary(order.getId(), customerName, order.getStatus(), order.getCreatedAt(),
                    order.getSalesman(), total, packets));
        }
        return result;
    }
}
