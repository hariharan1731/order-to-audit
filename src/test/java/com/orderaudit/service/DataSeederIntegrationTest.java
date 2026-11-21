package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.Role;
import com.orderaudit.repository.AppUserRepository;
import com.orderaudit.seed.DataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The seeder loads the official benchmark data once, and is safe to run twice. */
@SpringBootTest(properties = "app.seed.enabled=true")
@Testcontainers
@Transactional
class DataSeederIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired DataSeeder seeder;
    @Autowired ItemService itemService;
    @Autowired StockService stock;
    @Autowired AppUserRepository userRepository;
    @Autowired PasswordEncoder encoder;
    @Autowired CustomerService customerService;

    @Test
    void seederRunsOnStartupWithOfficialOpeningStock() {
        var flour = itemService.activeItemsOfType(ItemType.RAW).stream().filter(i -> i.getName().equals("Flour")).findFirst();
        assertThat(flour).isPresent();
        assertThat(stock.balanceOf(flour.get().getId())).isEqualTo(100_000);
        var fg = itemService.activeItemsOfType(ItemType.FINISHED).get(0);
        assertThat(stock.balanceOf(fg.getId())).isEqualTo(50);
    }

    @Test
    void oneDemoLoginExistsPerRoleWithAHashedPassword() {
        for (String name : new String[] {"ravi", "admin", "prod", "accounts", "owner"}) {
            var user = userRepository.findAll().stream().filter(u -> u.getUsername().equals(name)).findFirst();
            assertThat(user).as(name).isPresent();
            assertThat(user.get().getPasswordHash()).startsWith("{bcrypt}");
        }
        assertThat(userRepository.findAll().stream().map(u -> u.getRole()).distinct().toList())
                .contains(Role.SALESMAN, Role.ADMIN, Role.PRODUCTION_SUPERVISOR, Role.ACCOUNTANT, Role.OWNER);
    }

    @Test
    void runningTheSeederAgainDoesNotDuplicateAnything() throws Exception {
        int usersBefore = userRepository.findAll().size();
        seeder.run(null);
        assertThat(userRepository.findAll().size()).isEqualTo(usersBefore);
        assertThat(itemService.activeItemsOfType(ItemType.RAW)).hasSize(3);
    }
}
