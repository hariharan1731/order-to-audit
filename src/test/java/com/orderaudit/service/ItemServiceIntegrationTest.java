package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.Unit;
import com.orderaudit.repository.ItemRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test against a real PostgreSQL. @Transactional on the class rolls every test back,
 * so tests never leave data behind for each other.
 */
@SpringBootTest
@Testcontainers
@Transactional
class ItemServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    ItemService service;

    @Autowired
    ItemRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void savesAndReloadsBenchmarkItems() {
        // OFFICIAL: finished item sells at GST 5%. HSN is left empty (not supplied).
        Item saved = service.create("Murukku 200 g", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        Item reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getName()).isEqualTo("Murukku 200 g");
        assertThat(reloaded.getBaseUnit()).isEqualTo(Unit.PACKET);
        assertThat(reloaded.getGstRatePercent()).isEqualByComparingTo("5");
        assertThat(reloaded.isActive()).isTrue();
    }

    @Test
    void listsActiveItemsOfOneTypeSortedByName() {
        service.create("Oil", ItemType.RAW, Unit.MILLILITRE, null, BigDecimal.ZERO);
        service.create("Flour", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);
        service.create("Pouch 200 g", ItemType.PACKAGING, Unit.PIECE, null, BigDecimal.ZERO);

        List<Item> raw = service.activeItemsOfType(ItemType.RAW);

        assertThat(raw).extracting(Item::getName).containsExactly("Flour", "Oil");
    }

    @Test
    void databaseItselfRejectsDuplicateNamesIgnoringCase() {
        repository.saveAndFlush(new Item("Flour", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO));

        assertThatThrownBy(() ->
                repository.saveAndFlush(new Item("FLOUR", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseItselfRejectsAnUnknownItemType() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO item (name, item_type, base_unit) VALUES ('Bad', 'MAGIC', 'GRAM')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
