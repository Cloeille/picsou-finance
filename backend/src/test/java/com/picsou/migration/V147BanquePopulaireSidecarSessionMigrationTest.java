package com.picsou.migration;

import com.picsou.port.BanquePopulaireRegion;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves a database just before V147 keeps every Banque Populaire session through V147: same member,
 * cookies, region, unsupported contracts, sync state and timestamps, so nobody has to reconnect.
 */
@RequiresPostgres
class V147BanquePopulaireSidecarSessionMigrationTest {

    private static final String ROW = """
        SELECT member_id, session_state, encrypted_credentials, unsupported_contracts, region,
               last_validated_at, is_active, sync_status, last_sync_started_at,
               last_sync_completed_at, last_sync_error, created_at, updated_at, last_sync_life_ambiguous
          FROM sidecar_session WHERE bank = 'BANQUE_POPULAIRE' AND member_id = ?
        """;

    private static final Instant STARTED = Instant.parse("2026-10-03T08:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    static long alice;
    static long bob;
    static long erin;
    static long frank;
    static long gina;
    static final List<Long> REGIONS = new ArrayList<>();

    @BeforeAll
    static void seedBeforeV147AndMigrate() throws SQLException {
        PostgresContainers.migrateTo(POSTGRES, "146");
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            alice = newMember(c, "alice");
            execute(c, "INSERT INTO banque_populaire_session (member_id, region, session_state, last_validated_at, "
                + "is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error, "
                + "unsupported_contracts, created_at, updated_at) VALUES (" + alice + ", 'BRED', 'state-alice', "
                + "'2026-10-01T08:00:00Z', false, 'FAILED', '2026-10-02T08:00:00Z', '2026-10-02T08:05:00Z', "
                + "'SESSION_EXPIRED', '[{\"externalId\":\"x\",\"familyCode\":\"PEA\"}]', "
                + "'2026-09-01T08:00:00Z', '2026-10-02T08:05:00Z')");
            bob = newMember(c, "bob");
            execute(c, "INSERT INTO banque_populaire_session (member_id, region, session_state, sync_status) "
                + "VALUES (" + bob + ", 'SUD', 'state-bob', 'SUCCESS')");
            erin = newMember(c, "erin");
            frank = newMember(c, "frank");
            gina = newMember(c, "gina");
            execute(c, "INSERT INTO banque_populaire_session (member_id, region, session_state, sync_status, "
                + "last_sync_started_at) VALUES (" + erin + ", 'NORD', 'erin', 'QUEUED', '" + STARTED + "')");
            execute(c, "INSERT INTO banque_populaire_session (member_id, region, session_state, sync_status, "
                + "last_sync_started_at) VALUES (" + frank + ", 'NORD', 'frank', 'RUNNING', '" + STARTED + "')");
            execute(c, "INSERT INTO banque_populaire_session (member_id, region, session_state, sync_status) "
                + "VALUES (" + gina + ", 'NORD', 'gina', 'IDLE')");
            // One member per regional bank, so every region V134 accepted is proven to fit V147's CHECK.
            for (BanquePopulaireRegion region : BanquePopulaireRegion.values()) {
                long member = newMember(c, "region-" + region.name());
                REGIONS.add(member);
                execute(c, "INSERT INTO banque_populaire_session (member_id, region, session_state) VALUES ("
                    + member + ", '" + region.name() + "', 's')");
            }
            // A Caisse d'Epargne row already in sidecar_session must keep a null region.
            execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state) VALUES ('CAISSE_EPARGNE', "
                + alice + ", 'ce-state')");
        }
        PostgresContainers.migrateTo(POSTGRES, "147");
    }

    @Test
    void aSessionIsCopiedByteForByte() throws SQLException {
        assertThat(row(alice)).containsExactly(
            alice, "state-alice", null, "[{\"externalId\":\"x\",\"familyCode\":\"PEA\"}]", "BRED",
            Instant.parse("2026-10-01T08:00:00Z"), false, "FAILED", Instant.parse("2026-10-02T08:00:00Z"),
            Instant.parse("2026-10-02T08:05:00Z"), "SESSION_EXPIRED", Instant.parse("2026-09-01T08:00:00Z"),
            Instant.parse("2026-10-02T08:05:00Z"), 0);
        // Index 1 is session_state, 4 region, 6 is_active, 7 sync_status, 10 last_sync_error.
        List<Object> bobRow = row(bob);
        assertThat(List.of(bobRow.get(1), bobRow.get(4), bobRow.get(6), bobRow.get(7)))
            .containsExactly("state-bob", "SUD", true, "SUCCESS");
        assertThat(bobRow.get(10)).isNull();
    }

    @Test
    void everyStatusIsCopied() throws SQLException {
        assertStatusCopied(erin, "QUEUED", STARTED);
        assertStatusCopied(frank, "RUNNING", STARTED);
        assertStatusCopied(gina, "IDLE", null);
    }

    @Test
    void everyRegionIsKept() throws SQLException {
        BanquePopulaireRegion[] regions = BanquePopulaireRegion.values();
        for (int i = 0; i < regions.length; i++) {
            assertThat(row(REGIONS.get(i)).get(4)).isEqualTo(regions[i].name());
        }
        List<Long> seeded = new ArrayList<>(List.of(alice, bob, erin, frank, gina));
        seeded.addAll(REGIONS);
        assertThat(count("SELECT count(*) FROM sidecar_session WHERE bank = 'BANQUE_POPULAIRE' AND member_id IN ("
            + String.join(", ", seeded.stream().map(String::valueOf).toList()) + ")"))
            .isEqualTo((long) seeded.size());
    }

    @Test
    void theOtherBanksRowsAreUntouched() throws SQLException {
        assertThat(count("SELECT count(*) FROM sidecar_session WHERE bank = 'CAISSE_EPARGNE' AND member_id = "
            + alice + " AND session_state = 'ce-state' AND region IS NULL")).isEqualTo(1L);
    }

    @Test
    void theBanquePopulaireTableIsGone() throws SQLException {
        assertThat(count("SELECT count(*) FROM pg_tables WHERE schemaname = 'public' "
            + "AND tablename = 'banque_populaire_session'")).isZero();
    }

    @Test
    void aBanquePopulaireRowNeedsAKnownRegion() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "no-region");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state) "
                + "VALUES ('BANQUE_POPULAIRE', " + member + ", 's')"))
                .hasMessageContaining("ck_sidecar_session_region");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, region) "
                + "VALUES ('BANQUE_POPULAIRE', " + member + ", 's', 'ATLANTIS')"))
                .hasMessageContaining("ck_sidecar_session_region");
        }
    }

    @Test
    void noOtherBankCarriesARegion() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "stray-region");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, region) "
                + "VALUES ('CAISSE_EPARGNE', " + member + ", 's', 'BRED')"))
                .hasMessageContaining("ck_sidecar_session_region");
        }
    }

    /** Decision D5: cookies only, as banque_populaire_session had no credentials column. */
    @Test
    void aBanquePopulaireRowCannotStoreALogin() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "login");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session "
                + "(bank, member_id, session_state, region, encrypted_credentials) "
                + "VALUES ('BANQUE_POPULAIRE', " + member + ", 's', 'BRED', 'login')"))
                .hasMessageContaining("ck_sidecar_session_credentials");
        }
    }

    @Test
    void aBanquePopulaireRowStoresOnlyItsOwnErrorCodes() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "codes");
            execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, region, sync_status, "
                + "last_sync_error) VALUES ('BANQUE_POPULAIRE', " + member + ", 's', 'BRED', 'FAILED', 'KEYPAD_CHANGED')");
            long other = newMember(c, "codes-other");
            // CAPTCHA_BLOCKED belongs to Amundi only.
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, "
                + "region, sync_status, last_sync_error) VALUES ('BANQUE_POPULAIRE', " + other
                + ", 's', 'BRED', 'FAILED', 'CAPTCHA_BLOCKED')"))
                .hasMessageContaining("ck_sidecar_session_last_sync_error");
        }
    }

    private static void assertStatusCopied(long member, String status, Instant started) throws SQLException {
        // Index 7 is sync_status, 8 last_sync_started_at, 10 last_sync_error.
        List<Object> row = row(member);
        assertThat(row.get(7)).as("%s status", status).isEqualTo(status);
        assertThat(row.get(8)).as("%s started at", status).isEqualTo(started);
        assertThat(row.get(10)).as("%s error", status).isNull();
    }

    private static List<Object> row(long member) throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES); PreparedStatement st = c.prepareStatement(ROW)) {
            st.setLong(1, member);
            try (ResultSet rs = st.executeQuery()) {
                assertThat(rs.next()).as("Banque Populaire row of member %d", member).isTrue();
                List<Object> values = new ArrayList<>();
                values.add(rs.getLong("member_id"));
                values.add(rs.getString("session_state"));
                values.add(rs.getString("encrypted_credentials"));
                values.add(rs.getString("unsupported_contracts"));
                values.add(rs.getString("region"));
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
