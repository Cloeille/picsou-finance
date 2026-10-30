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
import java.util.function.IntPredicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #174: commit 2839c94 renumbered migrations that 1.1.0 databases had already applied
 * (V80, V81, V86-V88 became V93-V99). These tests build those databases from the real files under
 * their old names, then start them on the current files the way the application does.
 *
 * <p>The old numbering never existed as one tree that Flyway could run (V80 had three files), so
 * each legacy line is the set a deployed instance actually ran: V1-V79 plus that line's files.
 *
 * <p>Issue #265: the 1.1.0 merge did the same to files the 1.0.x line had applied (V38, V51,
 * V54-V64). That line is rebuilt as release 1.0.18 shipped it.
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

    /**
     * Release 1.0.18 (#265): the 1.0.x line before the 1.1.0 merge moved its files up. These
     * files kept their content; V51 and old V55-V58 changed only in comments, so they come from
     * the 1.0.18 copies under legacy-migrations/1.0.18.
     */
    private static final Map<String, String> RELEASE_1_0_18_RENAMED = Map.ofEntries(
        Map.entry("V94__backfill_tr_crypto_transaction_tickers.sql", "V38__backfill_tr_crypto_transaction_tickers.sql"),
        Map.entry("V56__wallet_ethereum_to_evm.sql", "V54__wallet_ethereum_to_evm.sql"),
        Map.entry("V61__bourse_direct_session.sql", "V59__bourse_direct_session.sql"),
        Map.entry("V62__bourse_direct_integration.sql", "V60__bourse_direct_integration.sql"),
        Map.entry("V63__harden_bourse_direct_sync.sql", "V61__harden_bourse_direct_sync.sql"),
        Map.entry("V64__backfill_bourse_direct_valuations.sql", "V62__backfill_bourse_direct_valuations.sql"),
        Map.entry("V65__constrain_bourse_direct_sync_errors.sql", "V63__constrain_bourse_direct_sync_errors.sql"),
        Map.entry("V96__backfill_trade_republic_valuations.sql", "V64__backfill_trade_republic_valuations.sql"));

    /** Versions 1.0.18 shipped under the number and content they still have. */
    private static final IntPredicate UNCHANGED_SINCE_1_0_18 = v -> v <= 32 || v == 36 || v == 37
        || v == 50 || v == 52 || v == 53 || (v >= 66 && v <= 80 && v != 72) || v == 86 || v == 87;

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
    void aRelease1018DatabaseKeepsItsAppliedRowsAndRunsThe110OnlyMigrations() throws Exception {
        String url = createDatabase("release_1_0_18");
        int files = migrateLegacy(url, UNCHANGED_SINCE_1_0_18, RELEASE_1_0_18_RENAMED, "/legacy-migrations/1.0.18");
        List<String> applied = query(url, "SELECT installed_rank || ' ' || description FROM flyway_schema_history "
            + "WHERE version IN ('38', '51', '54', '55', '56', '57', '58', '59', '60', '61', '62', '63', '64') "
            + "ORDER BY installed_rank");

        upgrade(url).migrate();

        assertThat(files).isEqualTo(65);
        assertThat(applied).hasSize(13);
        assertThat(upgrade(url).info().pending()).isEmpty();
        assertThat(query(url, "SELECT installed_rank || ' ' || description FROM flyway_schema_history "
            + "WHERE version IN ('94', '51', '56', '57', '58', '59', '60', '61', '62', '63', '64', '65', '96') "
            + "ORDER BY installed_rank")).isEqualTo(applied);
        assertThat(query(url, "SELECT version || ' ' || description || ' ' || success FROM flyway_schema_history "
            + "WHERE version IN ('38', '54', '55', '72') ORDER BY version::int")).containsExactly(
            "38 budget categorization foundation true",
            "54 oauth2 authorization server true",
            "55 account hidden true",
            "72 account external unique add provider true");
    }

    @Test
    void aRelease1018DatabaseKeepsItsAccountsAndMergesOnlyGenuineDuplicates() throws Exception {
        String url = createDatabase("release_1_0_18_accounts");
        migrateLegacy(url, UNCHANGED_SINCE_1_0_18, RELEASE_1_0_18_RENAMED, "/legacy-migrations/1.0.18");
        execute(url,
            "INSERT INTO family_member (id, display_name) VALUES (900, 'Owner')",
            "INSERT INTO account (id, name, provider, external_account_id, is_manual, member_id) VALUES "
                + "(10, 'Bank A current', 'Bank A', '12345', false, 900), "
                + "(11, 'Bank B current', 'Bank B', '12345', false, 900), "
                + "(12, 'Manual', 'Bank B', '12345', true, 900), "
                + "(13, 'Bank C duplicate', 'Bank C', '67890', false, 900), "
                + "(14, 'Bank C', 'Bank C', '67890', false, 900)",
            "INSERT INTO transaction (account_id, date, description, amount) VALUES "
                + "(10, '2026-01-01', 'a', 1), (11, '2026-01-01', 'b', 2), (12, '2026-01-01', 'c', 3), "
                + "(13, '2026-01-01', 'd', 4), (14, '2026-01-02', 'e', 5)",
            "INSERT INTO balance_snapshot (account_id, date, balance, invested_amount) VALUES "
                + "(10, '2026-01-01', 10, 0), (11, '2026-01-01', 11, 0), (12, '2026-01-01', 12, 0), "
                + "(13, '2026-01-01', 13, 0), (13, '2026-01-02', 13, 0), (14, '2026-01-01', 14, 0)");

        upgrade(url).migrate();

        assertThat(upgrade(url).info().pending()).isEmpty();
        assertThat(query(url, "SELECT id FROM account WHERE member_id = 900 ORDER BY id"))
            .containsExactly("10", "11", "12", "14");
        assertThat(query(url, "SELECT account_id || ' ' || description FROM transaction ORDER BY description"))
            .containsExactly("10 a", "11 b", "12 c", "14 d", "14 e");
        assertThat(query(url, "SELECT account_id || ' ' || date || ' ' || balance::int FROM balance_snapshot "
            + "ORDER BY account_id, date")).containsExactly(
            "10 2026-01-01 10", "11 2026-01-01 11", "12 2026-01-01 12",
            "14 2026-01-01 14", "14 2026-01-02 13");
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

    /**
     * Applies V1-V79, with V47 as 1.1.0 shipped it, and the line's files under the names they had
     * before 2839c94.
     */
    private static void migrateLegacy(String url, Map<String, String> line) throws Exception {
        migrateLegacy(url, version -> version <= 79 && version != 47, line, "/legacy-migrations/1.1.0");
    }

    /**
     * Applies the current files whose version is {@code unchanged}, the {@code renamed} ones under
     * their legacy names, and the files in the {@code legacyResources} directory as they are.
     * Returns how many files the legacy tree holds.
     */
    private static int migrateLegacy(String url, IntPredicate unchanged, Map<String, String> renamed,
                                     String legacyResources) throws Exception {
        Path legacy = Files.createTempDirectory("legacy-migrations");
        try (Stream<Path> files = Files.list(resourceDirectory("/db/migration"))) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (unchanged.test(version(name))) {
                    Files.copy(file, legacy.resolve(name));
                } else if (renamed.containsKey(name)) {
                    Files.copy(file, legacy.resolve(renamed.get(name)));
                }
            }
        }
        if (legacyResources != null) {
            try (Stream<Path> files = Files.list(resourceDirectory(legacyResources))) {
                for (Path file : files.toList()) {
                    Files.copy(file, legacy.resolve(file.getFileName()));
                }
            }
        }
        Flyway.configure()
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("filesystem:" + legacy)
            .load()
            .migrate();
        try (Stream<Path> files = Files.list(legacy)) {
            return (int) files.count();
        }
    }

    private static Path resourceDirectory(String name) throws URISyntaxException {
        return Path.of(LegacyMigrationRenumberingTest.class.getResource(name).toURI());
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

    private static void execute(String url, String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        }
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
