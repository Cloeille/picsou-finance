package com.picsou.migration;

import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;

import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@RequiresPostgres
class FutureMigrationStartupTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class));

    @Test
    void refusesToStartOnADatabaseWithAnAppliedVersionTheImageDoesNotShip() throws Exception {
        String url = createMigratedDatabase("future");
        execute(url, "INSERT INTO flyway_schema_history "
            + "(installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) "
            + "SELECT max(installed_rank) + 1, '9999', 'from a newer image', 'SQL', "
            + "'V9999__from_a_newer_image.sql', 1, 'picsou', 1, true FROM flyway_schema_history");

        contextRunner.withPropertyValues(datasourceProperties(url)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .rootCause()
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("9999")
                .hasMessageContaining("not resolved locally");
        });
    }

    @Test
    void startsOnAFullyMigratedDatabaseAndLeavesTheHistoryAlone() throws Exception {
        String url = createMigratedDatabase("current");
        int before = historyRows(url);

        contextRunner.withPropertyValues(datasourceProperties(url))
            .run(context -> assertThat(context).hasNotFailed());

        assertThat(historyRows(url)).isEqualTo(before);
    }

    private static String[] datasourceProperties(String url) {
        return new String[] {
            "spring.datasource.url=" + url,
            "spring.datasource.username=" + POSTGRES.getUsername(),
            "spring.datasource.password=" + POSTGRES.getPassword()
        };
    }

    private static String createMigratedDatabase(String name) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
        PostgresContainers.flyway(POSTGRES)
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .outOfOrder(true)
            .load()
            .migrate();
        return url;
    }

    private static int historyRows(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM flyway_schema_history")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void execute(String url, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
