package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.Customer;
import com.orderaudit.domain.Supplier;
import com.orderaudit.repository.CustomerRepository;
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

/** Real PostgreSQL. Each test is rolled back. GSTINs are FICTIONAL placeholders. */
@SpringBootTest
@Testcontainers
@Transactional
class PartyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    CustomerService customers;

    @Autowired
    SupplierService suppliers;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void savesRetailerAAndDistributorBWithTheirStates() {
        Customer a = customers.create("Retailer A", "33ABCDE1234F1Z5", "33", null);
        Customer b = customers.create("Distributor B", null, "29", null);

        assertThat(customerRepository.findById(a.getId()).orElseThrow().getStateCode()).isEqualTo("33");
        assertThat(customerRepository.findById(b.getId()).orElseThrow().getStateCode()).isEqualTo("29");
        assertThat(customers.activeList()).extracting(Customer::getName)
                .containsExactly("Distributor B", "Retailer A");
    }

    @Test
    void twoCustomersWithoutGstinAreAllowed() {
        customers.create("Walk-in One", null, "33", null);
        customers.create("Walk-in Two", null, "33", null);

        assertThat(customers.activeList()).hasSize(2);
    }

    @Test
    void savesAndReloadsASupplier() {
        Supplier s = suppliers.create("Flour Mill", "33ABCDE1234F1Z5", "33", "Salem");

        assertThat(suppliers.activeList()).extracting(Supplier::getName).containsExactly("Flour Mill");
        assertThat(s.getId()).isNotNull();
    }

    @Test
    void databaseRejectsDuplicateGstin() {
        customerRepository.saveAndFlush(new Customer("One", "33ABCDE1234F1Z5", "33", null));

        assertThatThrownBy(() ->
                customerRepository.saveAndFlush(new Customer("Two", "33ABCDE1234F1Z5", "33", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsGstinWithWrongStatePrefix() {
        assertThatThrownBy(() ->
                customerRepository.saveAndFlush(new Customer("Wrong", "29ABCDE1234F1Z5", "33", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsBadStateCode() {
        assertThatThrownBy(() ->
                customerRepository.saveAndFlush(new Customer("Bad", null, "TN", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void factoryIsInTamilNaduAndGstinIsNotYetSupplied() {
        String state = jdbc.queryForObject(
                "SELECT setting_value FROM app_setting WHERE setting_key = 'factory_state_code'", String.class);
        List<String> gstin = jdbc.queryForList(
                "SELECT setting_value FROM app_setting WHERE setting_key = 'factory_gstin'", String.class);

        assertThat(state).isEqualTo("33");
        assertThat(gstin).hasSize(1);
        assertThat(gstin.get(0)).isNull();
    }
}
