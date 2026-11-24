package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.OrderLineRequest;
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
 * Audit finding M4: if the stock is gone by the time someone clicks Dispatch (for example a production run was
 * reversed after the invoice), they get the normal message on the order page, not a crash page.
 */
@SpringBootTest
@Testcontainers
@Transactional
class DispatchShortStockIntegrationTest {

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

    MockMvc mvc;
    Item product;
    SalesOrder order;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        product = itemService.create("Dispatch Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 200, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
        Customer retailer = customerService.create("Retailer A", null, "33", null);
        order = orderService.submit(retailer.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(product.getId(), 150)));
        orderService.approve(order.getId(), "admin");
        invoiceService.raise(order.getId(), DAY, "admin");
    }

    @Test
    void dispatchWithTooLittleStockShowsAMessageInsteadOfCrashing() throws Exception {
        adjustments.recordCorrection(product.getId(), -100, null, "Run reversed, goods gone", "admin");   // 100 left, 150 needed

        var result = mvc.perform(post("/admin/orders/" + order.getId() + "/dispatch")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/admin/orders/" + order.getId());
        assertThat(String.valueOf(result.getFlashMap().get("error"))).contains("Insufficient stock");
    }
}
