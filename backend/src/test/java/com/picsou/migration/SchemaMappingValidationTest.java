package com.picsou.migration;

import com.picsou.model.AccountType;
import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates every JPA entity against the real, Flyway-migrated PostgreSQL schema.
 *
 * <p><b>Why this exists.</b> The rest of the suite runs on H2, which accepts mappings
 * PostgreSQL rejects — so an entity can drift from its migration while every test passes, and
 * the application then refuses to boot. That is not hypothetical: it was added after
 * {@code country CHAR(2)} shipped through a green build and failed startup with <em>"wrong
 * column type encountered in column [country]: found [bpchar], but expecting [varchar(2)]"</em>.
 * Flyway had already applied the migration by then, so the container crash-looped against an
 * upgraded database.
 *
 * <p>Booting the persistence unit with {@code ddl-auto=validate} against real PostgreSQL runs
 * the same check the application runs at startup, across every entity at once.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@RequiresPostgres
class SchemaMappingValidationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresContainers.registerValidatedDatasource(registry, POSTGRES);
    }

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Test
    void everyEntityMatchesTheMigratedSchema() {
        // Reaching this point is the assertion: building the EntityManagerFactory under
        // ddl-auto=validate throws SchemaManagementException on the first mismatched column,
        // so the context would have failed before the test body ran.
        assertThat(entityManagerFactory).isNotNull();
        assertThat(entityManagerFactory.getMetamodel().getEntities()).isNotEmpty();
    }

    /**
     * {@code ddl-auto=validate} checks column types, not the labels of a native enum — so a
     * constant added to {@link AccountType} without the matching {@code ALTER TYPE} passes every
     * test on H2 and then fails in production the first time someone saves an account of that
     * type. This is the check that catches the missing migration.
     */
    @Test
    void everyAccountTypeExistsInThePostgresEnum() {
        List<String> labels = entityManagerFactory.createEntityManager()
            .createNativeQuery("SELECT unnest(enum_range(NULL::account_type))::text", String.class)
            .getResultList();

        assertThat(labels).containsAll(Stream.of(AccountType.values()).map(Enum::name).toList());
    }
}
