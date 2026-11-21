package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.AllocationRequest;
import com.orderaudit.domain.CreditNote;
import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.InvoiceStatus;
import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.PaymentMethod;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.SourceType;
import com.orderaudit.domain.StockMovement;
import com.orderaudit.report.ReportPeriod;
import com.orderaudit.report.ReportService;
import com.orderaudit.report.ReportTable;
import com.orderaudit.repository.InvoiceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL, rolled back after each test. Retailer A's official invoice is dispatched, then returned. */
@SpringBootTest
@Testcontainers
@Transactional
class CreditNoteIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);

    @Autowired ItemService itemService;
    @Autowired RecipeService recipeService;
    @Autowired PackConfigService packConfigService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired SupplierService supplierService;
    @Autowired CustomerService customerService;
    @Autowired ProductionService productionService;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired InvoiceService invoiceService;
    @Autowired DispatchService dispatchService;
    @Autowired ReceivablesService receivables;
    @Autowired CreditNoteService creditNotes;
    @Autowired StockService stock;
    @Autowired AuditService audit;
    @Autowired ReportService reports;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired JdbcTemplate jdbc;

    private BenchmarkFixture f;
    private Customer retailerA;

    private void produce() {
        f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
        productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(new ActualLine(f.oil.getId(), 10_500), new ActualLine(f.pouch.getId(), 194),
                        new ActualLine(f.carton.getId(), 9)),
                List.of(new OverheadInput("Labour", 120_000), new OverheadInput("Gas and power", 60_000)), "production");
        retailerA = customerService.create("Retailer A", null, "33", null);
        priceService.setListPrice(f.murukku.getId(), 5_000);
    }

    private SalesOrder approvedOrder(long packets) {
        SalesOrder order = orderService.submit(retailerA.getId(), "salesman-a", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), packets)));
        orderService.approve(order.getId(), "admin");
        return order;
    }

    /** The official Retailer A invoice, invoiced and dispatched: stock 88. */
    private Invoice dispatchedInvoice() {
        produce();
        SalesOrder order = approvedOrder(150);
        Invoice invoice = invoiceService.raise(order.getId(), DAY, "admin");
        dispatchService.dispatch(order.getId(), "admin");
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(88);
        return invoice;
    }

    @Test
    void aCreditNoteCancelsTheInvoiceAndBringsThePacketsBack() {
        Invoice invoice = dispatchedInvoice();

        CreditNote note = creditNotes.issue(invoice.getId(), DAY, "Goods returned damaged", "admin");

        assertThat(note.getCreditNoteNumber()).isEqualTo("CN/2026-27/0001");
        assertThat(note.getInvoiceId()).isEqualTo(invoice.getId());
        assertThat(note.getTaxablePaise()).isEqualTo(750_000);
        assertThat(note.getCgstPaise()).isEqualTo(18_750);
        assertThat(note.getSgstPaise()).isEqualTo(18_750);
        assertThat(note.getTotalPaise()).isEqualTo(787_500);
        assertThat(invoiceRepository.findById(invoice.getId()).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(238);          // 88 + 150 back
        StockMovement back = stock.ledgerFor(f.murukku.getId()).get(3);
        assertThat(back.getMovementType()).isEqualTo(MovementType.RETURN);
        assertThat(back.getSourceType()).isEqualTo(SourceType.SALES_RETURN);
        assertThat(back.getSourceId()).isEqualTo(note.getId());
        assertThat(back.getQtySigned()).isEqualTo(150);
        assertThat(stock.ledgerFor(f.murukku.getId())).hasSize(4);              // the old rows were not touched
        assertThat(stock.reconcile()).isEmpty();
    }

    @Test
    void theInvoiceNoLongerCountsAsOutstanding() {
        Invoice invoice = dispatchedInvoice();
        assertThat(receivables.customerOutstanding(retailerA.getId())).isEqualTo(787_500);

        creditNotes.issue(invoice.getId(), DAY, "Goods returned", "admin");

        assertThat(receivables.customerOutstanding(retailerA.getId())).isZero();
        assertThat(receivables.openInvoices(retailerA.getId())).isEmpty();
    }

    @Test
    void aCancelledInvoiceCannotReceiveAPayment() {
        Invoice invoice = dispatchedInvoice();
        creditNotes.issue(invoice.getId(), DAY, "Goods returned", "admin");

        assertThatThrownBy(() -> receivables.recordPayment(retailerA.getId(), DAY, PaymentMethod.CASH, null, "admin",
                List.of(new AllocationRequest(invoice.getId(), 1_000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CANCELLED");
    }

    @Test
    void aSecondCreditNoteForTheSameInvoiceIsRefused() {
        Invoice invoice = dispatchedInvoice();
        creditNotes.issue(invoice.getId(), DAY, "Goods returned", "admin");

        assertThatThrownBy(() -> creditNotes.issue(invoice.getId(), DAY, "Again", "admin"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already CANCELLED");
    }

    @Test
    void anInvoiceWithPaymentsCannotBeCreditedUntilRefunded() {
        Invoice invoice = dispatchedInvoice();
        receivables.recordPayment(retailerA.getId(), DAY, PaymentMethod.CASH, "R-1", "admin",
                List.of(new AllocationRequest(invoice.getId(), 400_000)));

        assertThatThrownBy(() -> creditNotes.issue(invoice.getId(), DAY, "Goods returned", "admin"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("refund the payment");
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(88);           // nothing changed
    }

    @Test
    void onlyDispatchedGoodsCanBeReturned() {
        produce();
        SalesOrder order = approvedOrder(150);
        Invoice invoice = invoiceService.raise(order.getId(), DAY, "admin");     // invoiced, NOT dispatched

        assertThatThrownBy(() -> creditNotes.issue(invoice.getId(), DAY, "Changed mind", "admin"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dispatched");
    }

    @Test
    void aReasonIsRequiredAndTheDateCannotPrecedeTheInvoice() {
        Invoice invoice = dispatchedInvoice();

        assertThatThrownBy(() -> creditNotes.issue(invoice.getId(), DAY, " ", "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> creditNotes.issue(invoice.getId(), DAY.minusDays(1), "Returned", "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("before the invoice");
        assertThatThrownBy(() -> creditNotes.issue(invoice.getId(), null, "Returned", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> creditNotes.issue(99_999, DAY, "Returned", "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");
    }

    @Test
    void creditNoteNumbersAreGaplessAcrossInvoices() {
        Invoice first = dispatchedInvoice();
        SalesOrder second = approvedOrder(20);
        Invoice secondInvoice = invoiceService.raise(second.getId(), DAY, "admin");
        dispatchService.dispatch(second.getId(), "admin");

        CreditNote one = creditNotes.issue(first.getId(), DAY, "Returned", "admin");
        CreditNote two = creditNotes.issue(secondInvoice.getId(), DAY, "Returned", "admin");

        assertThat(one.getCreditNoteNumber()).isEqualTo("CN/2026-27/0001");
        assertThat(two.getCreditNoteNumber()).isEqualTo("CN/2026-27/0002");
    }

    @Test
    void theCreditNoteIsAudited() {
        Invoice invoice = dispatchedInvoice();

        creditNotes.issue(invoice.getId(), DAY, "Goods returned damaged", "admin9");

        AuditEntry entry = audit.forEntity("INVOICE", invoice.getId()).stream()
                .filter(e -> e.action().equals("CREDIT_NOTE_ISSUED")).findFirst().orElseThrow();
        assertThat(entry.actor()).isEqualTo("admin9");
        assertThat(entry.details()).contains("CN/2026-27/0001").contains("INV/2026-27/0001").contains("damaged");
    }

    @Test
    void aCreditNoteRowCannotBeChangedAfterwards() {
        Invoice invoice = dispatchedInvoice();
        creditNotes.issue(invoice.getId(), DAY, "Goods returned", "admin");

        assertThatThrownBy(() -> jdbc.update("UPDATE credit_note SET total_paise = 1, taxable_paise = 1, "
                + "cgst_paise = 0, sgst_paise = 0, igst_paise = 0"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void salesReportsShowTheInvoiceAndTheCreditNoteAndNetToZero() {
        Invoice invoice = dispatchedInvoice();
        creditNotes.issue(invoice.getId(), DAY, "Goods returned", "admin");
        ReportPeriod day = new ReportPeriod(DAY, DAY);

        ReportTable register = reports.salesRegister(day);
        assertThat(register.rows()).hasSize(2);
        assertThat(register.rows().get(0).get(1)).isEqualTo("INV/2026-27/0001");
        assertThat(register.rows().get(0).get(9)).isEqualTo(787_500L);
        assertThat(register.rows().get(1).get(1)).isEqualTo("CN/2026-27/0001");
        assertThat(register.rows().get(1).get(9)).isEqualTo(-787_500L);
        assertThat(register.totals().get(9)).isEqualTo(0L);

        ReportTable byCustomer = reports.customerWiseSales(day);
        assertThat(byCustomer.rows().get(0).get(1)).isEqualTo(1L);               // one invoice counted
        assertThat(byCustomer.rows().get(0).get(4)).isEqualTo(0L);

        ReportTable byProduct = reports.productWiseSales(day);
        assertThat(byProduct.rows().get(0).get(2)).isEqualTo(0L);                // 150 sold, 150 returned
        assertThat(byProduct.rows().get(0).get(3)).isEqualTo(0L);

        ReportTable gst = reports.gstSummary(day);
        assertThat(gst.totals().get(2)).isEqualTo(0L);
        assertThat(gst.totals().get(6)).isEqualTo(0L);
    }

    @Test
    void aCreditNoteInALaterPeriodIsShownThereAsNegative() {
        Invoice invoice = dispatchedInvoice();
        creditNotes.issue(invoice.getId(), DAY.plusDays(10), "Returned later", "admin");

        ReportTable invoiceMonth = reports.salesRegister(new ReportPeriod(DAY, DAY));
        ReportTable creditMonth = reports.salesRegister(new ReportPeriod(DAY.plusDays(10), DAY.plusDays(10)));

        assertThat(invoiceMonth.totals().get(9)).isEqualTo(787_500L);            // the invoice stays in its own period
        assertThat(creditMonth.totals().get(9)).isEqualTo(-787_500L);            // the credit note lands in its period
    }
}
