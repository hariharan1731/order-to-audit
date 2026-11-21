package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.Unit;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Master-data changes (items, prices, customers, suppliers) leave an audit trail naming who made them. */
@SpringBootTest
@Testcontainers
@Transactional
class MasterDataAuditIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired ItemService itemService;
    @Autowired PriceService priceService;
    @Autowired CustomerService customerService;
    @Autowired SupplierService supplierService;
    @Autowired AuditService audit;

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String name) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(name, "x", "ROLE_ADMIN"));
    }

    private Item finishedItem(String name) {
        return itemService.create(name, ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
    }

    @Test
    void creatingAnItemRecordsWhoDidIt() {
        loginAs("admin1");
        Item item = itemService.create("Salt", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);
        var trail = audit.forEntity("ITEM", item.getId());
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).actor()).isEqualTo("admin1");
        assertThat(trail.get(0).action()).isEqualTo("ITEM_CREATED");
        assertThat(trail.get(0).details()).contains("Salt");
    }

    @Test
    void withoutALoginTheActorIsSystem() {
        Item item = itemService.create("Sugar", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);
        assertThat(audit.forEntity("ITEM", item.getId()).get(0).actor()).isEqualTo("system");
    }

    @Test
    void aRefusedItemLeavesNoAuditRow() {
        loginAs("admin1");
        itemService.create("Salt", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);
        long before = audit.recent(1000).stream().filter(e -> e.action().equals("ITEM_CREATED")).count();
        assertThatThrownBy(() -> itemService.create("salt", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO))
                .isInstanceOf(DuplicateItemException.class);
        long after = audit.recent(1000).stream().filter(e -> e.action().equals("ITEM_CREATED")).count();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void changingAPriceRecordsTheOldAndNewValue() {
        loginAs("admin1");
        Item murukku = finishedItem("Murukku 200 g");
        priceService.setListPrice(murukku.getId(), 5_000);
        priceService.setListPrice(murukku.getId(), 5_500);
        List<com.orderaudit.service.AuditEntry> trail = audit.forEntity("ITEM", murukku.getId()).stream()
                .filter(e -> e.action().equals("PRICE_SET")).toList();
        assertThat(trail).hasSize(2);
        assertThat(trail.stream().map(AuditEntry::details).toList())
                .anyMatch(d -> d.contains("none -> 5000")).anyMatch(d -> d.contains("5000 -> 5500"));
    }

    @Test
    void aSpecialPriceChangeIsRecordedOnTheCustomer() {
        loginAs("admin1");
        Item murukku = finishedItem("Murukku 200 g");
        Customer c = customerService.create("Retailer A", null, "33", null);
        priceService.setCustomerPrice(c.getId(), murukku.getId(), 4_500);
        var entries = audit.forEntity("CUSTOMER", c.getId());
        assertThat(entries).extracting(AuditEntry::action).contains("CUSTOMER_CREATED", "CUSTOMER_PRICE_SET");
        assertThat(entries.stream().filter(e -> e.action().equals("CUSTOMER_PRICE_SET")).findFirst().orElseThrow().details())
                .contains("none -> 4500");
    }

    @Test
    void creatingASupplierIsAudited() {
        loginAs("prod");
        var supplier = supplierService.create("Flour Mill", null, "33", null);
        var trail = audit.forEntity("SUPPLIER", supplier.getId());
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).actor()).isEqualTo("prod");
    }

    @Test
    void anInvalidPriceLeavesNoAuditRow() {
        loginAs("admin1");
        Item murukku = finishedItem("Murukku 200 g");
        assertThatThrownBy(() -> priceService.setListPrice(murukku.getId(), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(audit.forEntity("ITEM", murukku.getId()).stream().filter(e -> e.action().equals("PRICE_SET")))
                .isEmpty();
    }
}
