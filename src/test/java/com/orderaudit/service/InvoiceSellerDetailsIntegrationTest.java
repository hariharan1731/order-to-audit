package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.Unit;
import com.orderaudit.report.InvoicePdfRenderer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Audit finding M10: the seller's name, GSTIN and state on the invoice PDF come from configuration. */
@SpringBootTest
@Testcontainers
@Transactional
class InvoiceSellerDetailsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired ItemService itemService;
    @Autowired CustomerService customerService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired InvoiceService invoiceService;

    Invoice invoice;

    @BeforeEach
    void setUp() {
        Item product = itemService.create("Seller Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 1_000, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
        Customer retailer = customerService.create("Retailer A", null, "33", null);
        SalesOrder order = orderService.submit(retailer.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(product.getId(), 150)));
        orderService.approve(order.getId(), "admin");
        invoice = invoiceService.raise(order.getId(), LocalDate.of(2026, 11, 21), "admin");
    }

    private String text(byte[] pdf) throws Exception {
        return new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
    }

    @Test
    void theDefaultSellerIsTheDemoTamilNaduSupplier() throws Exception {
        String text = text(InvoicePdfRenderer.render(invoice, invoiceService.linesOf(invoice.getId())));
        assertThat(text).contains("Murukku Foods (demo)").contains("Tamil Nadu (33)");
    }

    @Test
    void aConfiguredSellerIsPrintedInsteadOfTheDemoOne() throws Exception {
        var seller = new InvoicePdfRenderer.Seller("Kerala Snacks Ltd", "32BBBBB1111B1Z6", "Kerala (32)");
        String text = text(InvoicePdfRenderer.render(invoice, invoiceService.linesOf(invoice.getId()), seller));
        assertThat(text).contains("Kerala Snacks Ltd").contains("32BBBBB1111B1Z6").contains("Kerala (32)")
                .doesNotContain("Tamil Nadu (33)");
        // Amounts come from the stored invoice, so changing the seller never changes the money.
        assertThat(text).contains("7,875.00");
    }
}
