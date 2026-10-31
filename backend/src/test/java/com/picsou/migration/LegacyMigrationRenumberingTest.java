package com.picsou.migration;

import com.picsou.config.LegacyMigrationRenumberingCallback;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.exception.FlywayValidateException;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #174: commit 2839c94 renumbered migrations that 1.1.0 databases had already applied
 * (V80, V81, V86-V88 became V93-V99). Issue #265: merging main into 1.1.0 renumbered migrations
 * that the 1.0.11-1.0.18 releases had applied (V38, V54-V64) and edited comments in V51 and
 * V55-V58. These tests build those databases from the real files under their old names, then
 * start them on the current files the way the application does.
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

    /** Versions 1.0.18 shipped under the same file as today. */
    private static final Set<Integer> RELEASE_1_0_18_UNCHANGED = Set.of(
        1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
        28, 29, 30, 31, 32, 36, 37, 50, 52, 53, 66, 67, 68, 69, 70, 71, 73, 74, 75, 76, 77, 78, 79, 80,
        86, 87);

    /** Versions 1.0.11 shipped under the same file as today (its V38 is the moved backfill). */
    private static final Set<Integer> RELEASE_1_0_11_UNCHANGED = Set.of(
        1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
        28, 29, 30, 31, 32, 36, 37, 50);

    /** Versions 1.0.15 shipped under the same file as today: no V51 or V64 yet. */
    private static final Set<Integer> RELEASE_1_0_15_UNCHANGED = Set.of(
        1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
        28, 29, 30, 31, 32, 36, 37, 50, 52, 53);

    /** Issue #265: today's file -> the name 1.0.18 shipped it under, content unchanged. */
    private static final Map<String, String> RELEASE_1_0_18_MOVED = Map.of(
        "V94__backfill_tr_crypto_transaction_tickers.sql", "V38__backfill_tr_crypto_transaction_tickers.sql",
        "V56__wallet_ethereum_to_evm.sql", "V54__wallet_ethereum_to_evm.sql",
        "V61__bourse_direct_session.sql", "V59__bourse_direct_session.sql",
        "V62__bourse_direct_integration.sql", "V60__bourse_direct_integration.sql",
        "V63__harden_bourse_direct_sync.sql", "V61__harden_bourse_direct_sync.sql",
        "V64__backfill_bourse_direct_valuations.sql", "V62__backfill_bourse_direct_valuations.sql",
        "V65__constrain_bourse_direct_sync_errors.sql", "V63__constrain_bourse_direct_sync_errors.sql",
        "V96__backfill_trade_republic_valuations.sql", "V64__backfill_trade_republic_valuations.sql");

    /** The 1.0.18 files whose comments changed since; copied verbatim from the 1.0.18 tag. */
    private static final List<String> RELEASE_1_0_18_EDITED = List.of(
        "V51__requisition_oauth_state.sql",
        "V55__wallet_evm_account_name.sql",
        "V56__persistent_session_previous_token.sql",
        "V57__ibkr_connection.sql",
        "V58__account_cash_balance.sql");

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
    void aRelease1018DatabaseKeepsItsAppliedRowsAndMigratesToTheFreshSchema() throws Exception {
        String url = createDatabase("release_1_0_18");
        migrateRelease(url, RELEASE_1_0_18_UNCHANGED, RELEASE_1_0_18_MOVED, RELEASE_1_0_18_EDITED);
        // The applied checksums of the issue #265 log: V38 2050086936 is the 1.0.18 backfill.
        assertThat(query(url, "SELECT version || ' ' || checksum FROM flyway_schema_history "
            + "WHERE version IN ('38', '51', '64') ORDER BY version::int")).containsExactly(
            "38 2050086936", "51 1952390840", "64 664629777");
        String history = "SELECT installed_rank || ' ' || description FROM flyway_schema_history "
            + "WHERE installed_rank <= 65 ORDER BY installed_rank";
        List<String> applied = query(url, history);

        upgrade(url).migrate();

        assertThat(upgrade(url).info().pending()).isEmpty();
        // Same installed_rank and description: every 1.0.18 row was kept, none re-ran.
        assertThat(applied).hasSize(65);
        assertThat(query(url, history)).isEqualTo(applied);
        assertThat(query(url, "SELECT version FROM flyway_schema_history "
            + "WHERE installed_rank <= 65 AND description IN ('backfill tr crypto transaction tickers', "
            + "'wallet ethereum to evm', 'ibkr connection', 'backfill trade republic valuations') "
            + "ORDER BY installed_rank")).containsExactly("94", "56", "59", "96");
        // The 1.1.0 migrations that sat behind the old rows' versions ran for real.
        assertThat(query(url, "SELECT version || ' ' || description || ' ' || success FROM flyway_schema_history "
            + "WHERE version IN ('38', '54', '55') ORDER BY version::int")).containsExactly(
            "38 budget categorization foundation true",
            "54 oauth2 authorization server true",
            "55 account hidden true");
        assertThat(schema(url)).isEqualTo(schema(freshDatabase("release_1_0_18_reference")));
    }

    @Test
    void aRelease1011DatabaseMovesItsBackfillAndMigratesToHead() throws Exception {
        String url = createDatabase("release_1_0_11");
        migrateRelease(url, RELEASE_1_0_11_UNCHANGED,
            Map.of("V94__backfill_tr_crypto_transaction_tickers.sql", "V38__backfill_tr_crypto_transaction_tickers.sql"),
            List.of());

        upgrade(url).migrate();

        assertThat(upgrade(url).info().pending()).isEmpty();
        assertThat(query(url, "SELECT version || ' ' || description FROM flyway_schema_history "
            + "WHERE version IN ('38', '94') ORDER BY version::int")).containsExactly(
            "38 budget categorization foundation",
            "94 backfill tr crypto transaction tickers");
        assertThat(schema(url)).isEqualTo(schema(freshDatabase("release_1_0_11_reference")));
    }

    @Test
    void aRelease1015DatabaseWithoutV51AndV64MigratesToTheFreshSchema() throws Exception {
        String url = createDatabase("release_1_0_15");
        Map<String, String> moved = new HashMap<>(RELEASE_1_0_18_MOVED);
        moved.remove("V96__backfill_trade_republic_valuations.sql");
        migrateRelease(url, RELEASE_1_0_15_UNCHANGED, moved, RELEASE_1_0_18_EDITED.subList(1, 5));

        upgrade(url).migrate();

        assertThat(upgrade(url).info().pending()).isEmpty();
        assertThat(query(url, "SELECT version || ' ' || description FROM flyway_schema_history "
            + "WHERE version IN ('51', '96') ORDER BY version::int")).containsExactly(
            "51 requisition oauth state", "96 backfill trade republic valuations");
        assertThat(schema(url)).isEqualTo(schema(freshDatabase("release_1_0_15_reference")));
    }

    @Test
    void aRowWithAnUnknownChecksumIsLeftForFlywayToReject() throws Exception {
        String url = createDatabase("unknown_checksum");
        migrateRelease(url, RELEASE_1_0_18_UNCHANGED, RELEASE_1_0_18_MOVED, RELEASE_1_0_18_EDITED);
        execute(url, "UPDATE flyway_schema_history SET checksum = 1 WHERE version = '57'");

        assertThatThrownBy(() -> upgrade(url).migrate()).isInstanceOf(FlywayValidateException.class);

        assertThat(query(url, "SELECT version || ' ' || description || ' ' || checksum FROM flyway_schema_history "
            + "WHERE description = 'ibkr connection'")).containsExactly("57 ibkr connection 1");
        // V55 -> V57 is blocked by the unknown row still holding V57, so it stays where it was.
        assertThat(query(url, "SELECT version FROM flyway_schema_history "
            + "WHERE description = 'wallet evm account name'")).containsExactly("55");
    }

    @Test
    void aRelease1018DatabaseWithDuplicateExternalIdsStopsBeforeV47() throws Exception {
        String url = createDatabase("release_duplicates");
        migrateRelease(url, RELEASE_1_0_18_UNCHANGED, RELEASE_1_0_18_MOVED, RELEASE_1_0_18_EDITED);
        // A manual account and a synced one sharing an external id: 1.0.x allowed it, V47 does not.
        execute(url, "INSERT INTO family_member (display_name) VALUES ('Test')");
        execute(url, "INSERT INTO account (name, provider, external_account_id, is_manual, member_id) VALUES "
            + "('Manual', NULL, 'ext-1', true, (SELECT min(id) FROM family_member)), "
            + "('Synced', 'TRADE_REPUBLIC', 'ext-1', false, (SELECT min(id) FROM family_member))");
        String history = "SELECT installed_rank || ' ' || version || ' ' || checksum "
            + "FROM flyway_schema_history ORDER BY installed_rank";
        List<String> before = query(url, history);

        assertThatThrownBy(() -> upgrade(url).migrate())
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("1 group(s) of active accounts share an external_account_id");

        // Nothing ran and nothing was deleted; the renumbering rolled back with the transaction.
        assertThat(query(url, history)).isEqualTo(before);
        assertThat(query(url, "SELECT name FROM account ORDER BY id")).containsExactly("Manual", "Synced");

        execute(url, "UPDATE account SET deleted_at = now() WHERE name = 'Manual'");
        upgrade(url).migrate();

        assertThat(upgrade(url).info().pending()).isEmpty();
        assertThat(query(url, "SELECT name FROM account ORDER BY id")).containsExactly("Manual", "Synced");
    }

    @Test
    void aTargetBelowV47SkipsTheDuplicateCheck() throws Exception {
        String url = createDatabase("release_duplicates_target");
        migrateRelease(url, RELEASE_1_0_18_UNCHANGED, RELEASE_1_0_18_MOVED, RELEASE_1_0_18_EDITED);
        execute(url, "INSERT INTO family_member (display_name) VALUES ('Test')");
        execute(url, "INSERT INTO account (name, provider, external_account_id, is_manual, member_id) VALUES "
            + "('Manual', NULL, 'ext-1', true, (SELECT min(id) FROM family_member)), "
            + "('Synced', 'TRADE_REPUBLIC', 'ext-1', false, (SELECT min(id) FROM family_member))");

        // V47 is above the target, so it cannot delete anything and the check has nothing to guard.
        Flyway.configure()
            .configuration(upgrade(url).getConfiguration())
            .target("46")
            .load()
            .migrate();

        assertThat(query(url, "SELECT count(*) FROM flyway_schema_history WHERE version = '47'"))
            .containsExactly("0");
        assertThat(query(url, "SELECT name FROM account ORDER BY id")).containsExactly("Manual", "Synced");
    }

    @Test
    void aDuplicateCommittedAfterTheUpFrontCheckStillStopsBeforeV47() throws Exception {
        String url = createDatabase("release_racing_duplicate");
        migrateRelease(url, RELEASE_1_0_18_UNCHANGED, RELEASE_1_0_18_MOVED, RELEASE_1_0_18_EDITED);
        execute(url, "INSERT INTO family_member (display_name) VALUES ('Test')");
        execute(url, "INSERT INTO account (name, provider, external_account_id, is_manual, member_id) VALUES "
            + "('Synced', 'TRADE_REPUBLIC', 'ext-1', false, (SELECT min(id) FROM family_member))");
        // The running 1.0.x instance syncs again once the up-front check has passed, before V47.
        Flyway flyway = upgrade(url, concurrentWrite(url, "46", writer -> {
            try (Statement st = writer.createStatement()) {
                st.execute("INSERT INTO account (name, provider, external_account_id, is_manual, member_id) VALUES "
                    + "('Resynced', 'TRADE_REPUBLIC', 'ext-1', false, (SELECT min(id) FROM family_member))");
            }
        }));

        assertThatThrownBy(flyway::migrate)
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("1 group(s) of active accounts share an external_account_id");

        assertThat(query(url, "SELECT name FROM account ORDER BY id")).containsExactly("Synced", "Resynced");
        assertThat(query(url, "SELECT version FROM flyway_schema_history WHERE version = '47'")).isEmpty();
    }

    @Test
    void accountWritesWaitUntilV47Commits() throws Exception {
        String url = createDatabase("release_v47_lock");
        migrateRelease(url, RELEASE_1_0_18_UNCHANGED, RELEASE_1_0_18_MOVED, RELEASE_1_0_18_EDITED);
        execute(url, "INSERT INTO family_member (display_name) VALUES ('Test')");
        List<String> writerErrors = new ArrayList<>();
        Flyway flyway = upgrade(url, concurrentWrite(url, "47", writer -> {
            try (Statement st = writer.createStatement()) {
                st.execute("SET lock_timeout = '500ms'");
                st.execute("INSERT INTO account (name, provider, external_account_id, is_manual, member_id) VALUES "
                    + "('Resynced', 'TRADE_REPUBLIC', 'ext-1', false, (SELECT min(id) FROM family_member))");
            } catch (SQLException e) {
                writerErrors.add(e.getSQLState());
            }
        }));

        flyway.migrate();

        assertThat(writerErrors).as("the insert timed out on the account lock").containsExactly("55P03");
        assertThat(upgrade(url).info().pending()).isEmpty();
    }

    @Test
    void aSecondStartOfARelease1018DatabaseChangesNothing() throws Exception {
        String url = createDatabase("release_second_start");
        migrateRelease(url, RELEASE_1_0_18_UNCHANGED, RELEASE_1_0_18_MOVED, RELEASE_1_0_18_EDITED);
        upgrade(url).migrate();
        String history = "SELECT installed_rank || ' ' || version || ' ' || script || ' ' || checksum "
            + "FROM flyway_schema_history ORDER BY installed_rank";
        List<String> before = query(url, history);

        MigrateResult second = upgrade(url).migrate();

        assertThat(second.migrationsExecuted).isZero();
        assertThat(query(url, history)).isEqualTo(before);
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
    private static Flyway upgrade(String url, Callback... others) {
        Callback[] callbacks = Stream.concat(Stream.of(new LegacyMigrationRenumberingCallback()), Stream.of(others))
            .toArray(Callback[]::new);
        return Flyway.configure()
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .outOfOrder(true)
            .callbacks(callbacks)
            .load();
    }

    private interface Write {
        void run(Connection writer) throws SQLException;
    }

    /** Another connection writes, and commits, just before {@code version} runs. */
    private static Callback concurrentWrite(String url, String version, Write write) {
        return new Callback() {
            @Override
            public boolean supports(Event event, Context context) {
                return event == Event.BEFORE_EACH_MIGRATE
                    && MigrationVersion.fromVersion(version).equals(context.getMigrationInfo().getVersion());
            }

            @Override
            public boolean canHandleInTransaction(Event event, Context context) {
                return true;
            }

            @Override
            public void handle(Event event, Context context) {
                try (Connection writer = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
                    write.run(writer);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public String getCallbackName() {
                // Flyway runs callbacks by name: this one after the callback under test.
                return "zz-concurrent-write";
            }
        };
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

    /** Applies a 1.0.x release's files under the names that release shipped them with. */
    private static void migrateRelease(String url, Set<Integer> unchanged, Map<String, String> moved,
                                       List<String> edited) throws Exception {
        Path current = Path.of(migrationDirectory());
        Path release = Files.createTempDirectory("release-migrations");
        try (Stream<Path> files = Files.list(current)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (unchanged.contains(version(name))) {
                    Files.copy(file, release.resolve(name));
                } else if (moved.containsKey(name)) {
                    Files.copy(file, release.resolve(moved.get(name)));
                }
            }
        }
        Path legacy = Path.of(LegacyMigrationRenumberingTest.class.getResource("/db/legacy-1.0.18").toURI());
        for (String name : edited) {
            Files.copy(legacy.resolve(name), release.resolve(name));
        }
        Flyway.configure()
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("filesystem:" + release)
            .load()
            .migrate();
    }

    private static String freshDatabase(String name) throws Exception {
        String url = createDatabase(name);
        upgrade(url).migrate();
        return url;
    }

    /**
     * Tables, columns, constraints, indexes and enum labels. Column order is ignored: out-of-order
     * migrations append columns in a different order than a fresh run. Sequences, triggers, views
     * and seed data are not compared.
     */
    private static List<String> schema(String url) throws SQLException {
        List<String> rows = new ArrayList<>();
        rows.addAll(query(url, "SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type || ' ' "
            + "|| udt_name || ' ' || is_nullable || ' ' || coalesce(column_default, '-') "
            + "FROM information_schema.columns WHERE table_schema = 'public' "
            + "AND table_name <> 'flyway_schema_history'"));
        rows.addAll(query(url, "SELECT 'constraint ' || conrelid::regclass || ' ' || conname || ' ' "
            + "|| pg_get_constraintdef(oid) FROM pg_constraint "
            + "WHERE connamespace = 'public'::regnamespace AND conrelid <> 0 "
            + "AND conrelid::regclass::text <> 'flyway_schema_history'"));
        rows.addAll(query(url, "SELECT 'index ' || indexdef FROM pg_indexes "
            + "WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'"));
        // Label position matters: ADD VALUE ... BEFORE changes ORDER BY on the column.
        rows.addAll(query(url, "SELECT 'enum ' || t.typname || ' ' || e.enumlabel || ' ' "
            + "|| row_number() OVER (PARTITION BY t.typname ORDER BY e.enumsortorder) FROM pg_enum e "
            + "JOIN pg_type t ON t.oid = e.enumtypid"));
        Collections.sort(rows);
        return rows;
    }

    private static void execute(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
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
