package com.example.phonepayservice;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs this service's Flyway migrations on a real, EMPTY MySQL schema and lets Hibernate validate every entity against
 * the result - the one thing the H2-based tests cannot prove. Only runs when MIGRATION_TEST_DB_URL is set (CI's
 * "migrations" job does, with a throwaway MySQL service); locally:
 * MIGRATION_TEST_DB_URL=jdbc:mysql://localhost:3306/some_empty_db MIGRATION_TEST_DB_USER=root MIGRATION_TEST_DB_PASSWORD=... ./mvnw test -Dtest=MySqlMigrationsTest
 */
@DataJpaTest(properties = {
        "spring.datasource.url=${MIGRATION_TEST_DB_URL}",
        "spring.datasource.username=${MIGRATION_TEST_DB_USER:root}",
        "spring.datasource.password=${MIGRATION_TEST_DB_PASSWORD:}",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        // The app refuses to start without its API keys; these placeholders only let the context load.
        "bank.service.api-key=migration-test-placeholder",
        "internal.service.api-key=migration-test-placeholder"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "MIGRATION_TEST_DB_URL", matches = ".+")
class MySqlMigrationsTest {
    @Autowired
    private EntityManager entityManager;

    @Test
    void everyMigrationAppliesToAnEmptySchemaAndTheEntitiesMatchIt() {
        Number applied = (Number) entityManager.createNativeQuery(
                "select count(*) from flyway_schema_history_phonepe where success = 1 and version is not null").getSingleResult();
        // Baseline row has a NULL version in some Flyway setups; V1, V2... are the real migrations.
        assertTrue(applied.intValue() >= 2, "expected the migrations to have been applied, got " + applied);
        Number tables = (Number) entityManager.createNativeQuery(
                "select count(*) from information_schema.tables where table_schema = database() and table_name = 'transaction'").getSingleResult();
        assertEquals(1, tables.intValue());
    }
}
