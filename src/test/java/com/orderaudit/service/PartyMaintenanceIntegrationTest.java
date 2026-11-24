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
import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.PaymentMethod;
import com.orderaudit.domain.PurchaseLineSpec;
import com.orderaudit.domain.SalesOrder;
import com.orderaudit.domain.Supplier;
import com.orderaudit.domain.Unit;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Audit finding M6: customers and suppliers can be corrected and switched off. Nothing old is touched: invoices
 * keep the details they were raised with, and a customer who still owes money stays reachable for payments.
 */
@SpringBootTest
@Testcontainers
@Transactional
class PartyMaintenanceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);
    private static final String GSTIN_TN = "33AAAAA0000A1Z5";
    private static final String GSTIN_KA = "29AAAAA0000A1Z5";

    @Autowired WebApplicationContext context;
    @Autowired ItemService itemService;
    @Autowired CustomerService customers;
    @Autowired SupplierService suppliers;
    @Autowired StockAdjustmentService adjustments;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired InvoiceService invoiceService;
    @Autowired ReceivablesService receivables;
    @Autowired PurchaseService purchases;
    @Autowired AuditService audit;

    MockMvc mvc;
    Item product;
    Customer retailerA;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        product = itemService.create("Party Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 10_000, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
        retailerA = customers.create("Retailer A", GSTIN_TN, "33", "Old address");
    }

    private Invoice invoiceFor(Customer c) {
        SalesOrder order = orderService.submit(c.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(product.getId(), 150)));
        orderService.approve(order.getId(), "admin");
        return invoiceService.raise(order.getId(), DAY, "admin");
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder b) {
        return b.with(user("admin").roles("ADMIN")).with(csrf());
    }

    private String flash(MockHttpServletRequestBuilder b, String key) throws Exception {
        Object v = mvc.perform(asAdmin(b)).andExpect(status().is3xxRedirection()).andReturn().getFlashMap().get(key);
        return v == null ? null : v.toString();
    }

    // ---- correcting a customer

    @Test
    void aCustomerCanBeCorrectedAndTheChangeIsAudited() {
        customers.update(retailerA.getId(), "Retailer A Stores", GSTIN_KA, "29", "New address");

        Customer saved = customers.all().stream().filter(c -> c.getId().equals(retailerA.getId())).findFirst().orElseThrow();
        assertThat(saved.getName()).isEqualTo("Retailer A Stores");
        assertThat(saved.getStateCode()).isEqualTo("29");
        assertThat(saved.getGstin()).isEqualTo(GSTIN_KA);
        assertThat(saved.getAddress()).isEqualTo("New address");
        assertThat(audit.recent(1000)).anyMatch(e -> e.action().equals("CUSTOMER_UPDATED")
                && e.details().contains("name: Retailer A -> Retailer A Stores") && e.details().contains("state: 33 -> 29"));
    }

    @Test
    void anUnchangedSaveLeavesNoAuditRow() {
        long before = audit.recent(1000).stream().filter(e -> e.action().equals("CUSTOMER_UPDATED")).count();
        customers.update(retailerA.getId(), "Retailer A", GSTIN_TN, "33", "Old address");
        assertThat(audit.recent(1000).stream().filter(e -> e.action().equals("CUSTOMER_UPDATED")).count()).isEqualTo(before);
    }

    @Test
    void aCustomerKeepsItsOwnNameAndGstinButCannotTakeAnotherCustomers() {
        customers.update(retailerA.getId(), "RETAILER A", GSTIN_TN, "33", null);          // same name, other case: fine
        Customer other = customers.create("Distributor B", GSTIN_KA, "29", null);

        assertThatThrownBy(() -> customers.update(other.getId(), "retailer a", GSTIN_KA, "29", null))
                .isInstanceOf(DuplicatePartyException.class);
        assertThatThrownBy(() -> customers.update(other.getId(), "Distributor B", GSTIN_TN, "33", null))
                .isInstanceOf(DuplicatePartyException.class);
    }

    @Test
    void badDetailsAreRefusedOnEdit() {
        assertThatThrownBy(() -> customers.update(retailerA.getId(), " ", null, "33", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> customers.update(retailerA.getId(), "Retailer A", GSTIN_TN, "29", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("GSTIN starts with");
        assertThatThrownBy(() -> customers.update(999_999L, "Ghost", null, "33", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInvoiceAlreadyRaisedKeepsTheOldDetails() {
        Invoice invoice = invoiceFor(retailerA);

        customers.update(retailerA.getId(), "Retailer A Stores", GSTIN_KA, "29", "New address");

        Invoice again = invoiceService.get(invoice.getId());
        assertThat(again.getCustomerName()).isEqualTo("Retailer A");
        assertThat(again.getCustomerState()).isEqualTo("33");
        assertThat(again.isIntraState()).isTrue();
        assertThat(again.getTotalPaise()).isEqualTo(787_500);
    }

    // ---- switching a customer off

    @Test
    void aSwitchedOffCustomerCannotPlaceNewOrdersButIsStillListedInAll() {
        customers.setActive(retailerA.getId(), false);

        assertThat(customers.activeList()).extracting(Customer::getName).doesNotContain("Retailer A");
        assertThat(customers.all()).extracting(Customer::getName).contains("Retailer A");
        assertThatThrownBy(() -> orderService.submit(retailerA.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(product.getId(), 10))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not active");
        assertThat(audit.recent(1000)).anyMatch(e -> e.action().equals("CUSTOMER_DEACTIVATED"));
    }

    @Test
    void aSwitchedOffCustomerWhoStillOwesMoneyCanStillPay() throws Exception {
        Invoice invoice = invoiceFor(retailerA);
        customers.setActive(retailerA.getId(), false);

        String debtors = mvc.perform(asAdmin(get("/admin/payments"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(debtors).contains("Retailer A");
        mvc.perform(asAdmin(get("/admin/payments/customers/" + retailerA.getId()))).andExpect(status().isOk());
        receivables.recordPayment(retailerA.getId(), DAY, PaymentMethod.CASH, null, "admin",
                List.of(new AllocationRequest(invoice.getId(), 400_000)));
        assertThat(receivables.customerOutstanding(retailerA.getId())).isEqualTo(387_500);
    }

    @Test
    void aCustomerCanBeSwitchedBackOn() {
        customers.setActive(retailerA.getId(), false);
        customers.setActive(retailerA.getId(), true);
        assertThat(customers.activeList()).extracting(Customer::getName).contains("Retailer A");
        orderService.submit(retailerA.getId(), "ravi", null, null, List.of(new OrderLineRequest(product.getId(), 10)));
    }

    @Test
    void switchingTheSameWayTwiceIsHarmlessAndAuditedOnce() {
        customers.setActive(retailerA.getId(), false);
        customers.setActive(retailerA.getId(), false);
        assertThat(audit.recent(1000).stream().filter(e -> e.action().equals("CUSTOMER_DEACTIVATED")).count()).isEqualTo(1);
    }

    // ---- suppliers

    @Test
    void aSupplierCanBeCorrectedAndSwitchedOff() {
        Supplier supplier = suppliers.create("Flour Mill", null, "33", null);
        Item flour = itemService.create("Party Flour", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);

        suppliers.update(supplier.getId(), "Flour Mill Pvt Ltd", GSTIN_TN, "33", "Mill Road");
        assertThat(suppliers.all()).extracting(Supplier::getName).contains("Flour Mill Pvt Ltd");
        assertThat(audit.recent(1000)).anyMatch(e -> e.action().equals("SUPPLIER_UPDATED")
                && e.details().contains("name: Flour Mill -> Flour Mill Pvt Ltd"));

        purchases.record(supplier.getId(), DAY, "PO-1",
                List.of(new PurchaseLineSpec(flour.getId(), 1_000, new BigDecimal("4.5"))), "admin");
        suppliers.setActive(supplier.getId(), false);
        assertThat(suppliers.activeList()).extracting(Supplier::getName).doesNotContain("Flour Mill Pvt Ltd");
        assertThatThrownBy(() -> purchases.record(supplier.getId(), DAY, "PO-2",
                List.of(new PurchaseLineSpec(flour.getId(), 1_000, new BigDecimal("4.5"))), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not active");
        assertThat(audit.recent(1000)).anyMatch(e -> e.action().equals("SUPPLIER_DEACTIVATED"));
    }

    @Test
    void supplierDuplicatesAreRefusedOnEdit() {
        Supplier a = suppliers.create("Supplier One", GSTIN_TN, "33", null);
        Supplier b = suppliers.create("Supplier Two", GSTIN_KA, "29", null);
        assertThatThrownBy(() -> suppliers.update(b.getId(), "supplier one", GSTIN_KA, "29", null))
                .isInstanceOf(DuplicatePartyException.class);
        assertThatThrownBy(() -> suppliers.update(b.getId(), "Supplier Two", GSTIN_TN, "33", null))
                .isInstanceOf(DuplicatePartyException.class);
        suppliers.update(a.getId(), "Supplier One", GSTIN_TN, "33", "Same details, new address");
    }

    // ---- the screens

    @Test
    void theCustomerPageEditsAndSwitchesOff() throws Exception {
        assertThat(flash(post("/admin/customers/" + retailerA.getId()).param("name", "Retailer A Stores")
                .param("stateCode", "33").param("gstin", GSTIN_TN).param("address", "Anna Nagar"), "message"))
                .isEqualTo("Customer saved.");
        String page = mvc.perform(asAdmin(get("/admin/customers"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Retailer A Stores").contains("Anna Nagar").contains("Edit details");

        assertThat(flash(post("/admin/customers/" + retailerA.getId() + "/active").param("active", "false"), "message"))
                .contains("switched off");
        String after = mvc.perform(asAdmin(get("/admin/customers"))).andReturn().getResponse().getContentAsString();
        assertThat(after).contains("Switched off").contains("Switch on");
    }

    @Test
    void aBadEditShowsTheMessageAndChangesNothing() throws Exception {
        String error = flash(post("/admin/customers/" + retailerA.getId()).param("name", "Retailer A")
                .param("stateCode", "9"), "error");
        assertThat(error).contains("State code");
        assertThat(flash(post("/admin/customers/999999/active").param("active", "false"), "error")).contains("does not exist");
    }

    @Test
    void theSupplierPageWorks() throws Exception {
        Supplier supplier = suppliers.create("Oil Traders", null, "33", null);
        assertThat(flash(post("/admin/suppliers/" + supplier.getId()).param("name", "Oil Traders Ltd")
                .param("stateCode", "33"), "message")).isEqualTo("Supplier saved.");
        assertThat(flash(post("/admin/suppliers/" + supplier.getId() + "/active").param("active", "false"), "message"))
                .contains("switched off");
        String page = mvc.perform(asAdmin(get("/admin/suppliers"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Oil Traders Ltd").contains("Switched off");
        assertThat(flash(post("/admin/suppliers").param("name", "New Supplier").param("stateCode", "33"), "message"))
                .isEqualTo("Supplier added.");
    }

    @Test
    void onlyTheOfficeMayChangeParties() throws Exception {
        mvc.perform(post("/admin/customers/" + retailerA.getId()).with(user("ravi").roles("SALESMAN")).with(csrf())
                .param("name", "Hacked").param("stateCode", "33")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/suppliers/1/active").with(user("acc").roles("ACCOUNTANT")).with(csrf())
                .param("active", "false")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/suppliers").with(user("ravi").roles("SALESMAN"))).andExpect(status().isForbidden());
    }
}
