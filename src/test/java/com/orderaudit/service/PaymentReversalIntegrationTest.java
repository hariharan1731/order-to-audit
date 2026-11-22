package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderaudit.domain.AllocationRequest;
import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.InvoiceStatus;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.Payment;
import com.orderaudit.domain.PaymentMethod;
import com.orderaudit.domain.PaymentRow;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.Unit;
import com.orderaudit.report.ReportPeriod;
import com.orderaudit.report.ReportService;
import com.orderaudit.report.ReportTable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Audit finding H1: a payment can be reversed (bounced cheque, wrong customer). Nothing is edited or deleted;
 * the invoice simply owes the money again, the history keeps both rows, and the credit-note dead end is gone.
 * Retailer A's official invoice is Rs 7,875.00 (787,500 paise); the official payment is Rs 4,000.00.
 */
@SpringBootTest
@Testcontainers
@Transactional
class PaymentReversalIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);

    @Autowired WebApplicationContext context;
    @Autowired ItemService itemService;
    @Autowired CustomerService customerService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired InvoiceService invoiceService;
    @Autowired DispatchService dispatchService;
    @Autowired CreditNoteService creditNotes;
    @Autowired ReceivablesService receivables;
    @Autowired ReportService reports;
    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;
    Customer retailerA;
    Invoice invoice;
    Payment payment;
    SalesOrder order;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Item product = itemService.create("Reversal Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 10_000, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
        retailerA = customerService.create("Retailer A", null, "33", null);
        order = orderService.submit(retailerA.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(product.getId(), 150)));
        orderService.approve(order.getId(), "admin");
        invoice = invoiceService.raise(order.getId(), DAY, "admin");
        payment = receivables.recordPayment(retailerA.getId(), DAY, PaymentMethod.CASH, "CH-1", "admin",
                List.of(new AllocationRequest(invoice.getId(), 400_000)));
    }

    private static RequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String flashOf(MockHttpServletRequestBuilder request, String key) throws Exception {
        var result = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn();
        Object value = result.getFlashMap().get(key);
        return value == null ? null : value.toString();
    }

    // ---- the money

    @Test
    void reversingTheOfficialPaymentPutsTheWholeInvoiceBackToOutstanding() {
        assertThat(receivables.outstandingOfInvoice(invoice.getId())).isEqualTo(387_500);   // official Rs 3,875

        receivables.reversePayment(payment.getId(), DAY, "Cheque bounced", "admin");

        assertThat(receivables.outstandingOfInvoice(invoice.getId())).isEqualTo(787_500);   // back to Rs 7,875
        assertThat(receivables.customerOutstanding(retailerA.getId())).isEqualTo(787_500);
        assertThat(receivables.openInvoices(retailerA.getId())).hasSize(1);
        assertThat(invoiceService.get(invoice.getId()).getStatus()).isEqualTo(InvoiceStatus.ISSUED);
    }

    @Test
    void theReversedPaymentIsKeptInHistoryAndMarkedReversed() {
        receivables.reversePayment(payment.getId(), DAY, "Wrong customer", "admin");

        List<PaymentRow> history = receivables.paymentsOf(retailerA.getId());
        assertThat(history).hasSize(1);
        PaymentRow row = history.get(0);
        assertThat(row.reversed()).isTrue();
        assertThat(row.amountPaise()).isEqualTo(400_000);
        assertThat(row.reversalReason()).isEqualTo("Wrong customer");
        assertThat(row.reversedBy()).isEqualTo("admin");
        assertThat(row.invoices()).isEqualTo(invoice.getInvoiceNumber());
    }

    @Test
    void reversingOnePaymentLeavesTheOthersCounting() {
        Payment second = receivables.recordPayment(retailerA.getId(), DAY, PaymentMethod.UPI, "U-2", "admin",
                List.of(new AllocationRequest(invoice.getId(), 100_000)));
        assertThat(receivables.outstandingOfInvoice(invoice.getId())).isEqualTo(287_500);

        receivables.reversePayment(second.getId(), DAY, "Keyed twice", "admin");

        assertThat(receivables.outstandingOfInvoice(invoice.getId())).isEqualTo(387_500);   // first payment still counts
        assertThat(receivables.paymentsOf(retailerA.getId())).extracting(PaymentRow::reversed)
                .containsExactly(false, true);
    }

    @Test
    void aReversedPaymentDoesNotCountInTheAgeingReport() {
        assertThat(receivables.ageing(DAY).get(0).total()).isEqualTo(387_500);
        receivables.reversePayment(payment.getId(), DAY, "Cheque bounced", "admin");
        assertThat(receivables.ageing(DAY).get(0).total()).isEqualTo(787_500);
    }

    @Test
    void theCustomerCanPayTheInvoiceAgainAfterAReversal() {
        receivables.reversePayment(payment.getId(), DAY, "Cheque bounced", "admin");

        receivables.recordPaymentOldestFirst(retailerA.getId(), DAY, PaymentMethod.UPI, "U-9", "admin", 787_500);

        assertThat(receivables.outstandingOfInvoice(invoice.getId())).isZero();
    }

    // ---- the rules

    @Test
    void aPaymentCanOnlyBeReversedOnce() {
        receivables.reversePayment(payment.getId(), DAY, "Cheque bounced", "admin");

        assertThatThrownBy(() -> receivables.reversePayment(payment.getId(), DAY, "Again", "admin"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already been reversed");
    }

    @Test
    void aReasonIsRequiredAndTheDateCannotBeBeforeThePayment() {
        assertThatThrownBy(() -> receivables.reversePayment(payment.getId(), DAY, "  ", "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> receivables.reversePayment(payment.getId(), DAY, "x".repeat(201), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> receivables.reversePayment(payment.getId(), DAY.minusDays(1), "Early", "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("before the payment date");
        assertThatThrownBy(() -> receivables.reversePayment(payment.getId(), null, "No date", "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("date");
        assertThatThrownBy(() -> receivables.reversePayment(999_999L, DAY, "Ghost", "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");
        assertThat(receivables.outstandingOfInvoice(invoice.getId())).isEqualTo(387_500);   // nothing changed
    }

    @Test
    void theAuditLogRecordsWhoReversedWhichPaymentAndWhy() {
        receivables.reversePayment(payment.getId(), DAY, "Cheque bounced", "admin");

        List<AuditEntry> trail = audit.forEntity("PAYMENT", payment.getId());
        assertThat(trail).extracting(AuditEntry::action).containsExactly("PAYMENT_RECORDED", "PAYMENT_REVERSED");
        AuditEntry reversed = trail.get(1);
        assertThat(reversed.actor()).isEqualTo("admin");
        assertThat(reversed.details()).contains("400000 paise").contains("Cheque bounced");
    }

    @Test
    void theDatabaseItselfRefusesToEditOrDeleteAReversal() {
        receivables.reversePayment(payment.getId(), DAY, "Cheque bounced", "admin");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM payment_reversal"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    // ---- the credit-note dead end is gone

    @Test
    void afterReversingThePaymentTheCreditNoteCanBeIssued() {
        dispatchService.dispatch(order.getId(), "admin");
        assertThatThrownBy(() -> creditNotes.issue(invoice.getId(), DAY, "Goods returned", "admin"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("refund the payment");

        receivables.reversePayment(payment.getId(), DAY, "Refunded to customer", "admin");
        var note = creditNotes.issue(invoice.getId(), DAY, "Goods returned", "admin");

        assertThat(note.getCreditNoteNumber()).isEqualTo("CN/2026-27/0001");
        assertThat(invoiceService.get(invoice.getId()).getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
    }

    // ---- the collections report

    @Test
    void theCollectionsReportShowsTheReceiptThenTheReversalAsANegativeLine() {
        receivables.reversePayment(payment.getId(), DAY.plusDays(3), "Cheque bounced", "admin");

        ReportTable onTheDay = reports.collections(new ReportPeriod(DAY, DAY));
        assertThat(onTheDay.rows()).hasSize(1);                         // October-style rule: the past does not change
        assertThat(onTheDay.totals().get(5)).isEqualTo(400_000L);

        ReportTable later = reports.collections(new ReportPeriod(DAY.plusDays(3), DAY.plusDays(3)));
        assertThat(later.rows()).hasSize(1);
        assertThat(later.rows().get(0).get(2)).isEqualTo("REVERSED");
        assertThat((String) later.rows().get(0).get(3)).contains("Cheque bounced");
        assertThat(later.totals().get(5)).isEqualTo(-400_000L);

        ReportTable both = reports.collections(new ReportPeriod(DAY, DAY.plusDays(3)));
        assertThat(both.rows()).hasSize(2);
        assertThat(both.totals().get(5)).isEqualTo(0L);                 // net collected is zero
    }

    // ---- the website

    @Test
    void theCustomerPageListsPaymentsAndShowsTheReverseControlOnlyForCountedOnes() throws Exception {
        String before = body(get("/admin/payments/customers/" + retailerA.getId()).with(admin()));
        assertThat(before).contains("Payments received").contains("CH-1").contains("Rs 4,000.00")
                .contains("Counted").contains("Confirm reversal");

        receivables.reversePayment(payment.getId(), DAY, "Cheque bounced", "admin");
        String after = body(get("/admin/payments/customers/" + retailerA.getId()).with(admin()));
        assertThat(after).contains("Reversed").contains("Cheque bounced").doesNotContain("Confirm reversal");
    }

    @Test
    void reversingFromTheWebsiteWorksAndTellsTheUser() throws Exception {
        String message = flashOf(post("/admin/payments/" + payment.getId() + "/reverse").with(admin()).with(csrf())
                .param("customerId", String.valueOf(retailerA.getId()))
                .param("reason", "Cheque bounced").param("reversalDate", DAY.toString()), "message");

        assertThat(message).contains("Payment reversed");
        assertThat(receivables.outstandingOfInvoice(invoice.getId())).isEqualTo(787_500);
    }

    @Test
    void theWebsiteShowsAnErrorInsteadOfCrashingWhenTheReasonIsMissingOrItIsAlreadyReversed() throws Exception {
        String noReason = flashOf(post("/admin/payments/" + payment.getId() + "/reverse").with(admin()).with(csrf())
                .param("customerId", String.valueOf(retailerA.getId()))
                .param("reason", " ").param("reversalDate", DAY.toString()), "error");
        assertThat(noReason).contains("reason");

        receivables.reversePayment(payment.getId(), DAY, "Cheque bounced", "admin");
        String twice = flashOf(post("/admin/payments/" + payment.getId() + "/reverse").with(admin()).with(csrf())
                .param("customerId", String.valueOf(retailerA.getId()))
                .param("reason", "Again").param("reversalDate", DAY.toString()), "error");
        assertThat(twice).contains("already been reversed");
    }

    @Test
    void aSalesmanCannotReverseAPaymentAndTheDebtorsPageLinksEveryCustomer() throws Exception {
        mvc.perform(post("/admin/payments/" + payment.getId() + "/reverse").with(user("ravi").roles("SALESMAN")).with(csrf())
                .param("customerId", "1").param("reason", "x").param("reversalDate", DAY.toString()))
                .andExpect(status().isForbidden());
        assertThat(receivables.outstandingOfInvoice(invoice.getId())).isEqualTo(387_500);

        // Retailer A still owes money, but a fully paid customer must stay reachable too: every customer is linked
        assertThat(body(get("/admin/payments").with(admin()))).contains("Payment history and reversals")
                .contains("/admin/payments/customers/" + retailerA.getId());
    }
}
