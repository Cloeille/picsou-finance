package com.picsou.migration;

import com.picsou.config.LegacyMigrationRenumberingCallback;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #174: commit 2839c94 renumbered migrations that 1.1.0 databases had already applied
 * (V80, V81, V86-V88 became V93-V99). These tests build those databases from the real files under
 * their old names, then start them on the current files the way the application does.
 *
 * <p>The old numbering never existed as one tree that Flyway could run (V80 had three files), so
 * each legacy line is the set a deployed instance actually ran: V1-V79 plus that line's files.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class LegacyMigrationRenumberingTest {

    static {
        System.setProperty("api.version", "1.44");
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && System.getenv("PICSOU_REQUIRE_DOCKER_TESTS") != null) {
            throw new IllegalStateException("Docker is required but unavailable");
        }
        return available;
    }

    /** The 1.1.0 branch after the security-profile work landed: old V80, V81, V86, V87, V88. */
    private static final Map<String, String> SECURITY_PROFILE_LINE = Map.ofEntries(
        Map.entry("V94__backfill_tr_crypto_transaction_tickers.sql", "V80__backfill_tr_crypto_transaction_tickers.sql"),
        Map.entry("V96__backfill_trade_republic_valuations.sql", "V81__backfill_trade_republic_valuations.sql"),
        Map.entry("V82__account_type_life_insurance_and_scpi.sql", "V82__account_type_life_insurance_and_scpi.sql"),
        Map.entry("V83__wealth_allocation.sql", "V83__wealth_allocation.sql"),
        Map.entry("V84__security_profile.sql", "V84__security_profile.sql"),
        Map.entry("V85__goal_recurring_investment.sql", "V85__goal_recurring_investment.sql"),
        Map.entry("V97__security_profile_isin.sql", "V86__security_profile_isin.sql"),
        Map.entry("V98__security_profile_status.sql", "V87__security_profile_status.sql"),
        Map.entry("V99__security_profile_fund_facts.sql", "V88__security_profile_fund_facts.sql"),
        Map.entry("V89__goal_allocation.sql", "V89__goal_allocation.sql"),
        Map.entry("V90__member_profile.sql", "V90__member_profile.sql"),
        Map.entry("V91__account_opened_at.sql", "V91__account_opened_at.sql"),
        Map.entry("V92__default_category_impots.sql", "V92__default_category_impots.sql"));

    /** The Fortuneo connector branch: old V80 and V81 are Fortuneo's, not the backfills. */
    private static final Map<String, String> FORTUNEO_LINE = Map.of(
        "V93__fortuneo_session.sql", "V80__fortuneo_session.sql",
        "V95__fortuneo_integration.sql", "V81__fortuneo_integration.sql");

    @Test
    void aFreshDatabaseMigratesToHeadUnderTheNewNumbers() throws Exception {
        String url = createDatabase("fresh");

        upgrade(url).migrate();

        assertThat(upgrade(url).info().pending()).isEmpty();
        assertThat(query(url, "SELECT version || ' ' || description FROM flyway_schema_history "
            + "WHERE version IN ('80', '81', '86', '87', '88') ORDER BY installed_rank")).containsExactly(
            "80 widen tr and degiro session tokens",
            "86 unbounded encrypted session tokens",
            "87 bourso fraud ack required",
            "88 transaction external id");
        assertThat(query(url, "SELECT version FROM flyway_schema_history "
            + "WHERE version IN ('93', '94', '95', '96', '97', '98', '99') ORDER BY version::int")).containsExactly(
            "93", "94", "95", "96", "97", "98", "99");
    }

    @Test
    void theSecurityProfileLineKeepsItsAppliedRowsAndMigratesToHead() throws Exception {
        String url = createDatabase("security_profile_line");
        migrateLegacy(url, SECURITY_PROFILE_LINE);
        List<String> applied = query(url, "SELECT installed_rank || ' ' || description FROM flyway_schema_history "
            + "WHERE version IN ('80', '81', '86', '87', '88') ORDER BY installed_rank");

        upgrade(url).migrate();

        assertThat(upgrade(url).info().pending()).isEmpty();
        // Same installed_rank under the new version: the row was renumbered, not re-run.
        assertThat(query(url, "SELECT installed_rank || ' ' || description FROM flyway_schema_history "
            + "WHERE version IN ('94', '96', '97', '98', '99') ORDER BY installed_rank")).isEqualTo(applied);
        assertThat(query(url, "SELECT version || ' ' || description FROM flyway_schema_history "
            + "WHERE version IN ('80', '81', '86', '87', '88') ORDER BY version::int")).containsExactly(
            "80 widen tr and degiro session tokens",
            "86 unbounded encrypted session tokens",
            "87 bourso fraud ack required",
            "88 transaction external id");
        assertThat(query(url, "SELECT version || ' ' || success FROM flyway_schema_history "
            + "WHERE version IN ('93', '95', '103') ORDER BY version::int")).containsExactly(
            "93 true", "95 true", "103 true");
    }

    @Test
    void theFortuneoLineKeepsItsAppliedRowsAndMigratesToHead() throws Exception {
        String url = createDatabase("fortuneo_line");
        migrateLegacy(url, FORTUNEO_LINE);
        List<String> applied = query(url, "SELECT installed_rank || ' ' || description FROM flyway_schema_history "
            + "WHERE version IN ('80', '81') ORDER BY installed_rank");

        upgrade(url).migrate();

        assertThat(upgrade(url).info().pending()).isEmpty();
        assertThat(query(url, "SELECT installed_rank || ' ' || description FROM flyway_schema_history "
            + "WHERE version IN ('93', '95') ORDER BY installed_rank")).isEqualTo(applied);
        assertThat(query(url, "SELECT version || ' ' || description FROM flyway_schema_history "
            + "WHERE version IN ('80', '81') ORDER BY version::int")).containsExactly(
            "80 widen tr and degiro session tokens");
        assertThat(query(url, "SELECT version || ' ' || success FROM flyway_schema_history "
            + "WHERE version IN ('94', '96', '103') ORDER BY version::int")).containsExactly(
            "94 true", "96 true", "103 true");
    }

    @Test
    void aSecondStartChangesNothing() throws Exception {
        String url = createDatabase("second_start");
        migrateLegacy(url, SECURITY_PROFILE_LINE);
        upgrade(url).migrate();
        String history = "SELECT installed_rank || ' ' || version || ' ' || script || ' ' || checksum "
            + "FROM flyway_schema_history ORDER BY installed_rank";
        List<String> before = query(url, history);

        MigrateResult second = upgrade(url).migrate();

        assertThat(second.migrationsExecuted).isZero();
        assertThat(query(url, history)).isEqualTo(before);
    }

    /** The application's Flyway: current files, out-of-order, and the renumbering callback. */
    private static Flyway upgrade(String url) {
        return Flyway.configure()
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .outOfOrder(true)
            .callbacks(new LegacyMigrationRenumberingCallback())
            .load();
    }

    /** Applies V1-V79 and the line's files under the names they had before 2839c94. */
    private static void migrateLegacy(String url, Map<String, String> line) throws Exception {
        Path current = Path.of(migrationDirectory());
        Path legacy = Files.createTempDirectory("legacy-migrations");
        try (Stream<Path> files = Files.list(current)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (version(name) <= 79) {
                    Files.copy(file, legacy.resolve(name));
                } else if (line.containsKey(name)) {
                    Files.copy(file, legacy.resolve(line.get(name)));
                }
            }
        }
        Flyway.configure()
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("filesystem:" + legacy)
            .load()
            .migrate();
    }

    private static java.net.URI migrationDirectory() throws URISyntaxException {
        return LegacyMigrationRenumberingTest.class.getResource("/db/migration").toURI();
    }

    private static int version(String fileName) {
        return Integer.parseInt(fileName.substring(1, fileName.indexOf("__")));
    }

    private static String createDatabase(String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
    }

    private static List<String> query(String url, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }
}
