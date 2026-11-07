package com.picsou.migration;

import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V129 lands after V131-V141 were released, so a deployed database is already at V141 when it
 * first sees V129. This proves application.yml's {@code out-of-order: true} applies it there: the
 * Caisse d'Epargne row moves to sidecar_session and the Banque Populaire table (V134) is untouched.
 */
@RequiresPostgres
class V129OutOfOrderMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    static Path withoutV129;
    static MigrateResult outOfOrder;
    static long member;

    @BeforeAll
    static void migrateToV141WithoutV129ThenApplyIt() throws IOException, SQLException {
        withoutV129 = Files.createTempDirectory("migrations-without-v129");
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path file : files.filter(p -> !p.getFileName().toString().startsWith("V129__")).toList()) {
                Files.copy(file, withoutV129.resolve(file.getFileName()));
            }
        }
        PostgresContainers.flyway(POSTGRES).locations("filesystem:" + withoutV129).target("141").load().migrate();
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            member = insertMember(c);
            execute(c, "INSERT INTO caisse_epargne_session (member_id, session_state) VALUES (?, 'ce-state')", member);
            execute(c, "INSERT INTO banque_populaire_session (member_id, session_state, region) "
                + "VALUES (?, 'bp-state', 'RIVES_DE_PARIS')", member);
        }
        outOfOrder = PostgresContainers.flyway(POSTGRES).outOfOrder(true).load().migrate();
    }

    @AfterAll
    static void cleanUp() throws IOException {
        try (Stream<Path> files = Files.list(withoutV129)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(withoutV129);
    }

    @Test
    void onlyV129IsAppliedOutOfOrder() {
        assertThat(outOfOrder.migrations).extracting(m -> m.version).isEqualTo(List.of("129"));
        assertThat(outOfOrder.migrations.get(0).type).isEqualTo("SQL");
    }

    @Test
    void theCaisseEpargneSessionMovesToTheSharedTable() throws SQLException {
        assertThat(single("SELECT session_state FROM sidecar_session WHERE bank = 'CAISSE_EPARGNE' AND member_id = ?"))
            .isEqualTo("ce-state");
        assertThat(single("SELECT count(*)::text FROM pg_tables WHERE tablename = 'caisse_epargne_session'"))
            .isEqualTo("0");
    }

    @Test
    void theBanquePopulaireTableIsLeftAsItWas() throws SQLException {
        assertThat(single("SELECT session_state FROM banque_populaire_session WHERE member_id = ?"))
            .isEqualTo("bp-state");
        assertThat(single("SELECT count(*)::text FROM sidecar_session WHERE bank = 'BANQUE_POPULAIRE' AND member_id = ?"))
            .isEqualTo("0");
    }

    private static long insertMember(Connection c) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                 "INSERT INTO family_member (display_name) VALUES ('out-of-order') RETURNING id");
             ResultSet rs = st.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execute(Connection c, String sql, long id) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, id);
            st.execute();
        }
    }

    private static String single(String sql) throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES); PreparedStatement st = c.prepareStatement(sql)) {
            if (sql.contains("?")) {
                st.setLong(1, member);
            }
            try (ResultSet rs = st.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }
}
