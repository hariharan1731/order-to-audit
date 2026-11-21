package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.PurchaseLineSpec;
import com.orderaudit.domain.SalesOrder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The printable invoice for Retailer A's official order: 150 packets, Rs 7,500 + 187.50 + 187.50 = Rs 7,875. */
@SpringBootTest
@Testcontainers
@Transactional
class InvoicePdfIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired WebApplicationContext context;
    @Autowired ItemService itemService;
    @Autowired RecipeService recipeService;
    @Autowired PackConfigService packConfigService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired SupplierService supplierService;
    @Autowired CustomerService customerService;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired InvoiceService invoiceService;
    @Autowired PurchaseService purchaseService;
    @Autowired ProductionService productionService;
    @Autowired StockService stock;

    MockMvc mvc;
    Invoice invoice;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        BenchmarkFixture f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService,
                packConfigService, adjustments, supplierService);
        // OFFICIAL steps 1-3: buy 60 kg flour, then the production run adds 188 good packets -> 50 + 188 = 238.
        LocalDate day = LocalDate.of(2026, 11, 21);
        purchaseService.record(f.supplier.getId(), day, "PO-1",
                List.of(new PurchaseLineSpec(f.flour.getId(), 60_000, new BigDecimal("4.5"))), "admin");
        productionService.complete(f.config.getId(), BigDecimal.ONE, day, 188, 6,
                List.of(new ActualLine(f.oil.getId(), 10_500), new ActualLine(f.pouch.getId(), 194),
                        new ActualLine(f.carton.getId(), 9)),
                List.of(new OverheadInput("Labour", 120_000), new OverheadInput("Gas and power", 60_000)), "production");
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(238);
        Customer retailerA = customerService.create("Retailer A", null, "33", null);
        priceService.setListPrice(f.murukku.getId(), 5_000);
        SalesOrder order = orderService.submit(retailerA.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), 150)));
        orderService.approve(order.getId(), "admin1");
        invoice = invoiceService.raise(order.getId(), day, "admin1");
    }

    private String textOf(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        return new PdfTextExtractor(reader).getTextFromPage(1);
    }

    @Test
    void rendererProducesARealPdf() {
        byte[] pdf = com.orderaudit.report.InvoicePdfRenderer.render(invoice, invoiceService.linesOf(invoice.getId()));
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void pdfShowsTheOfficialGstBreakup() throws Exception {
        String text = textOf(com.orderaudit.report.InvoicePdfRenderer.render(invoice,
                invoiceService.linesOf(invoice.getId())));
        assertThat(text).contains(invoice.getInvoiceNumber()).contains("Retailer A").contains("7,500.00")
                .contains("187.50").contains("7,875.00").contains("CGST").contains("SGST");
    }

    @Test
    void endpointReturnsAnAttachmentForAdmin() throws Exception {
        var response = mvc.perform(get("/admin/invoices/" + invoice.getId() + "/pdf").with(user("admin1").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader("Content-Disposition")).contains("attachment").contains(".pdf");
        assertThat(new String(response.getContentAsByteArray(), 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void salesmanCannotDownloadAnInvoice() throws Exception {
        mvc.perform(get("/admin/invoices/" + invoice.getId() + "/pdf").with(user("ravi").roles("SALESMAN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownInvoiceIsNotFound() throws Exception {
        mvc.perform(get("/admin/invoices/999999/pdf").with(user("admin1").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }
}
