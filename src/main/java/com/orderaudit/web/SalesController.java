package com.orderaudit.web;

import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.service.CustomerService;
import com.orderaudit.service.ItemService;
import com.orderaudit.service.OrderQueryService;
import com.orderaudit.service.OrderService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The salesman's screens: list my orders, place a new order, look at one order. Thin: all rules live in the services. */
@Controller
@RequestMapping("/sales")
public class SalesController {

    private final OrderService orderService;
    private final OrderQueryService queries;
    private final CustomerService customers;
    private final ItemService items;

    public SalesController(OrderService orderService, OrderQueryService queries, CustomerService customers,
                           ItemService items) {
        this.orderService = orderService;
        this.queries = queries;
        this.customers = customers;
        this.items = items;
    }

    @GetMapping("/orders")
    public String myOrders(Authentication auth, Model model) {
        model.addAttribute("orders", queries.mine(auth.getName()));
        return "sales/orders";
    }

    @GetMapping("/orders/new")
    public String newOrderForm(Model model) {
        model.addAttribute("customers", customers.activeList());
        model.addAttribute("products", items.activeItemsOfType(ItemType.FINISHED));
        model.addAttribute("requestId", UUID.randomUUID().toString());   // same id on a re-send = no duplicate order
        return "sales/new";
    }

    @PostMapping("/orders")
    public String submit(Authentication auth, @RequestParam long customerId,
                         @RequestParam(required = false) String requestId,
                         @RequestParam(required = false) String note,
                         @RequestParam(name = "itemId", required = false) List<Long> itemIds,
                         @RequestParam(name = "quantity", required = false) List<String> quantities,
                         RedirectAttributes redirect) {
        try {
            List<OrderLineRequest> requests = new ArrayList<>();
            for (int i = 0; itemIds != null && quantities != null && i < itemIds.size() && i < quantities.size(); i++) {
                String text = quantities.get(i) == null ? "" : quantities.get(i).trim();
                if (text.isEmpty() || text.equals("0")) {
                    continue;                                  // the salesman left this product at zero
                }
                requests.add(new OrderLineRequest(itemIds.get(i), Long.parseLong(text)));
            }
            SalesOrder order = orderService.submit(customerId, auth.getName(), requestId, note, requests);
            redirect.addFlashAttribute("message", "Order #" + order.getId() + " sent for approval.");
            return "redirect:/sales/orders/" + order.getId();
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "Quantities must be whole numbers.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/sales/orders/new";
    }

    @GetMapping("/orders/{id}")
    public String detail(@PathVariable long id, Authentication auth, Model model) {
        OrderQueryService.OrderView view;
        try {
            view = queries.view(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        boolean mine = view.summary().salesman().equalsIgnoreCase(auth.getName());
        boolean office = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_OWNER"));
        if (!mine && !office) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This is not your order");   // salesmen see only their own
        }
        model.addAttribute("order", view);
        return "sales/order";
    }
}
