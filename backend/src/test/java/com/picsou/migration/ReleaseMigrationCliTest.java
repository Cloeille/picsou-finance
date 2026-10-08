package com.picsou.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIf("dockerAvailable")
class ReleaseMigrationCliTest {

    static {
        System.setProperty("api.version", "1.44");
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException("Docker is required but unavailable");
        }
        return available;
    }

    @Test
    void migratesFreshDatabaseOnceAndVerifyIsReadOnly() throws Exception {
        String url = createDatabase("fresh");

        assertThat(run("migrate", url)).isZero();
        int before = historyRows(url);
        assertThat(run("verify", url)).isZero();
        assertThat(run("migrate", url)).isZero();

        assertThat(historyRows(url)).isEqualTo(before);
    }

    @Test
    void rejectsMissingLowerMigrationEvenWhenHigherVersionsAreApplied() throws Exception {
        String url = createDatabase("missing_lower");
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").outOfOrder(true).load().migrate();
        execute(url, "DELETE FROM flyway_schema_history WHERE version = '100'");

        assertThat(run("verify", url)).isNotZero();
    }

    @Test
    void appliesGenuinelyMissingV100OutOfOrderAndPreservesDataOnRepeat() throws Exception {
        String url = createDatabase("missing_v100");
        assertThat(run("migrate", url)).isZero();
        int completeHistory = historyRows(url);
        replaceAccountTypeWithoutCreditCard(url);
        execute(url, "DELETE FROM flyway_schema_history WHERE version = '100'");
        execute(url, "CREATE TABLE release_test_marker (id INTEGER PRIMARY KEY, value TEXT); "
            + "INSERT INTO release_test_marker VALUES (1, 'preserved')");

        assertThat(run("verify", url)).isNotZero();
        assertThat(run("migrate", url)).isZero();
        assertThat(historyRowsForVersion(url, "100")).isEqualTo(1);
        assertThat(run("verify", url)).isZero();
        assertThat(run("migrate", url)).isZero();
        assertThat(historyRows(url)).isEqualTo(completeHistory);
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT value FROM release_test_marker WHERE id = 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("preserved");
        }
    }

    @Test
    void rejectsAppliedMigrationWithChangedChecksum() throws Exception {
        String url = createDatabase("checksum");
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").outOfOrder(true).load().migrate();
        execute(url, "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '100'");

        assertThat(run("verify", url)).isNotZero();
    }

    @Test
    void rejectsHistoryForUntrackedDdl() throws Exception {
        String url = createDatabase("untracked");
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").outOfOrder(true).load().migrate();
        execute(url, "INSERT INTO flyway_schema_history "
            + "(installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) "
            + "SELECT max(installed_rank) + 1, '999', 'untracked ddl', 'SQL', 'V999__untracked_ddl.sql', "
            + "123, current_user, now(), 0, true FROM flyway_schema_history");

        assertThat(run("verify", url)).isNotZero();
    }

    @Test
    void rejectsMigrateWhenAnUntrackedTableCollidesWithPendingMigration() throws Exception {
        String url = createDatabase("untracked_collision");
        execute(url, "CREATE TABLE amex_session (id BIGINT PRIMARY KEY)");
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").baselineVersion("100").load().baseline();

        assertThat(run("migrate", url)).isNotZero();
        assertThat(run("verify", url)).isNotZero();
    }

    @Test
    void rejectsSchemaDriftWhenMigrationHistoryIsComplete() throws Exception {
        String url = createDatabase("missing_column");
        assertThat(run("migrate", url)).isZero();
        execute(url, "ALTER TABLE account DROP COLUMN payment_due_amount");

        assertThat(run("verify", url)).isNotZero();
    }

    @Test
    void rejectsMissingAccountTypeLabelEvenWhenMigrationHistoryIsComplete() throws Exception {
        String url = createDatabase("missing_enum_label");
        assertThat(run("migrate", url)).isZero();
        replaceAccountTypeWithoutCreditCard(url);

        assertThat(run("verify", url)).isNotZero();
    }

    @Test
    void rejectsHighBaseline() throws Exception {
        String url = createDatabase("high_baseline");
        assertThat(run("migrate", url)).isZero();
        execute(url, "DROP TABLE flyway_schema_history");
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").baselineVersion("999").load().baseline();
        int historyBefore = historyRows(url);

        assertThat(run("verify", url)).isNotZero();
        assertThat(run("migrate", url)).isNotZero();
        assertThat(historyRows(url)).isEqualTo(historyBefore);
    }

    @Test
    void requiresExplicitDatabaseEnvironmentAndExactlyOneKnownCommand() {
        assertThat(ReleaseMigrationCli.run(new String[]{"migrate"}, Map.of())).isNotZero();
        assertThat(ReleaseMigrationCli.run(new String[]{"unknown"}, Map.of())).isNotZero();
        assertThat(ReleaseMigrationCli.run(new String[]{"verify", "."}, Map.of())).isNotZero();
    }

    private static int run(String command, String url) {
        return ReleaseMigrationCli.run(new String[]{command}, Map.of(
            "SPRING_DATASOURCE_URL", url,
            "SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername(),
            "SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword()));
    }

    private static String createDatabase(String name) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
    }

    private static int historyRows(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM flyway_schema_history")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static int historyRowsForVersion(String url, String version) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT count(*) FROM flyway_schema_history WHERE version = ?")) {
            statement.setString(1, version);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }


    private static void replaceAccountTypeWithoutCreditCard(String url) throws Exception {
        List<String> labels = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT unnest(enum_range(NULL::account_type))::text")) {
            while (result.next()) {
                if (!"CREDIT_CARD".equals(result.getString(1))) {
                    labels.add(result.getString(1));
                }
            }
        }
        String values = labels.stream().map(label -> "'" + label.replace("'", "''") + "'")
            .collect(Collectors.joining(", "));
        execute(url, "BEGIN; ALTER TABLE account ALTER COLUMN type DROP DEFAULT; "
            + "ALTER TYPE account_type RENAME TO account_type_old; "
            + "CREATE TYPE account_type AS ENUM (" + values + "); "
            + "ALTER TABLE account ALTER COLUMN type TYPE account_type USING type::text::account_type; "
            + "ALTER TABLE account ALTER COLUMN type SET DEFAULT 'OTHER'; "
            + "DROP TYPE account_type_old; COMMIT");
    }

    private static void execute(String url, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
