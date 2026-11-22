package com.orderaudit.seed;

import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.PackBasis;
import com.orderaudit.domain.PackLineSpec;
import com.orderaudit.domain.RecipeLineSpec;
import com.orderaudit.domain.Recipe;
import com.orderaudit.domain.Role;
import com.orderaudit.domain.Unit;
import com.orderaudit.service.CustomerService;
import com.orderaudit.service.ItemService;
import com.orderaudit.service.PackConfigService;
import com.orderaudit.service.PriceService;
import com.orderaudit.service.RecipeService;
import com.orderaudit.service.StockAdjustmentService;
import com.orderaudit.service.SupplierService;
import com.orderaudit.service.UserService;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Loads the OFFICIAL master data and opening stock (problem statement section 5) plus one demo login per role,
 * so a fresh database is usable straight away. Switched on with app.seed.enabled=true (the Docker setup does this).
 * It goes through the normal services, so every rule (validation, audit, append-only ledger) still applies,
 * and it does nothing if the data is already there, so restarting never duplicates anything.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final ItemService items;
    private final RecipeService recipes;
    private final PackConfigService packConfigs;
    private final StockAdjustmentService adjustments;
    private final SupplierService suppliers;
    private final CustomerService customers;
    private final PriceService prices;
    private final UserService users;
    private final String demoPassword;

    public DataSeeder(ItemService items, RecipeService recipes, PackConfigService packConfigs,
                      StockAdjustmentService adjustments, SupplierService suppliers, CustomerService customers,
                      PriceService prices, UserService users,
                      @Value("${app.seed.password:Demo@12345}") String demoPassword) {
        this.items = items;
        this.recipes = recipes;
        this.packConfigs = packConfigs;
        this.adjustments = adjustments;
        this.suppliers = suppliers;
        this.customers = customers;
        this.prices = prices;
        this.users = users;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (alreadySeeded()) {
            log.info("Seed data already present - skipping");
            return;
        }
        seedMasterData();
        seedUsers();
        log.info("Seed data loaded: official benchmark master data, opening stock and demo users");
    }

    private boolean alreadySeeded() {
        return items.activeItemsOfType(ItemType.RAW).stream().anyMatch(i -> i.getName().equals("Flour"));
    }

    private void seedMasterData() {
        BigDecimal zero = BigDecimal.ZERO;
        Item flour = items.create("Flour", ItemType.RAW, Unit.GRAM, null, zero);
        Item oil = items.create("Oil", ItemType.RAW, Unit.MILLILITRE, null, zero);
        Item spice = items.create("Spice mix", ItemType.RAW, Unit.GRAM, null, zero);
        Item pouch = items.create("Pouch 200 g", ItemType.PACKAGING, Unit.PIECE, null, zero);
        Item carton = items.create("Carton", ItemType.PACKAGING, Unit.PIECE, null, zero);
        Item murukku = items.create("Murukku 200 g", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));

        // OFFICIAL BOM per batch: flour 40 kg, oil 10 L, spice 1.2 kg, 200 pouches, 10 cartons of 20 packets.
        Recipe recipe = recipes.create("Murukku base", 40_000, null, List.of(
                new RecipeLineSpec(flour.getId(), 40_000),
                new RecipeLineSpec(oil.getId(), 10_000),
                new RecipeLineSpec(spice.getId(), 1_200)));
        packConfigs.create(murukku.getId(), recipe.getId(), 200, 20, List.of(
                new PackLineSpec(pouch.getId(), 1, PackBasis.PER_PACKET),
                new PackLineSpec(carton.getId(), 1, PackBasis.PER_CARTON)));

        // OFFICIAL opening stock. Rates are paise per gram / ml / piece. Finished goods have no supplied rate.
        adjustments.recordOpening(flour.getId(), 100_000, new BigDecimal("4.2"), "Opening stock", "seed");
        adjustments.recordOpening(oil.getId(), 30_000, new BigDecimal("14"), "Opening stock", "seed");
        adjustments.recordOpening(spice.getId(), 5_000, new BigDecimal("20"), "Opening stock", "seed");
        adjustments.recordOpening(pouch.getId(), 500, new BigDecimal("250"), "Opening stock", "seed");
        adjustments.recordOpening(carton.getId(), 30, new BigDecimal("1500"), "Opening stock", "seed");
        adjustments.recordOpening(murukku.getId(), 50, null, "Opening stock", "seed");

        prices.setListPrice(murukku.getId(), 5_000);                     // Rs 50 per packet

        suppliers.create("Flour Mill (demo)", null, "33", "Tiruchirappalli");
        // Fictional placeholder GSTINs - never real ones.
        customers.create("Retailer A", "33AAAAA1111A1Z5", "33", "Tiruchirappalli, Tamil Nadu");
        customers.create("Distributor B", "29BBBBB2222B1Z5", "29", "Bengaluru, Karnataka");
    }

    private void seedUsers() {
        users.create("ravi", "Ravi (Salesman)", demoPassword, Role.SALESMAN, "seed");
        // Second salesman, so the benchmark's "Salesman B" order is placed by a different person.
        users.create("suresh", "Suresh (Salesman)", demoPassword, Role.SALESMAN, "seed");
        users.create("admin", "Admin", demoPassword, Role.ADMIN, "seed");
        users.create("prod", "Production Supervisor", demoPassword, Role.PRODUCTION_SUPERVISOR, "seed");
        users.create("accounts", "Accountant", demoPassword, Role.ACCOUNTANT, "seed");
        users.create("owner", "Owner", demoPassword, Role.OWNER, "seed");
    }
}
