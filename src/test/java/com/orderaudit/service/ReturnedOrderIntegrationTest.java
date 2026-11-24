package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.OrderLineRequest;
import com.orderaudit.domain.OrderStatus;
import com.orderaudit.domain.SalesOrder;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Audit finding M8: after a credit note cancels the invoice, the order still says Dispatched (it was) but is also
 * marked Returned, and it no longer counts in the salesman's customer-wise sales. Invoice is Rs 7,875.00.
 */
@SpringBootTest
@Testcontainers
@Transactional
class ReturnedOrderIntegrationTest {

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
    @Autowired OrderQueryService queries;

    MockMvc mvc;
    Item product;
    Customer shop;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        product = itemService.create("Return Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 10_000, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
        shop = customerService.create("Returning Shop", null, "33", null);
    }

    private SalesOrder dispatchedOrder() {
        SalesOrder order = orderService.submit(shop.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(product.getId(), 150)));
        orderService.approve(order.getId(), "admin");
        invoiceService.raise(order.getId(), DAY, "admin");
        dispatchService.dispatch(order.getId(), "admin");
        return order;
    }

    private OrderQueryService.OrderSummary summaryOf(long orderId) {
        return queries.view(orderId).summary();
    }

    @Test
    void aNormalDispatchedOrderIsNotMarkedReturned() {
        SalesOrder order = dispatchedOrder();
        assertThat(summaryOf(order.getId()).returned()).isFalse();
    }

    @Test
    void aCreditNoteMarksTheOrderReturnedButItStaysDispatched() {
        SalesOrder order = dispatchedOrder();
        creditNotes.issue(invoiceIdOf(order), DAY, "Goods returned", "admin");

        var summary = summaryOf(order.getId());
        assertThat(summary.returned()).isTrue();
        assertThat(summary.status()).isEqualTo(OrderStatus.DISPATCHED);
    }

    private long invoiceIdOf(SalesOrder order) {
        return invoiceService.forOrder(order.getId()).getId();
    }

    @Test
    void theOrderScreensShowTheReturnedBadge() throws Exception {
        SalesOrder order = dispatchedOrder();
        String before = mvc.perform(get("/admin/orders/" + order.getId()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(before).doesNotContain("Returned");

        creditNotes.issue(invoiceIdOf(order), DAY, "Goods returned", "admin");

        String admin = mvc.perform(get("/admin/orders/" + order.getId()).with(user("admin").roles("ADMIN")))
                .andReturn().getResponse().getContentAsString();
        String desk = mvc.perform(get("/admin/orders?status=DISPATCHED").with(user("admin").roles("ADMIN")))
                .andReturn().getResponse().getContentAsString();
        String salesOrder = mvc.perform(get("/sales/orders/" + order.getId()).with(user("ravi").roles("SALESMAN")))
                .andReturn().getResponse().getContentAsString();
        String salesList = mvc.perform(get("/sales/orders").with(user("ravi").roles("SALESMAN")))
                .andReturn().getResponse().getContentAsString();
        assertThat(admin).contains("Returned").contains("DISPATCHED");
        assertThat(desk).contains("Returned");
        assertThat(salesOrder).contains("Returned");
        assertThat(salesList).contains("Returned");
    }

    @Test
    void aReturnedOrderNoLongerCountsInCustomerWiseSales() {
        SalesOrder order = dispatchedOrder();
        assertThat(OrderQueryService.customerWise(queries.search("ravi", null, null, null, null, null)))
                .extracting(OrderQueryService.CustomerSales::billedPaise).containsExactly(787_500L);

        creditNotes.issue(invoiceIdOf(order), DAY, "Goods returned", "admin");

        assertThat(OrderQueryService.customerWise(queries.search("ravi", null, null, null, null, null))).isEmpty();
    }
}
