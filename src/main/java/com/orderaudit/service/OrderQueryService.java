package com.orderaudit.service;

import com.orderaudit.domain.Customer;
import com.orderaudit.domain.GstBreakdown;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.Money;
import com.orderaudit.domain.OrderLine;
import com.orderaudit.domain.OrderStatus;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.StockCheckLine;
import com.orderaudit.report.ReportPeriod;
import com.orderaudit.repository.CustomerRepository;
import com.orderaudit.repository.InvoiceRepository;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.repository.OrderLineRepository;
import com.orderaudit.repository.SalesOrderRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only: turns orders into the flat, ready-to-show shapes the web pages need (names, totals, stock check). */
@Service
public class OrderQueryService {

    /**
     * One row of an order list.
     * totalPaise is before GST (price x quantity). gstPaise and totalWithGstPaise come from the real invoice once
     * the order is invoiced; before that they are what the invoice WOULD be at today's rates, and gstEstimated() is
     * true so the screen can say so. returned is true when a credit note cancelled the invoice (the order itself stays
     * Dispatched, because it was). intraState: true means CGST + SGST (customer in the factory's state), false
     * means IGST; taken from the invoice once there is one. products is a short description like "Murukku 200 g" or "Murukku 200 g +1 more".
     */
    public record OrderSummary(long id, String customerName, OrderStatus status, Instant createdAt,
                               String salesman, long totalPaise, long packets, long gstPaise,
                               long totalWithGstPaise, String invoiceNumber, String products, boolean intraState,
                               boolean returned) {

        /** "CGST + SGST" or "IGST": the tax this order is (or will be) invoiced with. */
        public String taxName() {
            return intraState ? "CGST + SGST" : "IGST";
        }

        /** True while no invoice exists yet, so the GST shown is an expectation and not a document. */
        public boolean gstEstimated() {
            return invoiceNumber == null;
        }

        /**
         * The order's date in Indian time, used by the date filter.
         * The database fills created_at, so this is null for an order that has been saved but not read back yet.
         */
        public LocalDate orderDate() {
            return createdAt == null ? null : createdAt.atZone(ReportPeriod.ZONE).toLocalDate();
        }
    }

    /**
     * How many orders sit in each stage, for the dashboard cards.
     * "Pending" means started but not finished: waiting approval + approved + invoiced.
     * "Completed" means dispatched - the goods have left the factory.
     */
    public record OrderStats(long total, long submitted, long approved, long invoiced, long dispatched,
                             long rejected) {

        public long pending() {
            return submitted + approved + invoiced;
        }

        public long completed() {
            return dispatched;
        }
    }

    /**
     * One customer's line in a salesman's customer-wise sales.
     * billedPaise: orders that have an invoice (invoiced or dispatched), from the invoice incl. GST.
     * pendingPaise: orders still waiting (submitted or approved), at today's prices incl. expected GST.
     * Rejected orders are left out completely.
     */
    public record CustomerSales(String customerName, long orders, long packets, long billedPaise,
                                long pendingPaise) {
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
    private final InvoiceRepository invoices;
    private final SettingService settings;
    private final OrderService orderService;

    public OrderQueryService(SalesOrderRepository orders, OrderLineRepository lines, CustomerRepository customers,
                             ItemRepository items, InvoiceRepository invoices, SettingService settings,
                             OrderService orderService) {
        this.orders = orders;
        this.lines = lines;
        this.customers = customers;
        this.items = items;
        this.invoices = invoices;
        this.settings = settings;
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

    /**
     * Search, filter and sort. Every argument may be null, which means "do not filter on this".
     * salesman limits the list to one person's orders (the salesman's own screen); null means every order.
     * query matches the order number or any part of the customer's name, ignoring case.
     */
    @Transactional(readOnly = true)
    public List<OrderSummary> search(String salesman, String query, OrderStatus status, LocalDate from, LocalDate to,
                                     String sort) {
        List<OrderSummary> rows = new ArrayList<>(salesman == null || salesman.isBlank()
                ? summaries(orders.findAll()) : summaries(orders.findBySalesmanOrderByIdDesc(salesman)));
        String text = query == null ? "" : query.trim().toLowerCase();
        String digits = text.startsWith("#") ? text.substring(1) : text;
        rows.removeIf(o -> {
            if (status != null && o.status() != status) {
                return true;
            }
            LocalDate day = o.orderDate();            // null only when the row has not been read back from the DB
            if (day != null && from != null && day.isBefore(from)) {
                return true;
            }
            if (day != null && to != null && day.isAfter(to)) {
                return true;
            }
            if (!text.isEmpty()) {
                boolean matchesNumber = String.valueOf(o.id()).equals(digits);
                boolean matchesName = o.customerName().toLowerCase().contains(text);
                boolean matchesInvoice = o.invoiceNumber() != null && o.invoiceNumber().toLowerCase().contains(text);
                return !(matchesNumber || matchesName || matchesInvoice);
            }
            return false;
        });
        rows.sort(comparator(sort));
        return rows;
    }

    private static Comparator<OrderSummary> comparator(String sort) {
        return switch (sort == null ? "" : sort) {
            case "oldest" -> Comparator.comparingLong(OrderSummary::id);
            case "amount" -> Comparator.comparingLong(OrderSummary::totalWithGstPaise).reversed();
            case "customer" -> Comparator.comparing(OrderSummary::customerName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Comparator.comparingLong(OrderSummary::id).reversed());
            default -> Comparator.comparingLong(OrderSummary::id).reversed();      // newest first
        };
    }

    /** Counts per stage. salesman null = the whole factory, otherwise only that salesman's orders. */
    @Transactional(readOnly = true)
    public OrderStats stats(String salesman) {
        List<SalesOrder> list = salesman == null || salesman.isBlank()
                ? orders.findAll() : orders.findBySalesmanOrderByIdDesc(salesman);
        long submitted = count(list, OrderStatus.SUBMITTED);
        long approved = count(list, OrderStatus.APPROVED);
        long invoiced = count(list, OrderStatus.INVOICED);
        long dispatched = count(list, OrderStatus.DISPATCHED);
        long rejected = count(list, OrderStatus.REJECTED);
        return new OrderStats(list.size(), submitted, approved, invoiced, dispatched, rejected);
    }

    /**
     * Groups order rows by customer: the salesman's customer-wise sales. Works on whatever rows the screen shows,
     * so the date and status filters apply. Biggest billed customer first, then by name.
     */
    public static List<CustomerSales> customerWise(List<OrderSummary> rows) {
        Map<String, long[]> byCustomer = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (OrderSummary o : rows) {
            if (o.status() == OrderStatus.REJECTED || o.returned()) {
                continue;                       // rejected, or the whole invoice was cancelled by a credit note
            }
            long[] t = byCustomer.computeIfAbsent(o.customerName(), k -> new long[4]);
            t[0]++;
            t[1] += o.packets();
            if (o.invoiceNumber() != null) {
                t[2] += o.totalWithGstPaise();
            } else {
                t[3] += o.totalWithGstPaise();
            }
        }
        List<CustomerSales> result = new ArrayList<>();
        byCustomer.forEach((name, t) -> result.add(new CustomerSales(name, t[0], t[1], t[2], t[3])));
        result.sort(Comparator.comparingLong(CustomerSales::billedPaise).reversed()
                .thenComparing(CustomerSales::customerName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static long count(List<SalesOrder> list, OrderStatus status) {
        return list.stream().filter(o -> o.getStatus() == status).count();
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
        String factoryState = settings.factoryStateCode();
        List<OrderSummary> result = new ArrayList<>();
        for (SalesOrder order : list) {
            Customer customer = customers.findById(order.getCustomerId()).orElse(null);
            String customerName = customer == null ? "?" : customer.getName();
            boolean intra = customer != null && GstCalculator.isIntraState(factoryState, customer.getStateCode());

            long total = 0;
            long packets = 0;
            GstBreakdown expected = GstBreakdown.ZERO;
            List<String> names = new ArrayList<>();
            for (OrderLine line : lines.findByOrderIdOrderByItemIdAsc(order.getId())) {
                long lineTotal = Math.multiplyExact(line.getQuantity(), line.getUnitPricePaise());
                total += lineTotal;
                packets += line.getQuantity();
                Item item = items.findById(line.getItemId()).orElse(null);
                if (item != null) {
                    names.add(item.getName());
                    expected = expected.plus(
                            GstCalculator.calculate(Money.ofPaise(lineTotal), item.getGstRatePercent(), intra));
                }
            }

            // Once the invoice exists its stored paise are the truth; before that we show what it would come to.
            Optional<Invoice> invoice = invoices.findByOrderId(order.getId());
            long gst = invoice.map(i -> i.getCgstPaise() + i.getSgstPaise() + i.getIgstPaise())
                    .orElse(expected.totalTax().paise());
            long withGst = invoice.map(Invoice::getTotalPaise).orElse(total + gst);

            result.add(new OrderSummary(order.getId(), customerName, order.getStatus(), order.getCreatedAt(),
                    order.getSalesman(), total, packets, gst, withGst,
                    invoice.map(Invoice::getInvoiceNumber).orElse(null), describe(names),
                    invoice.map(Invoice::isIntraState).orElse(intra),
                    invoice.map(i -> i.getStatus() == com.orderaudit.domain.InvoiceStatus.CANCELLED).orElse(false)));
        }
        return result;
    }

    /** "Murukku 200 g" for one product, "Murukku 200 g +2 more" when the order has several. */
    private static String describe(List<String> names) {
        if (names.isEmpty()) {
            return "-";
        }
        return names.size() == 1 ? names.get(0) : names.get(0) + " +" + (names.size() - 1) + " more";
    }
}
