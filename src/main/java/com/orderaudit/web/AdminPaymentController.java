package com.orderaudit.web;

import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Money;
import com.orderaudit.domain.PaymentMethod;
import com.orderaudit.report.ReportPeriod;
import com.orderaudit.service.CustomerService;
import com.orderaudit.service.ReceivablesService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

/** Money coming in: who owes what, and recording a payment (spread over the oldest unpaid invoices first). */
@Controller
@RequestMapping("/admin/payments")
public class AdminPaymentController {

    /** One customer with what they still owe. */
    public record Debtor(long customerId, String name, long outstandingPaise) {
    }

    private final CustomerService customers;
    private final ReceivablesService receivables;

    public AdminPaymentController(CustomerService customers, ReceivablesService receivables) {
        this.customers = customers;
        this.receivables = receivables;
    }

    @GetMapping
    public String debtors(Model model) {
        List<Debtor> debtors = new ArrayList<>();
        for (Customer c : customers.activeList()) {
            long owed = receivables.customerOutstanding(c.getId());
            if (owed > 0) {
                debtors.add(new Debtor(c.getId(), c.getName(), owed));
            }
        }
        model.addAttribute("debtors", debtors);
        model.addAttribute("total", debtors.stream().mapToLong(Debtor::outstandingPaise).sum());
        model.addAttribute("allCustomers", customers.activeList());   // so a fully paid customer's payments can still be opened
        return "admin/payments";
    }

    @GetMapping("/customers/{id}")
    public String customer(@PathVariable long id, Model model) {
        Customer customer = customers.activeList().stream().filter(c -> c.getId() == id).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown customer"));
        model.addAttribute("customer", customer);
        model.addAttribute("open", receivables.openInvoices(id));
        model.addAttribute("owed", receivables.customerOutstanding(id));
        model.addAttribute("history", receivables.paymentsOf(id));
        model.addAttribute("methods", PaymentMethod.values());
        model.addAttribute("today", ReportPeriod.today());
        model.addAttribute("requestId", java.util.UUID.randomUUID().toString());   // same id twice = one payment
        return "admin/payment-form";
    }

    @PostMapping("/customers/{id}")
    public String record(@PathVariable long id, @RequestParam BigDecimal amountRupees,
                         @RequestParam PaymentMethod method, @RequestParam(required = false) String reference,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDate,
                         @RequestParam(required = false) String requestId,
                         Authentication auth, RedirectAttributes redirect) {
        try {
            long paise = Money.ofRupees(amountRupees).paise();
            receivables.recordPaymentOldestFirst(id, paymentDate, method, reference, auth.getName(), paise, requestId);
            redirect.addFlashAttribute("message", "Payment recorded and applied to the oldest invoices first.");
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/payments/customers/" + id;
    }

    /** Cancel a payment (bounced cheque, wrong customer...). customerId is only used to come back to the same page. */
    @PostMapping("/{paymentId}/reverse")
    public String reverse(@PathVariable long paymentId, @RequestParam long customerId,
                          @RequestParam String reason,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reversalDate,
                          Authentication auth, RedirectAttributes redirect) {
        try {
            receivables.reversePayment(paymentId, reversalDate, reason, auth.getName());
            redirect.addFlashAttribute("message", "Payment reversed. The invoices it paid are outstanding again.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/payments/customers/" + customerId;
    }
}
