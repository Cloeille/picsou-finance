package com.picsou.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** V147 adds the optional, encrypted remembered identifier to Banque Populaire sessions. */
@Testcontainers
@EnabledIf("dockerAvailable")
class V147BanquePopulaireStoredIdentifierMigrationTest {

    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException(
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found. "
                    + "The V147 migration test cannot be skipped. Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("147")
            .load()
            .migrate();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    void addsTheEncryptedCredentialsColumn() throws SQLException {
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "SELECT data_type FROM information_schema.columns "
                     + "WHERE table_name = 'banque_populaire_session' AND column_name = 'encrypted_credentials'");
             ResultSet rs = st.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("text");
        }
    }

    @Test
    void acceptsASessionWithAndWithoutTheRememberedIdentifier() throws SQLException {
        long member = newMember("bp-v147");
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "INSERT INTO banque_populaire_session (member_id, region, session_state, encrypted_credentials) "
                     + "VALUES (?, 'RIVES_DE_PARIS', ?, ?)")) {
            st.setLong(1, member);
            st.setString(2, "cookies");
            st.setString(3, "encrypted-identifier");
            assertThat(st.executeUpdate()).isEqualTo(1);
        }
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "SELECT encrypted_credentials FROM banque_populaire_session WHERE member_id = ?")) {
            st.setLong(1, member);
            try (ResultSet rs = st.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("encrypted-identifier");
            }
        }

        long withoutIdentifier = newMember("bp-v147-null");
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "INSERT INTO banque_populaire_session (member_id, region, session_state) VALUES (?, 'BRED', ?)")) {
            st.setLong(1, withoutIdentifier);
            st.setString(2, "cookies");
            assertThat(st.executeUpdate()).isEqualTo(1);
        }
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "SELECT encrypted_credentials FROM banque_populaire_session WHERE member_id = ?")) {
            st.setLong(1, withoutIdentifier);
            try (ResultSet rs = st.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNull();
            }
        }
    }

    private static long newMember(String name) throws SQLException {
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "INSERT INTO family_member (display_name) VALUES (?) RETURNING id")) {
            st.setString(1, name);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
