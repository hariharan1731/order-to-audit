package com.orderaudit.web;

import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.Money;
import com.orderaudit.domain.OrderStatus;
import com.orderaudit.report.ReportPeriod;
import com.orderaudit.service.DispatchService;
import com.orderaudit.service.InvoiceService;
import com.orderaudit.service.OrderQueryService;
import com.orderaudit.service.OrderService;
import com.orderaudit.service.ShortageException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
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

/** The office's order desk: approve or reject, adjust a line, raise the invoice, dispatch. */
@Controller
@RequestMapping("/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;
    private final OrderQueryService queries;
    private final InvoiceService invoiceService;
    private final DispatchService dispatchService;

    public AdminOrderController(OrderService orderService, OrderQueryService queries, InvoiceService invoiceService,
                                DispatchService dispatchService) {
        this.orderService = orderService;
        this.queries = queries;
        this.invoiceService = invoiceService;
        this.dispatchService = dispatchService;
    }

    @GetMapping
    public String desk(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String sort, Model model) {
        model.addAttribute("toApprove", queries.withStatus(OrderStatus.SUBMITTED));
        model.addAttribute("toInvoice", queries.withStatus(OrderStatus.APPROVED));
        model.addAttribute("toDispatch", queries.withStatus(OrderStatus.INVOICED));
        OrderFilter filter = new OrderFilter(q, status, from, to, sort);
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("stats", queries.stats(null));
        if (filter.active()) {                       // searching shows one result list instead of the three queues
            model.addAttribute("results",
                    queries.search(null, q, filter.statusValue(), from, to, filter.sortOrDefault()));
        }
        return "admin/orders";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        OrderQueryService.OrderView view;
        try {
            view = queries.view(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        model.addAttribute("order", view);
        model.addAttribute("today", ReportPeriod.today());
        OrderStatus status = view.summary().status();
        if (status == OrderStatus.INVOICED || status == OrderStatus.DISPATCHED) {
            model.addAttribute("invoice", invoiceService.forOrder(id));
        }
        return "admin/order";
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        try {
            orderService.approve(id, auth.getName());
            redirect.addFlashAttribute("message", "Order #" + id + " approved. The stock is now reserved for it.");
        } catch (ShortageException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable long id, @RequestParam String reason, Authentication auth,
                         RedirectAttributes redirect) {
        try {
            orderService.reject(id, auth.getName(), reason);
            redirect.addFlashAttribute("message", "Order #" + id + " rejected.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/{id}/lines/{itemId}")
    public String changeLine(@PathVariable long id, @PathVariable long itemId, @RequestParam long quantity,
                             @RequestParam BigDecimal priceRupees, Authentication auth, RedirectAttributes redirect) {
        try {
            orderService.changeLine(id, itemId, quantity, Money.ofRupees(priceRupees).paise(), auth.getName());
            redirect.addFlashAttribute("message", "Line updated.");
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/{id}/invoice")
    public String raiseInvoice(@PathVariable long id,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate invoiceDate,
                               Authentication auth, RedirectAttributes redirect) {
        try {
            Invoice invoice = invoiceService.raise(id, invoiceDate, auth.getName());
            redirect.addFlashAttribute("message", "Invoice " + invoice.getInvoiceNumber() + " raised.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/{id}/dispatch")
    public String dispatch(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        try {
            dispatchService.dispatch(id, auth.getName());
            redirect.addFlashAttribute("message", "Order #" + id + " dispatched. Stock has been reduced.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/orders/" + id;
    }
}
