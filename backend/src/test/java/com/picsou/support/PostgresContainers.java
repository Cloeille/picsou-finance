package com.picsou.support;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The one place that knows how a Picsou test talks to a throwaway PostgreSQL.
 *
 * <p>Every test class declares its own container ({@code @Container static final
 * PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer()}) so migration tests that
 * migrate to a specific version and seed rows never see another class's data. This type only
 * removes the copied plumbing around that container.
 */
public final class PostgresContainers {

    static final String IMAGE = "postgres:16-alpine";

    static {
        // docker-java otherwise negotiates down to API 1.32, which Engine >= 28 refuses, and that
        // reads as "no Docker": the guard below would then skip on a capable host. Surefire sets the
        // same value; this covers IDE and failsafe runs, which never read the surefire config.
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    private PostgresContainers() {}

    /** A fresh, unstarted container; the Testcontainers JUnit extension starts and closes it. */
    @SuppressWarnings("resource")
    public static PostgreSQLContainer<?> newContainer() {
        return new PostgreSQLContainer<>(IMAGE);
    }

    /**
     * Gate for {@link RequiresPostgres}. Evaluated as a JUnit {@code ExecutionCondition}, which
     * runs <em>before</em> the Testcontainers extension tries to start the container, so a
     * machine without Docker skips these classes while the rest of the suite stays runnable.
     *
     * <p>A skip is invisible in a green build, so CI sets {@code PICSOU_REQUIRE_DOCKER_TESTS=true}:
     * "no Docker" then becomes a hard failure instead of silently deleting the coverage.
     */
    public static boolean dockerAvailable() {
        boolean available = dockerDaemonReachable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException(
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found. "
                    + "The PostgreSQL Testcontainers tests cannot be skipped. Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    /** Plain reachability check, for opt-in classes that must skip (never fail) without Docker. */
    public static boolean dockerDaemonReachable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    /** Flyway pointed at the container with the application's migration location. */
    public static FluentConfiguration flyway(PostgreSQLContainer<?> container) {
        return Flyway.configure()
            .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
            .locations("classpath:db/migration");
    }

    public static void migrateTo(PostgreSQLContainer<?> container, String version) {
        flyway(container).target(version).load().migrate();
    }

    /** As {@link #migrateTo}, with out-of-order enabled the way {@code application.yml} does. */
    public static void migrateToOutOfOrder(PostgreSQLContainer<?> container, String version) {
        flyway(container).target(version).outOfOrder(true).load().migrate();
    }

    public static Connection connection(PostgreSQLContainer<?> container) throws SQLException {
        return DriverManager.getConnection(
            container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /**
     * Production wiring for a full-schema {@code @DataJpaTest}: Flyway owns the schema, Hibernate
     * only validates it. Loosening {@code ddl-auto} here would silently disable the point of
     * every test that uses it.
     */
    public static void registerValidatedDatasource(
            DynamicPropertyRegistry registry, PostgreSQLContainer<?> container) {
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.out-of-order", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
