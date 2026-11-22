package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Invoice;
import com.orderaudit.domain.InvoiceStatus;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.Role;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The office screens through the real stack: invoices, credit notes, payments, masters, users, audit log. */
@SpringBootTest
@Testcontainers
@Transactional
class WebOfficeIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);

    @Autowired WebApplicationContext context;
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
    @Autowired StockService stock;
    @Autowired UserService userService;

    MockMvc mvc;
    BenchmarkFixture f;
    Customer retailerA;
    Invoice invoice;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
        productionService.complete(f.config.getId(), BigDecimal.ONE, DAY, 188, 6,
                List.of(new ActualLine(f.oil.getId(), 10_500), new ActualLine(f.pouch.getId(), 194),
                        new ActualLine(f.carton.getId(), 9)),
                List.of(new OverheadInput("Labour", 120_000), new OverheadInput("Gas and power", 60_000)), "production");
        retailerA = customerService.create("Retailer A", null, "33", null);
        priceService.setListPrice(f.murukku.getId(), 5_000);
        SalesOrder order = orderService.submit(retailerA.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(f.murukku.getId(), 150)));
        orderService.approve(order.getId(), "admin1");
        invoice = invoiceService.raise(order.getId(), DAY, "admin1");
        dispatchService.dispatch(order.getId(), "admin1");
    }

    private static RequestPostProcessor admin() {
        return user("admin1").roles("ADMIN");
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String flashOf(MockHttpServletRequestBuilder request, String key) throws Exception {
        var result = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn();
        Object value = result.getFlashMap().get(key);
        return value == null ? null : value.toString();
    }

    // ---- invoices and credit notes

    @Test
    void theInvoiceListAndDetailShowTheOfficialFigures() throws Exception {
        assertThat(body(get("/admin/invoices").with(admin()))).contains("INV/2026-27/0001").contains("Retailer A")
                .contains("Rs 7,875.00");

        String page = body(get("/admin/invoices/" + invoice.getId()).with(admin()));
        assertThat(page).contains("INV/2026-27/0001").contains("Rs 7,500.00").contains("Rs 187.50")
                .contains("CGST").contains("SGST").contains("Rs 7,875.00").contains("Issue credit note");
    }

    @Test
    void theInvoiceCanBeSharedByWhatsAppOrEmail() throws Exception {
        assertThat(com.orderaudit.web.InvoiceShare.message(invoice)).isEqualTo(
                "Tax invoice INV/2026-27/0001 dated " + invoice.getInvoiceDate().format(
                        java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
                        + " for Retailer A. Taxable value Rs 7,500.00, CGST + SGST Rs 375.00, total Rs 7,875.00."
                        + " The invoice PDF is attached.");

        String page = body(get("/admin/invoices/" + invoice.getId()).with(admin()));
        assertThat(page).contains("id=\"share-whatsapp\"")
                .contains("https://wa.me/?text=Tax%20invoice%20INV%2F2026-27%2F0001%20dated")
                .contains("total%20Rs%207%2C875.00")
                .contains("id=\"share-email\"")
                .contains("mailto:?subject=Tax%20invoice%20INV%2F2026-27%2F0001&amp;body=Tax%20invoice");
    }

    @Test
    void anUnknownInvoiceIsA404() throws Exception {
        mvc.perform(get("/admin/invoices/999999").with(admin())).andExpect(status().isNotFound());
    }

    @Test
    void aCreditNoteFromTheWebsiteCancelsTheInvoiceAndRestoresStock() throws Exception {
        String message = flashOf(post("/admin/invoices/" + invoice.getId() + "/credit-note").with(admin()).with(csrf())
                .param("creditDate", "2026-11-21").param("reason", "Returned damaged"), "message");

        assertThat(message).contains("CN/2026-27/0001");
        assertThat(invoiceService.get(invoice.getId()).getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(stock.balanceOf(f.murukku.getId())).isEqualTo(238);
        String page = body(get("/admin/invoices/" + invoice.getId()).with(admin()));
        assertThat(page).contains("Cancelled by credit note").contains("Returned damaged").doesNotContain("Issue credit note");
    }

    @Test
    void anInvoiceWithPaymentsShowsRefundFirstAndTheServiceRefuses() throws Exception {
        receivables.recordPaymentOldestFirst(retailerA.getId(), DAY, com.orderaudit.domain.PaymentMethod.CASH, "R1",
                "admin1", 100_000);

        assertThat(body(get("/admin/invoices/" + invoice.getId()).with(admin()))).contains("Refund the payment first");
        assertThat(flashOf(post("/admin/invoices/" + invoice.getId() + "/credit-note").with(admin()).with(csrf())
                .param("creditDate", "2026-11-21").param("reason", "x"), "error")).contains("refund the payment");
    }

    // ---- payments

    @Test
    void thePaymentsPageListsWhoOwesWhat() throws Exception {
        assertThat(body(get("/admin/payments").with(admin()))).contains("Retailer A").contains("Rs 7,875.00");
        assertThat(body(get("/admin/payments/customers/" + retailerA.getId()).with(admin())))
                .contains("INV/2026-27/0001").contains("Record payment");
    }

    @Test
    void recordingFourThousandRupeesLeavesTheOfficialOutstandingOf3875() throws Exception {
        String message = flashOf(post("/admin/payments/customers/" + retailerA.getId()).with(admin()).with(csrf())
                .param("amountRupees", "4000").param("method", "UPI").param("reference", "UPI-77")
                .param("paymentDate", "2026-11-21"), "message");

        assertThat(message).contains("Payment recorded");
        assertThat(receivables.customerOutstanding(retailerA.getId())).isEqualTo(387_500);   // OFFICIAL Rs 3,875
        assertThat(body(get("/admin/payments").with(admin()))).contains("Rs 3,875.00");
    }

    @Test
    void payingMoreThanIsOwedShowsAMessageAndChangesNothing() throws Exception {
        String error = flashOf(post("/admin/payments/customers/" + retailerA.getId()).with(admin()).with(csrf())
                .param("amountRupees", "9000").param("method", "CASH").param("paymentDate", "2026-11-21"), "error");

        assertThat(error).contains("more than the total outstanding");
        assertThat(receivables.customerOutstanding(retailerA.getId())).isEqualTo(787_500);
    }

    @Test
    void anAmountWithMoreThanTwoDecimalsIsRefused() throws Exception {
        assertThat(flashOf(post("/admin/payments/customers/" + retailerA.getId()).with(admin()).with(csrf())
                .param("amountRupees", "10.005").param("method", "CASH").param("paymentDate", "2026-11-21"), "error"))
                .isNotNull();
        assertThat(receivables.customerOutstanding(retailerA.getId())).isEqualTo(787_500);
    }

    // ---- masters

    @Test
    void anAdminAddsACustomerAndDuplicatesAreRefused() throws Exception {
        assertThat(flashOf(post("/admin/customers").with(admin()).with(csrf()).param("name", "Distributor B")
                .param("stateCode", "29").param("address", "Bengaluru"), "message")).isEqualTo("Customer added.");
        assertThat(body(get("/admin/customers").with(admin()))).contains("Distributor B").contains("Retailer A");

        assertThat(flashOf(post("/admin/customers").with(admin()).with(csrf()).param("name", "Distributor B")
                .param("stateCode", "29"), "error")).isNotNull();
        assertThat(flashOf(post("/admin/customers").with(admin()).with(csrf()).param("name", "Bad State")
                .param("stateCode", "XX"), "error")).isNotNull();
    }

    @Test
    void theAdminCanChangeTheSellingPrice() throws Exception {
        assertThat(body(get("/admin/prices").with(admin()))).contains("Murukku 200 g").contains("value=\"50.00\"");

        assertThat(flashOf(post("/admin/prices/" + f.murukku.getId()).with(admin()).with(csrf())
                .param("priceRupees", "55.25"), "message")).isEqualTo("Price saved.");

        assertThat(priceService.priceFor(retailerA.getId(), f.murukku.getId())).isEqualTo(5_525);
    }

    // ---- users and audit

    @Test
    void anAdminCreatesAUserWhoCanThenBeDisabledAndNeverSeesPasswords() throws Exception {
        assertThat(flashOf(post("/users").with(admin()).with(csrf()).param("username", "ravi2").param("displayName", "Ravi Two")
                .param("password", "murukku2026").param("role", "SALESMAN"), "message")).contains("ravi2");

        String page = body(get("/users").with(admin()));
        assertThat(page).contains("ravi2").contains("SALESMAN").doesNotContain("murukku2026").doesNotContain("bcrypt");

        long id = userService.all().stream().filter(u -> u.getUsername().equals("ravi2")).findFirst().orElseThrow().getId();
        assertThat(flashOf(post("/users/" + id + "/enabled").with(admin()).with(csrf()).param("enabled", "false"),
                "message")).isEqualTo("Account disabled.");
        assertThat(userService.all().stream().filter(u -> u.getId() == id).findFirst().orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void weakPasswordsAndDuplicateUsernamesAreRefused() throws Exception {
        userService.create("taken", "Taken", "murukku2026", Role.ACCOUNTANT, "admin1");

        assertThat(flashOf(post("/users").with(admin()).with(csrf()).param("username", "newbie").param("displayName", "N")
                .param("password", "short").param("role", "SALESMAN"), "error")).contains("at least 8");
        assertThat(flashOf(post("/users").with(admin()).with(csrf()).param("username", "TAKEN").param("displayName", "N")
                .param("password", "murukku2026").param("role", "SALESMAN"), "error")).contains("already taken");
    }

    @Test
    void theAuditPageShowsTheWholeSalesChain() throws Exception {
        String page = body(get("/audit").with(admin()));

        assertThat(page).contains("ORDER_APPROVED").contains("INVOICE_RAISED").contains("ORDER_DISPATCHED")
                .contains("PRODUCTION_POSTED").contains("admin1");
    }

    // ---- who may open what (real paths)

    @Test
    void onlyTheRightRolesOpenTheOfficeScreens() throws Exception {
        for (String path : new String[] {"/admin/invoices", "/admin/payments", "/admin/customers", "/admin/prices",
                "/users", "/audit", "/admin/orders"}) {
            mvc.perform(get(path).with(user("s").roles("SALESMAN"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("a").roles("ACCOUNTANT"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("p").roles("PRODUCTION_SUPERVISOR"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("o").roles("OWNER"))).andExpect(status().isOk());
            mvc.perform(get(path).with(admin())).andExpect(status().isOk());
        }
    }

    @Test
    void creditNotesAndPaymentsCannotBePostedByASalesman() throws Exception {
        mvc.perform(post("/admin/invoices/" + invoice.getId() + "/credit-note").with(user("s").roles("SALESMAN")).with(csrf())
                .param("creditDate", "2026-11-21").param("reason", "x")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/payments/customers/" + retailerA.getId()).with(user("s").roles("SALESMAN")).with(csrf())
                .param("amountRupees", "1").param("method", "CASH").param("paymentDate", "2026-11-21"))
                .andExpect(status().isForbidden());
        assertThat(invoiceService.get(invoice.getId()).getStatus()).isEqualTo(InvoiceStatus.ISSUED);
    }
}
