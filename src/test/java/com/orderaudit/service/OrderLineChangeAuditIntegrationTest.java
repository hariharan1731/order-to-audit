package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * Audit finding H2: an admin editing an order line OVERWRITES the quantity and price the salesman entered.
 * The line cannot keep both, so the append-only audit log must: who changed it, and from what to what.
 */
@SpringBootTest
@Testcontainers
@Transactional
class OrderLineChangeAuditIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired WebApplicationContext context;
    @Autowired ItemService itemService;
    @Autowired CustomerService customerService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired PriceService priceService;
    @Autowired OrderService orderService;
    @Autowired AuditService audit;

    MockMvc mvc;
    Item product;
    SalesOrder order;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        product = itemService.create("Audit Line Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        adjustments.recordOpening(product.getId(), 1_000, null, "Opening stock", "admin");
        priceService.setListPrice(product.getId(), 5_000);
        Customer customer = customerService.create("Audit Line Customer", null, "33", null);
        order = orderService.submit(customer.getId(), "ravi", null, null,
                List.of(new OrderLineRequest(product.getId(), 150)));
    }

    private List<AuditEntry> lineChanges() {
        return audit.forEntity("ORDER", order.getId()).stream()
                .filter(e -> e.action().equals("ORDER_LINE_CHANGED")).toList();
    }

    @Test
    void anEditRecordsWhoChangedWhatFromWhatToWhat() {
        orderService.changeLine(order.getId(), product.getId(), 120, 4_800, "admin1");

        List<AuditEntry> changes = lineChanges();
        assertThat(changes).hasSize(1);
        AuditEntry change = changes.get(0);
        assertThat(change.actor()).isEqualTo("admin1");
        assertThat(change.details()).contains("Audit Line Murukku").contains("quantity 150 -> 120")
                .contains("unit price 5000 -> 4800");
        assertThat(orderService.linesOf(order.getId()).get(0).getQuantity()).isEqualTo(120);   // the line itself is overwritten
    }

    @Test
    void everyEditIsKeptSoTheWholeHistoryCanBeReplayed() {
        orderService.changeLine(order.getId(), product.getId(), 120, 5_000, "admin1");
        orderService.changeLine(order.getId(), product.getId(), 100, 4_500, "admin2");

        List<AuditEntry> changes = lineChanges();
        assertThat(changes).extracting(AuditEntry::actor).containsExactly("admin1", "admin2");
        assertThat(changes.get(0).details()).contains("quantity 150 -> 120");
        assertThat(changes.get(1).details()).contains("quantity 120 -> 100").contains("unit price 5000 -> 4500");
    }

    @Test
    void savingTheSameValuesAgainLogsNothing() {
        orderService.changeLine(order.getId(), product.getId(), 150, 5_000, "admin1");

        assertThat(lineChanges()).isEmpty();
    }

    @Test
    void aRefusedEditLogsNothingAndChangesNothing() {
        assertThatThrownBy(() -> orderService.changeLine(order.getId(), product.getId(), 0, 5_000, "admin1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderService.changeLine(order.getId(), product.getId(), 100, -1, "admin1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderService.changeLine(order.getId(), product.getId(), 100, 5_000, " "))
                .isInstanceOf(IllegalArgumentException.class);

        orderService.approve(order.getId(), "admin1");
        assertThatThrownBy(() -> orderService.changeLine(order.getId(), product.getId(), 100, 5_000, "admin1"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(lineChanges()).isEmpty();
        assertThat(orderService.linesOf(order.getId()).get(0).getQuantity()).isEqualTo(150);
    }

    @Test
    void withoutALoggedInUserTheOlderFourArgumentCallIsLoggedAsSystem() {
        orderService.changeLine(order.getId(), product.getId(), 140, 5_000);

        assertThat(lineChanges()).hasSize(1);
        assertThat(lineChanges().get(0).actor()).isEqualTo("system");
    }

    @Test
    void theWebsiteRecordsTheLoggedInAdminAsTheActor() throws Exception {
        var result = mvc.perform(post("/admin/orders/" + order.getId() + "/lines/" + product.getId())
                        .with(user("admin1").roles("ADMIN")).with(csrf())
                        .param("quantity", "88").param("priceRupees", "45.00"))
                .andExpect(status().is3xxRedirection()).andReturn();

        assertThat(result.getFlashMap().get("message")).isEqualTo("Line updated.");
        List<AuditEntry> changes = lineChanges();
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).actor()).isEqualTo("admin1");
        assertThat(changes.get(0).details()).contains("quantity 150 -> 88").contains("unit price 5000 -> 4500");
    }
}
