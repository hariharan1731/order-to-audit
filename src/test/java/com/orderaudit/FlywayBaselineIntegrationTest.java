package com.orderaudit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test: starts the whole app against a REAL PostgreSQL (in Docker)
 * and proves Flyway ran V1 and the benchmark-related settings are correct.
 */
@SpringBootTest
@Testcontainers
class FlywayBaselineIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAppliedVersionOneSuccessfully() {
        Boolean ok = jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);
        assertThat(ok).isTrue();
    }

    @Test
    void toleranceDefaultsToThreePercent() {
        String value = jdbc.queryForObject(
                "SELECT setting_value FROM app_setting WHERE setting_key = 'variance_tolerance_pct'",
                String.class);
        assertThat(value).isEqualTo("3");
    }

    @Test
    void openingFinishedGoodsRateIsUnvaluedUntilSupplied() {
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM app_setting WHERE setting_key = 'opening_fg_rate_paise_per_packet'",
                Integer.class);
        String value = jdbc.queryForObject(
                "SELECT setting_value FROM app_setting WHERE setting_key = 'opening_fg_rate_paise_per_packet'",
                String.class);
        assertThat(rows).isEqualTo(1);
        assertThat(value).isNull();
    }
}
