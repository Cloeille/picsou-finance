package com.picsou.migration;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves a database at V141 (the whole chain before V142, Banque Populaire's V134 included) keeps
 * every sidecar session through V142: same member, cookies, stored login, unsupported contracts,
 * CORUM Life ambiguous count, sync state and timestamps, so nobody has to reconnect. Banque
 * Populaire keeps its own table and is not touched.
 */
@RequiresPostgres
class V142SidecarSessionMigrationTest {

    private static final String[] TABLES = {
        "amex", "amundi", "bourse_direct", "bourso", "caisse_epargne",
        "cic", "cmes", "corum", "fortuneo", "sofidy"
    };

    /** The banks V142 moved: Banque Populaire followed in V147, La Banque Postale keeps its own table, Natixis arrives with V146, Trade Republic with V148. */
    private static final List<SidecarBank> V142_BANKS = java.util.Arrays.stream(SidecarBank.values())
        .filter(SidecarBank::storedInSidecarSession)
        .filter(bank -> bank != SidecarBank.NATIXIS && bank != SidecarBank.BANQUE_POPULAIRE
            && bank != SidecarBank.TRADE_REPUBLIC && bank != SidecarBank.BNP).toList();

    private static final String ROW = """
        SELECT bank, member_id, session_state, encrypted_credentials, unsupported_contracts,
               last_validated_at, is_active, sync_status, last_sync_started_at,
               last_sync_completed_at, last_sync_error, created_at, updated_at, last_sync_life_ambiguous
          FROM sidecar_session WHERE bank = ? AND member_id = ?
        """;

    private static final String BEFORE = """
        SELECT bank, count(*), md5(string_agg(line, ';' ORDER BY member_id))
          FROM (
        SELECT 'AMEX' AS bank, member_id, concat_ws('|', member_id, session_state, last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, created_at, updated_at) AS line FROM amex_session
         WHERE member_id NOT IN (SELECT id FROM family_member WHERE display_name IN ('carol', 'dave'))
        UNION ALL
        SELECT 'AMUNDI' AS bank, member_id, concat_ws('|', member_id, session_state, last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, created_at, updated_at) AS line FROM amundi_session
         WHERE member_id NOT IN (SELECT id FROM family_member WHERE display_name IN ('carol', 'dave'))
        UNION ALL
        SELECT 'BOURSE_DIRECT' AS bank, member_id, concat_ws('|', member_id, session_state, last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, created_at, updated_at) AS line FROM bourse_direct_session
         WHERE member_id NOT IN (SELECT id FROM family_member WHERE display_name IN ('carol', 'dave'))
        UNION ALL
        SELECT 'BOURSO' AS bank, member_id, concat_ws('|', member_id, session_state, last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, created_at, updated_at) AS line FROM bourso_session
         WHERE member_id NOT IN (SELECT id FROM family_member WHERE display_name IN ('carol', 'dave'))
        UNION ALL
        SELECT 'CAISSE_EPARGNE' AS bank, member_id, concat_ws('|', member_id, session_state, last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, created_at, updated_at) AS line FROM caisse_epargne_session
         WHERE member_id NOT IN (SELECT id FROM family_member WHERE display_name IN ('carol', 'dave'))
        UNION ALL
        SELECT 'CIC' AS bank, member_id, concat_ws('|', member_id, session_state, last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, created_at, updated_at) AS line FROM cic_session
         WHERE member_id NOT IN (SELECT id FROM family_member WHERE display_name IN ('carol', 'dave'))
        UNION ALL
        SELECT 'CMES' AS bank, member_id, concat_ws('|', member_id, session_state, last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, created_at, updated_at) AS line FROM cmes_session
         WHERE member_id NOT IN (SELECT id FROM family_member WHERE display_name IN ('carol', 'dave'))
        UNION ALL
        SELECT 'CORUM' AS bank, member_id, concat_ws('|', member_id, session_state, last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, created_at, updated_at) AS line FROM corum_session
         WHERE member_id NOT IN (SELECT id FROM family_member WHERE display_name IN ('carol', 'dave'))
        UNION ALL
        SELECT 'FORTUNEO' AS bank, member_id, concat_ws('|', member_id, session_state, last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, created_at, updated_at) AS line FROM fortuneo_session
         WHERE member_id NOT IN (SELECT id FROM family_member WHERE display_name IN ('carol', 'dave'))
        UNION ALL
        SELECT 'SOFIDY' AS bank, member_id, concat_ws('|', member_id, session_state, last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, created_at, updated_at) AS line FROM sofidy_session
         WHERE member_id NOT IN (SELECT id FROM family_member WHERE display_name IN ('carol', 'dave'))
          ) rows GROUP BY bank
        """;

    private static final String AFTER = """
        SELECT bank, count(*), md5(string_agg(concat_ws('|', member_id, session_state, last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, created_at, updated_at), ';' ORDER BY member_id))
          FROM sidecar_session
         WHERE bank <> 'BANQUE_POPULAIRE'
           AND member_id NOT IN (SELECT id FROM family_member WHERE display_name IN ('carol', 'dave'))
         GROUP BY bank
        """;

    private static final Instant STARTED = Instant.parse("2026-10-03T08:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    static long alice;
    static long bob;
    static long carol;
    static long dave;
    static long erin;
    static long frank;
    static long gina;
    static java.util.Map<String, String> before;
    static java.util.Map<String, String> after;
    private static final int BULK = 300;

    @BeforeAll
    static void seedV141AndMigrate() throws SQLException {
        PostgresContainers.migrateTo(POSTGRES, "141");
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            alice = newMember(c, "alice");
            bob = newMember(c, "bob");
            for (String table : TABLES) {
                execute(c, "INSERT INTO " + table + "_session (member_id, session_state, last_validated_at, "
                    + "is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error, "
                    + "created_at, updated_at) VALUES (" + alice + ", 'state-" + table + "', "
                    + "'2026-10-01T08:00:00Z', false, 'FAILED', '2026-10-02T08:00:00Z', "
                    + "'2026-10-02T08:05:00Z', 'SESSION_EXPIRED', '2026-09-01T08:00:00Z', '2026-10-02T08:05:00Z')");
                execute(c, "INSERT INTO " + table + "_session (member_id, session_state, sync_status) "
                    + "VALUES (" + bob + ", 'bob-" + table + "', 'SUCCESS')");
            }
            execute(c, "UPDATE bourso_session SET encrypted_credentials = 'bourso-login'");
            execute(c, "UPDATE cic_session SET encrypted_credentials = 'cic-login'");
            execute(c, "UPDATE cmes_session SET encrypted_credentials = 'cmes-login'");
            // V127 and V124 gave corum_session a stored login and the CORUM Life ambiguous count.
            execute(c, "UPDATE corum_session SET encrypted_credentials = 'corum-login', last_sync_life_ambiguous = 2 "
                + "WHERE member_id = " + alice);
            execute(c, "UPDATE caisse_epargne_session SET unsupported_contracts = '[{\"externalId\":\"x\"}]'");
            carol = newMember(c, "carol");
            dave = newMember(c, "dave");
            // Rows the CORUM and Sofidy tables accepted although sidecar_session refuses them:
            // carol has a stale error outside FAILED, dave a FAILED row with no reason.
            for (String table : new String[] {"corum", "sofidy"}) {
                execute(c, "INSERT INTO " + table + "_session (member_id, session_state, sync_status, last_sync_error) "
                    + "VALUES (" + carol + ", 'carol', 'SUCCESS', 'INTERNAL_ERROR')");
                execute(c, "INSERT INTO " + table + "_session (member_id, session_state, sync_status) "
                    + "VALUES (" + dave + ", 'dave', 'FAILED')");
            }
            execute(c, "INSERT INTO banque_populaire_session (member_id, session_state, region) "
                + "VALUES (" + alice + ", 'bp-state', 'RIVES_DE_PARIS')");
            // A bulk of members, each with a distinct session on every bank.
            execute(c, "INSERT INTO family_member (display_name) SELECT 'bulk-' || g FROM generate_series(1, " + BULK + ") g");
            for (String table : TABLES) {
                execute(c, "INSERT INTO " + table + "_session (member_id, session_state, sync_status, last_validated_at) "
                    + "SELECT id, '" + table + "-' || id || '-' || md5(id::text), "
                    + "CASE WHEN id % 3 = 0 THEN 'SUCCESS' ELSE 'IDLE' END, NOW() - (id || ' minutes')::interval "
                    + "FROM family_member WHERE display_name LIKE 'bulk-%'");
            }
            erin = newMember(c, "erin");
            frank = newMember(c, "frank");
            gina = newMember(c, "gina");
            // The three statuses left: no error, and a start instant for the two that have begun.
            for (String table : TABLES) {
                execute(c, "INSERT INTO " + table + "_session (member_id, session_state, sync_status, "
                    + "last_sync_started_at) VALUES (" + erin + ", 'erin', 'QUEUED', '" + STARTED + "')");
                execute(c, "INSERT INTO " + table + "_session (member_id, session_state, sync_status, "
                    + "last_sync_started_at) VALUES (" + frank + ", 'frank', 'RUNNING', '" + STARTED + "')");
                execute(c, "INSERT INTO " + table + "_session (member_id, session_state, sync_status) "
                    + "VALUES (" + gina + ", 'gina', 'IDLE')");
            }
        }
        before = fingerprints(BEFORE);
        PostgresContainers.migrateTo(POSTGRES, "142");
        // Taken right here: later tests insert their own rows into sidecar_session.
        after = fingerprints(AFTER);
    }

    /** Per bank: row count and a digest of the columns all ten tables share (credentials are checked row by row). */
    private static java.util.Map<String, String> fingerprints(String sql) throws SQLException {
        java.util.Map<String, String> result = new java.util.TreeMap<>();
        try (Connection c = PostgresContainers.connection(POSTGRES);
             ResultSet rs = c.createStatement().executeQuery(sql)) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getLong(2) + " " + rs.getString(3));
            }
        }
        return result;
    }

    /** Hundreds of members on every bank: no row lost, duplicated or altered by the copy. */
    @Test
    void aBulkOfSessionsKeepsItsCountAndEveryColumnDigest() throws SQLException {
        assertThat(before).hasSize(TABLES.length);
        assertThat(before.values()).allSatisfy(v -> assertThat(Long.parseLong(v.split(" ")[0])).isGreaterThan(BULK));
        assertThat(after).isEqualTo(before);
    }

    @Test
    void banquePopulaireKeepsItsTableAndItsRow() throws SQLException {
        assertThat(count("SELECT count(*) FROM banque_populaire_session WHERE session_state = 'bp-state'")).isOne();
        assertThat(count("SELECT count(*) FROM sidecar_session WHERE bank = 'BANQUE_POPULAIRE'")).isZero();
    }

    @Test
    void everyRowOfEveryBankIsCopiedByteForByte() throws SQLException {
        for (SidecarBank bank : V142_BANKS) {
            String table = bank.name().toLowerCase(Locale.ROOT);
            List<Object> row = row(bank, alice);
            assertThat(row).as(bank.name()).containsExactly(
                bank.name(), alice, "state-" + table,
                switch (bank) {
                    case BOURSO -> "bourso-login";
                    case CIC -> "cic-login";
                    case CMES -> "cmes-login";
                    case CORUM -> "corum-login";
                    default -> null;
                },
                bank == SidecarBank.CAISSE_EPARGNE ? "[{\"externalId\":\"x\"}]" : null,
                Instant.parse("2026-10-01T08:00:00Z"), false, "FAILED", Instant.parse("2026-10-02T08:00:00Z"),
                Instant.parse("2026-10-02T08:05:00Z"), "SESSION_EXPIRED", Instant.parse("2026-09-01T08:00:00Z"),
                Instant.parse("2026-10-02T08:05:00Z"), bank == SidecarBank.CORUM ? 2 : 0);
            // Index 6 is is_active, 7 sync_status, 10 last_sync_error.
            List<Object> bobRow = row(bank, bob);
            assertThat(bobRow.subList(0, 3)).containsExactly(bank.name(), bob, "bob-" + table);
            assertThat(List.of(bobRow.get(6), bobRow.get(7))).as(bank.name()).containsExactly(true, "SUCCESS");
            assertThat(bobRow.get(10)).as(bank.name()).isNull();
        }
        assertThat(count("SELECT count(*) FROM sidecar_session WHERE member_id IN (" + alice + ", " + bob + ")"))
            .isEqualTo(2L * TABLES.length);
    }

    @Test
    void everyStatusIsCopied() throws SQLException {
        for (SidecarBank bank : V142_BANKS) {
            assertStatusCopied(bank, erin, "QUEUED", STARTED);
            assertStatusCopied(bank, frank, "RUNNING", STARTED);
            assertStatusCopied(bank, gina, "IDLE", null);
        }
    }

    @Test
    void aCorumOrSofidyRowBreakingTheFailedErrorRuleIsRepairedNotRefused() throws SQLException {
        for (SidecarBank bank : List.of(SidecarBank.CORUM, SidecarBank.SOFIDY)) {
            // Index 7 is sync_status, 10 is last_sync_error.
            List<Object> stale = row(bank, carol);
            assertThat(stale.get(7)).as(bank.name()).isEqualTo("SUCCESS");
            assertThat(stale.get(10)).as(bank.name()).isNull();
            List<Object> reasonless = row(bank, dave);
            assertThat(reasonless.get(7)).as(bank.name()).isEqualTo("FAILED");
            assertThat(reasonless.get(10)).as(bank.name()).isEqualTo("INTERNAL_ERROR");
        }
    }

    @Test
    void thePerBankTablesAreGone() throws SQLException {
        assertThat(count("SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename IN ("
            + String.join(", ", java.util.Arrays.stream(TABLES).map(t -> "'" + t + "_session'").toList()) + ")"))
            .isZero();
    }

    @Test
    void oneSessionPerBankAndMember() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state) "
                + "VALUES ('AMEX', " + alice + ", 'twice')"))
                .hasMessageContaining("uq_sidecar_session_bank_member");
        }
    }

    @Test
    void aBankCanOnlyStoreTheErrorCodesItDeclares() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "codes");
            // CAPTCHA_BLOCKED belongs to Amundi only.
            execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, sync_status, last_sync_error) "
                + "VALUES ('AMUNDI', " + member + ", 's', 'FAILED', 'CAPTCHA_BLOCKED')");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session "
                + "(bank, member_id, session_state, sync_status, last_sync_error) "
                + "VALUES ('AMEX', " + member + ", 's', 'FAILED', 'CAPTCHA_BLOCKED')"))
                .hasMessageContaining("ck_sidecar_session_last_sync_error");
        }
    }

    @Test
    void aFailureAlwaysSaysWhyAndNothingElseCarriesAnError() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "failed");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, "
                + "sync_status) VALUES ('CORUM', " + member + ", 's', 'FAILED')"))
                .hasMessageContaining("ck_sidecar_session_failed_error");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, "
                + "sync_status, last_sync_error) VALUES ('CORUM', " + member + ", 's', 'SUCCESS', 'INTERNAL_ERROR')"))
                .hasMessageContaining("ck_sidecar_session_failed_error");
        }
    }

    /** Decision D5 for Caisse d'Epargne, and every bank that never asks to remember a login. */
    @Test
    void onlyTheBanksThatKeepALoginCanStoreOne() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "login");
            for (SidecarBank bank : V142_BANKS) {
                String insert = "INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials) "
                    + "VALUES ('" + bank.name() + "', " + member + ", 's', 'login')";
                switch (bank) {
                    case BOURSO, CIC, CMES, CORUM -> execute(c, insert);
                    default -> assertThatThrownBy(() -> execute(c, insert)).as(bank.name())
                        .hasMessageContaining("ck_sidecar_session_credentials");
                }
            }
        }
    }

    /** V124's ck_corum_session_life_ambiguous, carried over, and kept to CORUM. */
    @Test
    void theLifeAmbiguousCountIsNeverNegativeAndOnlyCorumHasOne() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "ambiguous");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, "
                + "last_sync_life_ambiguous) VALUES ('CORUM', " + member + ", 's', -1)"))
                .hasMessageContaining("ck_sidecar_session_life_ambiguous");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, "
                + "last_sync_life_ambiguous) VALUES ('SOFIDY', " + member + ", 's', 1)"))
                .hasMessageContaining("ck_sidecar_session_life_ambiguous");
        }
    }

    @Test
    void deletingAMemberDeletesItsSessions() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "leaves");
            execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state) VALUES ('SOFIDY', "
                + member + ", 's')");
            execute(c, "DELETE FROM family_member WHERE id = " + member);
        }
        assertThat(count("SELECT count(*) FROM sidecar_session WHERE bank = 'SOFIDY' AND session_state = 's'"))
            .isZero();
    }

    private static void assertStatusCopied(SidecarBank bank, long member, String status, Instant started)
        throws SQLException {
        // Index 7 is sync_status, 8 last_sync_started_at, 10 last_sync_error.
        List<Object> row = row(bank, member);
        assertThat(row.get(7)).as("%s %s status", bank, status).isEqualTo(status);
        assertThat(row.get(8)).as("%s %s started at", bank, status).isEqualTo(started);
        assertThat(row.get(10)).as("%s %s error", bank, status).isNull();
    }

    private static List<Object> row(SidecarBank bank, long member) throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES); PreparedStatement st = c.prepareStatement(ROW)) {
            st.setString(1, bank.name());
            st.setLong(2, member);
            try (ResultSet rs = st.executeQuery()) {
                assertThat(rs.next()).as("%s row of member %d", bank, member).isTrue();
                List<Object> values = new ArrayList<>();
                values.add(rs.getString("bank"));
                values.add(rs.getLong("member_id"));
                values.add(rs.getString("session_state"));
                values.add(rs.getString("encrypted_credentials"));
                values.add(rs.getString("unsupported_contracts"));
                values.add(instant(rs, "last_validated_at"));
                values.add(rs.getBoolean("is_active"));
                values.add(rs.getString("sync_status"));
                values.add(instant(rs, "last_sync_started_at"));
                values.add(instant(rs, "last_sync_completed_at"));
                values.add(rs.getString("last_sync_error"));
                values.add(instant(rs, "created_at"));
                values.add(instant(rs, "updated_at"));
                values.add(rs.getInt("last_sync_life_ambiguous"));
                return values;
            }
        }
    }

    /** A timestamptz as an instant: getString renders it in the JVM's time zone. */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static long newMember(Connection c, String name) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
            "INSERT INTO family_member (display_name) VALUES (?) RETURNING id")) {
            st.setString(1, name);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES);
             ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        c.createStatement().execute(sql);
    }
}
